package com.gproust.sprout

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.local.SproutDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration 13 -> 14: the one that makes the data mergeable for partner sync.
 *
 * There is no local Android toolchain, so this test is the only place the
 * migration is ever proven to work (ADR-0006) — and it runs on data a user
 * cannot re-enter (ADR-0002). It builds a real v13 database by hand, then opens
 * it *through Room*, which runs the migration and validates the resulting schema
 * against the entity definitions: if the `ALTER TABLE`s and the entities ever
 * drift apart, opening throws here rather than on someone's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-13-14-test.db"
    private var db: SproutDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    /** The v13 schema, as it shipped in 1.5.0 — before any sync column existed. */
    private fun createV13Database(): SupportSQLiteDatabase {
        context.deleteDatabase(dbName)
        val callback = object : SupportSQLiteOpenHelper.Callback(13) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `baby` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `birthDate` INTEGER NOT NULL, " +
                        "`archived` INTEGER NOT NULL DEFAULT 0, " +
                        "`feedingReminderEnabled` INTEGER, `feedingReminderIntervalMinutes` INTEGER)",
                )
                db.execSQL(
                    "CREATE TABLE `feeding` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`babyId` INTEGER NOT NULL DEFAULT 1, `type` TEXT NOT NULL, `side` TEXT, " +
                        "`amountMl` INTEGER, `startTime` INTEGER NOT NULL, `endTime` INTEGER, " +
                        "`leftDurationMs` INTEGER, `rightDurationMs` INTEGER, " +
                        "`segments` TEXT NOT NULL DEFAULT '', `notes` TEXT)",
                )
                db.execSQL("CREATE INDEX `index_feeding_babyId` ON `feeding` (`babyId`)")
                db.execSQL(
                    "CREATE TABLE `sleep` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`babyId` INTEGER NOT NULL DEFAULT 1, `startTime` INTEGER NOT NULL, " +
                        "`endTime` INTEGER, `notes` TEXT)",
                )
                db.execSQL("CREATE INDEX `index_sleep_babyId` ON `sleep` (`babyId`)")
                db.execSQL(
                    "CREATE TABLE `diaper` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`babyId` INTEGER NOT NULL DEFAULT 1, `time` INTEGER NOT NULL, " +
                        "`wet` INTEGER NOT NULL DEFAULT 0, `dirty` INTEGER NOT NULL DEFAULT 0, " +
                        "`stoolColor` TEXT, `notes` TEXT)",
                )
                db.execSQL("CREATE INDEX `index_diaper_babyId` ON `diaper` (`babyId`)")
                db.execSQL(
                    "CREATE TABLE `growth` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`babyId` INTEGER NOT NULL DEFAULT 1, `time` INTEGER NOT NULL, " +
                        "`weightGrams` INTEGER, `heightMm` INTEGER, `headMm` INTEGER, `notes` TEXT)",
                )
                db.execSQL("CREATE INDEX `index_growth_babyId` ON `growth` (`babyId`)")
                db.execSQL(
                    "CREATE TABLE `treatment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`babyId` INTEGER NOT NULL, `name` TEXT NOT NULL, `dose` TEXT, " +
                        "`intervalDays` INTEGER NOT NULL, `timesOfDay` TEXT NOT NULL, " +
                        "`startDate` INTEGER NOT NULL, `endDate` INTEGER, " +
                        "`remindersEnabled` INTEGER NOT NULL, `active` INTEGER NOT NULL, `notes` TEXT)",
                )
                db.execSQL("CREATE INDEX `index_treatment_babyId` ON `treatment` (`babyId`)")
                db.execSQL(
                    "CREATE TABLE `pumping` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`time` INTEGER NOT NULL, `amountMl` INTEGER NOT NULL, `side` TEXT, " +
                        "`storage` TEXT NOT NULL, `notes` TEXT)",
                )
                db.execSQL(
                    "CREATE TABLE `wellbeing` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`time` INTEGER NOT NULL, `mood` INTEGER NOT NULL, `bleeding` TEXT, " +
                        "`recovery` TEXT, `breast` TEXT, `notes` TEXT)",
                )
                db.execSQL(
                    "CREATE TABLE `parent_profile` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                        "`gaveBirth` INTEGER NOT NULL, `breastfeeding` INTEGER NOT NULL, " +
                        "`deliveryType` TEXT, `askHealing` INTEGER NOT NULL DEFAULT 1, " +
                        "`askBleeding` INTEGER NOT NULL DEFAULT 1, " +
                        "`askBreasts` INTEGER NOT NULL DEFAULT 1, " +
                        "`trackWellbeing` INTEGER NOT NULL DEFAULT 1, " +
                        "`lastCheckIn` INTEGER, `activeBabyId` INTEGER, PRIMARY KEY(`id`))",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    /** Reopen the migrated database through Room, which validates the schema on the way in. */
    private fun openWithRoom(): SproutDatabase =
        Room.databaseBuilder(context, SproutDatabase::class.java, dbName)
            .addMigrations(SproutDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String> =
        query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SupportSQLiteDatabase.longs(sql: String): List<Long> =
        query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

    @Test
    fun backfillsEveryExistingRowWithItsOwnIdentity() {
        val before = System.currentTimeMillis()
        createV13Database().use { v13 ->
            v13.execSQL("INSERT INTO `baby` (name, birthDate) VALUES ('Sam', 1700000000000)")
            v13.execSQL(
                "INSERT INTO `feeding` (babyId, type, startTime, amountMl) VALUES " +
                    "(1, 'BOTTLE', 1700000100000, 90), " +
                    "(1, 'BOTTLE', 1700000200000, 80), " +
                    "(1, 'BREAST', 1700000300000, NULL)",
            )
            v13.execSQL(
                "INSERT INTO `pumping` (time, amountMl, storage) VALUES (1700000400000, 120, 'FRIDGE')",
            )
        }

        // Opening through Room runs the migration and validates the schema.
        val migrated = openWithRoom().openHelper.writableDatabase

        val uids = migrated.strings("SELECT uid FROM feeding ORDER BY id")
        assertEquals(3, uids.size)
        assertEquals("every row gets its own uid", 3, uids.toSet().size)
        uids.forEach { uid ->
            assertEquals("uid is a UUID: $uid", 36, uid.length)
            assertEquals("uid is version 4: $uid", '4', uid[14])
            assertTrue("uid has a valid variant: $uid", uid[19] in "89ab")
        }
        // The other tables are backfilled the same way.
        assertTrue(migrated.strings("SELECT uid FROM baby").single().isNotEmpty())
        assertTrue(migrated.strings("SELECT uid FROM pumping").single().isNotEmpty())
        assertNotEquals(
            "a uid is unique across tables too",
            migrated.strings("SELECT uid FROM baby").single(),
            migrated.strings("SELECT uid FROM pumping").single(),
        )

        // Existing rows are stamped as written "now" and are not deleted.
        migrated.longs("SELECT updatedAt FROM feeding").forEach {
            assertTrue("updatedAt is stamped at migration time", it >= before)
        }
        assertEquals(0, migrated.longs("SELECT COUNT(*) FROM feeding WHERE deletedAt IS NOT NULL").single())

        // And nothing was lost on the way.
        assertEquals(
            listOf(1700000100000L, 1700000200000L, 1700000300000L),
            migrated.longs("SELECT startTime FROM feeding ORDER BY id"),
        )
    }

    @Test
    fun migratesAnEmptyDatabase() {
        createV13Database().close()

        val migrated = openWithRoom().openHelper.writableDatabase

        assertEquals(0, migrated.longs("SELECT COUNT(*) FROM feeding").single())
        assertEquals(0, migrated.longs("SELECT COUNT(*) FROM tombstone").single())
    }

    @Test
    fun refusesTwoRowsWithTheSameIdentity() {
        createV13Database().use { v13 ->
            v13.execSQL("INSERT INTO `pumping` (time, amountMl, storage) VALUES (1, 100, 'FRIDGE')")
        }
        val migrated = openWithRoom().openHelper.writableDatabase
        val existing = migrated.strings("SELECT uid FROM pumping").single()

        val duplicate = runCatching {
            migrated.execSQL(
                "INSERT INTO `pumping` (time, amountMl, storage, uid, updatedAt) " +
                    "VALUES (2, 100, 'FRIDGE', ?, 0)",
                arrayOf<Any>(existing),
            )
        }

        assertTrue("the unique index on uid is what keeps a merge from doubling a row", duplicate.isFailure)
    }

    @Test
    fun keepsTheParentsOwnDataOutOfTheSync() {
        createV13Database().use { v13 ->
            v13.execSQL("INSERT INTO `wellbeing` (time, mood) VALUES (1700000000000, 4)")
        }

        val migrated = openWithRoom().openHelper.writableDatabase

        // `wellbeing` never leaves the device, so it gains no sync columns at all.
        val columns = migrated.query("PRAGMA table_info(`wellbeing`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        assertTrue("wellbeing must not become syncable", columns.none { it == "uid" })
        assertNull(columns.firstOrNull { it == "deletedAt" })
    }
}
