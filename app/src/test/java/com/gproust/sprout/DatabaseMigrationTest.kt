package com.gproust.sprout

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.local.SproutDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Upgrade tests for [SproutDatabase].
 *
 * Sprout is offline-only: a parent's logs exist on their phone and nowhere
 * else, so a migration that drops a table or leaves the schema disagreeing with
 * the entities destroys data that cannot be recovered. Room only discovers that
 * when the database is opened — on a user's device, after the update.
 *
 * These tests move that discovery into CI by opening a database built the way
 * an older release left it and running the real [SproutDatabase.MIGRATIONS]
 * chain over it. Room validates the migrated schema against the entities as
 * part of opening, so a mismatch fails here rather than in someone's kitchen at
 * 3 a.m.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class DatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"
    private lateinit var dbFile: File
    private var db: SproutDatabase? = null

    @Before
    fun setUp() {
        dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        deleteDatabaseFiles()
    }

    @After
    fun tearDown() {
        db?.close()
        deleteDatabaseFiles()
    }

    private fun deleteDatabaseFiles() {
        listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { it.delete() }
    }

    /** Opens the database through the shipped migration chain. */
    private fun openWithMigrations(): SproutDatabase =
        Room.databaseBuilder(context, SproutDatabase::class.java, dbName)
            .addMigrations(*SproutDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    private fun SproutDatabase.countOf(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    @Test
    fun `v10 database upgrades to the current schema`() {
        createV10Database()

        // Opening runs 10 -> 11 -> 12 and then validates the result against the
        // entities. Any mismatch (wrong type, missing default, absent index)
        // throws IllegalStateException here.
        val database = openWithMigrations()

        // Force the open to actually happen — Room is lazy until first use.
        assertTrue(database.openHelper.writableDatabase.isOpen)
    }

    @Test
    fun `upgrading from v10 keeps every logged entry`() {
        createV10Database()
        val database = openWithMigrations()

        assertEquals("baby rows", 2, database.countOf("baby"))
        assertEquals("feeding rows", 2, database.countOf("feeding"))
        assertEquals("sleep rows", 1, database.countOf("sleep"))
        assertEquals("diaper rows", 1, database.countOf("diaper"))
        assertEquals("growth rows", 1, database.countOf("growth"))
        assertEquals("treatment rows", 1, database.countOf("treatment"))
        assertEquals("wellbeing rows", 1, database.countOf("wellbeing"))
        assertEquals("parent_profile rows", 1, database.countOf("parent_profile"))
    }

    @Test
    fun `upgrading from v10 preserves the actual values, not just the row count`() {
        createV10Database()
        val database = openWithMigrations()

        database.query(
            "SELECT name, birthDate, archived FROM `baby` WHERE id = 1",
            null,
        ).use { c ->
            assertTrue("baby 1 missing", c.moveToFirst())
            assertEquals("Léa", c.getString(0))
            assertEquals(1_700_000_000_000L, c.getLong(1))
            assertEquals(0, c.getInt(2))
        }

        database.query(
            "SELECT type, side, leftDurationMs, rightDurationMs, segments, notes FROM `feeding` WHERE id = 1",
            null,
        ).use { c ->
            assertTrue("feeding 1 missing", c.moveToFirst())
            assertEquals("BREAST", c.getString(0))
            assertEquals("LEFT", c.getString(1))
            assertEquals(540_000L, c.getLong(2))
            assertEquals(240_000L, c.getLong(3))
            assertEquals("LEFT,1700000000000,1700000540000", c.getString(4))
            assertEquals("a good feed", c.getString(5))
        }

        // The v8 -> v9 diaper rework already ran before v10; check the migrated
        // checklist columns still hold what that migration produced.
        database.query("SELECT wet, dirty, stoolColor FROM `diaper` WHERE id = 1", null).use { c ->
            assertTrue("diaper 1 missing", c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(1, c.getInt(1))
            assertEquals("YELLOW", c.getString(2))
        }
    }

    @Test
    fun `the check-in opt-outs added after v10 default to on`() {
        createV10Database()
        val database = openWithMigrations()

        // A parent who upgrades must keep being offered the check-in exactly as
        // before; the v10 -> v11 and v11 -> v12 columns all default to 1. If a
        // default were wrong or missing, an existing profile would silently
        // lose its wellbeing tracking on update.
        database.query(
            "SELECT askHealing, askBleeding, askBreasts, trackWellbeing, name, activeBabyId FROM `parent_profile` WHERE id = 1",
            null,
        ).use { c ->
            assertTrue("parent_profile row missing", c.moveToFirst())
            assertEquals("askHealing", 1, c.getInt(0))
            assertEquals("askBleeding", 1, c.getInt(1))
            assertEquals("askBreasts", 1, c.getInt(2))
            assertEquals("trackWellbeing", 1, c.getInt(3))
            assertEquals("Marise", c.getString(4))
            assertEquals(1L, c.getLong(5))
        }
    }

    @Test
    fun `a fresh install creates a schema the entities agree with`() {
        // No pre-existing file: Room creates v12 directly. This catches an
        // entity change made without a matching migration, which would leave
        // fresh installs and upgraded installs on different schemas.
        val database = openWithMigrations()

        assertEquals(0, database.countOf("baby"))
        assertEquals(12, database.openHelper.writableDatabase.version)
    }

    /**
     * Builds the database exactly as version 1.3.0 (versionCode 2) left it —
     * schema version 10, the last version published before the check-in
     * settings were added — and seeds one row per table.
     */
    private fun createV10Database() {
        val v10 = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        v10.execSQL(
            "CREATE TABLE IF NOT EXISTS `baby` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`birthDate` INTEGER NOT NULL, `archived` INTEGER NOT NULL DEFAULT 0, " +
                "`feedingReminderEnabled` INTEGER, `feedingReminderIntervalMinutes` INTEGER)",
        )
        v10.execSQL(
            "CREATE TABLE IF NOT EXISTS `feeding` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`babyId` INTEGER NOT NULL DEFAULT 1, `type` TEXT NOT NULL, `side` TEXT, " +
                "`amountMl` INTEGER, `startTime` INTEGER NOT NULL, `endTime` INTEGER, " +
                "`leftDurationMs` INTEGER, `rightDurationMs` INTEGER, " +
                "`segments` TEXT NOT NULL DEFAULT '', `notes` TEXT)",
        )
        v10.execSQL("CREATE INDEX IF NOT EXISTS `index_feeding_babyId` ON `feeding` (`babyId`)")
        v10.execSQL(
            "CREATE TABLE IF NOT EXISTS `sleep` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`babyId` INTEGER NOT NULL DEFAULT 1, `startTime` INTEGER NOT NULL, " +
                "`endTime` INTEGER, `notes` TEXT)",
        )
        v10.execSQL("CREATE INDEX IF NOT EXISTS `index_sleep_babyId` ON `sleep` (`babyId`)")
        v10.execSQL(
            "CREATE TABLE IF NOT EXISTS `diaper` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`babyId` INTEGER NOT NULL DEFAULT 1, `time` INTEGER NOT NULL, " +
                "`wet` INTEGER NOT NULL DEFAULT 0, `dirty` INTEGER NOT NULL DEFAULT 0, " +
                "`stoolColor` TEXT, `notes` TEXT)",
        )
        v10.execSQL("CREATE INDEX IF NOT EXISTS `index_diaper_babyId` ON `diaper` (`babyId`)")
        v10.execSQL(
            "CREATE TABLE IF NOT EXISTS `growth` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`babyId` INTEGER NOT NULL DEFAULT 1, `time` INTEGER NOT NULL, " +
                "`weightGrams` INTEGER, `heightMm` INTEGER, `headMm` INTEGER, `notes` TEXT)",
        )
        v10.execSQL("CREATE INDEX IF NOT EXISTS `index_growth_babyId` ON `growth` (`babyId`)")
        v10.execSQL(
            "CREATE TABLE IF NOT EXISTS `treatment` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`babyId` INTEGER NOT NULL, `name` TEXT NOT NULL, `dose` TEXT, " +
                "`intervalDays` INTEGER NOT NULL, `timesOfDay` TEXT NOT NULL, " +
                "`startDate` INTEGER NOT NULL, `endDate` INTEGER, " +
                "`remindersEnabled` INTEGER NOT NULL, `active` INTEGER NOT NULL, " +
                "`notes` TEXT)",
        )
        v10.execSQL("CREATE INDEX IF NOT EXISTS `index_treatment_babyId` ON `treatment` (`babyId`)")
        v10.execSQL(
            "CREATE TABLE IF NOT EXISTS `wellbeing` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`time` INTEGER NOT NULL, `mood` INTEGER NOT NULL, " +
                "`bleeding` TEXT, `recovery` TEXT, `breast` TEXT, `notes` TEXT)",
        )
        v10.execSQL(
            "CREATE TABLE IF NOT EXISTS `parent_profile` (" +
                "`id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`gaveBirth` INTEGER NOT NULL, `breastfeeding` INTEGER NOT NULL, " +
                "`deliveryType` TEXT, `lastCheckIn` INTEGER, `activeBabyId` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )

        val birth = 1_700_000_000_000L
        v10.execSQL("INSERT INTO `baby` (id, name, birthDate, archived) VALUES (1, 'Léa', $birth, 0)")
        v10.execSQL("INSERT INTO `baby` (id, name, birthDate, archived) VALUES (2, 'Noah', $birth, 1)")
        v10.execSQL(
            "INSERT INTO `feeding` (id, babyId, type, side, startTime, endTime, leftDurationMs, rightDurationMs, segments, notes) " +
                "VALUES (1, 1, 'BREAST', 'LEFT', $birth, ${birth + 540_000L}, 540000, 240000, " +
                "'LEFT,$birth,${birth + 540_000L}', 'a good feed')",
        )
        v10.execSQL(
            "INSERT INTO `feeding` (id, babyId, type, amountMl, startTime, segments) " +
                "VALUES (2, 1, 'BOTTLE', 90, $birth, '')",
        )
        v10.execSQL("INSERT INTO `sleep` (id, babyId, startTime, endTime) VALUES (1, 1, $birth, ${birth + 3_600_000L})")
        v10.execSQL("INSERT INTO `diaper` (id, babyId, time, wet, dirty, stoolColor) VALUES (1, 1, $birth, 1, 1, 'YELLOW')")
        v10.execSQL("INSERT INTO `growth` (id, babyId, time, weightGrams, heightMm, headMm) VALUES (1, 1, $birth, 3200, 500, 350)")
        v10.execSQL(
            "INSERT INTO `treatment` (id, babyId, name, dose, intervalDays, timesOfDay, startDate, remindersEnabled, active) " +
                "VALUES (1, 1, 'Vitamin D', '1 drop', 1, '540', $birth, 1, 1)",
        )
        v10.execSQL("INSERT INTO `wellbeing` (id, time, mood, bleeding, recovery, breast) VALUES (1, $birth, 4, 'LIGHT', 'GOOD', 'NORMAL')")
        v10.execSQL(
            "INSERT INTO `parent_profile` (id, name, gaveBirth, breastfeeding, deliveryType, activeBabyId) " +
                "VALUES (1, 'Marise', 1, 1, 'CESAREAN', 1)",
        )

        v10.version = 10
        v10.close()
    }
}
