package com.gproust.sprout.data.local

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BabyEntity::class,
        FeedingEntity::class,
        SleepEntity::class,
        DiaperEntity::class,
        GrowthEntity::class,
        TreatmentEntity::class,
        PumpingEntity::class,
        WellbeingEntity::class,
        ParentProfileEntity::class,
        TombstoneEntity::class,
    ],
    version = 14,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class SproutDatabase : RoomDatabase() {
    abstract fun babyDao(): BabyDao
    abstract fun feedingDao(): FeedingDao
    abstract fun sleepDao(): SleepDao
    abstract fun diaperDao(): DiaperDao
    abstract fun growthDao(): GrowthDao
    abstract fun treatmentDao(): TreatmentDao
    abstract fun pumpingDao(): PumpingDao
    abstract fun wellbeingDao(): WellbeingDao
    abstract fun parentProfileDao(): ParentProfileDao
    abstract fun tombstoneDao(): TombstoneDao

    companion object {
        @Volatile
        private var instance: SproutDatabase? = null

        /** v1 -> v2: add the parent profile table and the recovery column. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `parent_profile` (" +
                        "`id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, `lastCheckIn` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("ALTER TABLE `mother_health` ADD COLUMN `recovery` TEXT")
            }
        }

        /** v2 -> v3: add the co-parent wellbeing table. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `co_parent_health` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`time` INTEGER NOT NULL, `mood` INTEGER NOT NULL, " +
                        "`notes` TEXT)",
                )
            }
        }

        /**
         * v3 -> v4: replace the mother/co-parent split with a capability model.
         * Merge both health tables into a single `wellbeing` table, and replace
         * the parent's `role` with `gaveBirth` / `breastfeeding` flags.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wellbeing` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`time` INTEGER NOT NULL, `mood` INTEGER NOT NULL, " +
                        "`bleeding` TEXT, `recovery` TEXT, `breast` TEXT, `notes` TEXT)",
                )
                db.execSQL(
                    "INSERT INTO `wellbeing` (time, mood, bleeding, recovery, breast, notes) " +
                        "SELECT time, mood, bleeding, recovery, breast, notes FROM `mother_health`",
                )
                db.execSQL(
                    "INSERT INTO `wellbeing` (time, mood, notes) " +
                        "SELECT time, mood, notes FROM `co_parent_health`",
                )
                db.execSQL("DROP TABLE `mother_health`")
                db.execSQL("DROP TABLE `co_parent_health`")

                // Recreate parent_profile: role -> gaveBirth / breastfeeding.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `parent_profile_new` (" +
                        "`id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                        "`gaveBirth` INTEGER NOT NULL, `breastfeeding` INTEGER NOT NULL, " +
                        "`deliveryType` TEXT, `lastCheckIn` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "INSERT INTO `parent_profile_new` (id, name, gaveBirth, breastfeeding, lastCheckIn) " +
                        "SELECT id, name, " +
                        "CASE WHEN role = 'MOTHER' THEN 1 ELSE 0 END, " +
                        "CASE WHEN role = 'MOTHER' THEN 1 ELSE 0 END, " +
                        "lastCheckIn FROM `parent_profile`",
                )
                db.execSQL("DROP TABLE `parent_profile`")
                db.execSQL("ALTER TABLE `parent_profile_new` RENAME TO `parent_profile`")
            }
        }

        /**
         * v4 -> v5: support several babies. Tag every existing log with the one
         * current baby (id = 1), add an `archived` flag for "stop tracking", and
         * remember which baby is selected on the parent profile.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `baby` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE `feeding` ADD COLUMN `babyId` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `sleep` ADD COLUMN `babyId` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `diaper` ADD COLUMN `babyId` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `growth` ADD COLUMN `babyId` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feeding_babyId` ON `feeding` (`babyId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sleep_babyId` ON `sleep` (`babyId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diaper_babyId` ON `diaper` (`babyId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_growth_babyId` ON `growth` (`babyId`)")

                db.execSQL("ALTER TABLE `parent_profile` ADD COLUMN `activeBabyId` INTEGER")
                // Point the existing profile at the one baby it already had.
                db.execSQL("UPDATE `parent_profile` SET `activeBabyId` = 1 WHERE EXISTS (SELECT 1 FROM `baby` WHERE id = 1)")
            }
        }

        /** v5 -> v6: add the per-baby `treatment` table (medications + reminders). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `treatment` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`babyId` INTEGER NOT NULL, `name` TEXT NOT NULL, `dose` TEXT, " +
                        "`intervalDays` INTEGER NOT NULL, `timesOfDay` TEXT NOT NULL, " +
                        "`startDate` INTEGER NOT NULL, `endDate` INTEGER, " +
                        "`remindersEnabled` INTEGER NOT NULL, `active` INTEGER NOT NULL, " +
                        "`notes` TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_treatment_babyId` ON `treatment` (`babyId`)")
            }
        }

        /** v6 -> v7: record per-breast durations for breastfeeding sessions. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `feeding` ADD COLUMN `leftDurationMs` INTEGER")
                db.execSQL("ALTER TABLE `feeding` ADD COLUMN `rightDurationMs` INTEGER")
            }
        }

        /**
         * v7 -> v8: add the per-baby feeding-reminder override. Both columns are
         * nullable and default to NULL, so every existing baby keeps following the
         * device-global feeding-reminder setting until an override is set.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `baby` ADD COLUMN `feedingReminderEnabled` INTEGER")
                db.execSQL("ALTER TABLE `baby` ADD COLUMN `feedingReminderIntervalMinutes` INTEGER")
            }
        }

        /**
         * v8 -> v9: rework a diaper change from a single type (WET/DIRTY/MIXED)
         * into a checklist — `wet` (urines) and `dirty` (selles) — plus an
         * optional `stoolColor`. Existing rows are backfilled from their old
         * type, and the now-unused `type` column is dropped by recreating the
         * table.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `diaper_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`babyId` INTEGER NOT NULL DEFAULT 1, `time` INTEGER NOT NULL, " +
                        "`wet` INTEGER NOT NULL DEFAULT 0, `dirty` INTEGER NOT NULL DEFAULT 0, " +
                        "`stoolColor` TEXT, `notes` TEXT)",
                )
                db.execSQL(
                    "INSERT INTO `diaper_new` (id, babyId, time, wet, dirty, stoolColor, notes) " +
                        "SELECT id, babyId, time, " +
                        "CASE WHEN type IN ('WET', 'MIXED') THEN 1 ELSE 0 END, " +
                        "CASE WHEN type IN ('DIRTY', 'MIXED') THEN 1 ELSE 0 END, " +
                        "NULL, notes FROM `diaper`",
                )
                db.execSQL("DROP TABLE `diaper`")
                db.execSQL("ALTER TABLE `diaper_new` RENAME TO `diaper`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diaper_babyId` ON `diaper` (`babyId`)")
            }
        }

        /**
         * v9 -> v10: record a breastfeed's individual back-and-forth segments
         * (each a "SIDE,start,end" triple). Existing rows default to an empty
         * list and keep falling back to their per-side totals for display.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `feeding` ADD COLUMN `segments` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v10 -> v11: let a parent opt out of each of the check-in's body
         * questions (healing, bleeding, breast comfort). All default to 1
         * (keep asking) for every existing profile.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `parent_profile` ADD COLUMN `askHealing` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `parent_profile` ADD COLUMN `askBleeding` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `parent_profile` ADD COLUMN `askBreasts` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v11 -> v12: let a parent stop tracking their own wellbeing entirely.
         * Defaults to 1 (keep offering the check-in) for every existing profile.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `parent_profile` ADD COLUMN `trackWellbeing` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v12 -> v13: add the `pumping` table (expressed milk: when, how much,
         * and where it is stored). Parent-scoped like `wellbeing`, so it has no
         * `babyId` and no index to go with one.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pumping` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`time` INTEGER NOT NULL, `amountMl` INTEGER NOT NULL, " +
                        "`side` TEXT, `storage` TEXT NOT NULL, `notes` TEXT)",
                )
            }
        }

        /**
         * The tables partner sync merges, and so the ones that gain the sync
         * columns in [MIGRATION_13_14]. `wellbeing` and `parent_profile` are
         * absent on purpose — they never leave the device (ADR-0007).
         */
        private val SYNCED_TABLES = listOf(
            "baby", "feeding", "sleep", "diaper", "growth", "treatment", "pumping",
        )

        /**
         * A UUIDv4 as a SQL expression, re-evaluated for every row of an UPDATE
         * (`randomblob` is non-deterministic, so SQLite cannot hoist it out).
         *
         * The version nibble is fixed at `4` and the variant one drawn from
         * `89ab`, as the format requires. The variant uses `random() & 3` rather
         * than `abs(random()) % 4` because `abs()` of the smallest possible
         * integer overflows and would abort the migration — vanishingly
         * unlikely, but this runs once on data the user cannot re-enter.
         */
        private const val UUID_V4_SQL =
            "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
                "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "substr('89ab', (random() & 3) + 1, 1) || " +
                "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "lower(hex(randomblob(6)))"

        /**
         * v13 -> v14: make the data mergeable, for partner sync (ADR-0007).
         *
         * Every synced table gains `uid` (who this row is, across two phones),
         * `updatedAt` (which of two edits wins) and `deletedAt` (a tombstone, so
         * a deletion can be told to the other phone instead of being undone by
         * it). Existing rows are backfilled with a fresh UUID each — the unique
         * index is created only afterwards, since every row starts at the `''`
         * default and would collide.
         *
         * `updatedAt` is stamped with the migration time rather than each row's
         * own timestamp: the true last-write time is unknowable, and "now" is a
         * safe upper bound while no partner exists yet to disagree with it.
         *
         * Nothing here changes what the app shows.
         *
         * Visible to tests because there is no local Android toolchain: CI is
         * the only place this is ever proven to work (ADR-0006).
         */
        @VisibleForTesting
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                for (table in SYNCED_TABLES) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `deletedAt` INTEGER")
                    db.execSQL(
                        "UPDATE `$table` SET `uid` = $UUID_V4_SQL, `updatedAt` = ?",
                        arrayOf<Any>(now),
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_uid` ON `$table` (`uid`)",
                    )
                }
                // Carries the deletion of rows that were erased outright, which
                // have no `deletedAt` of their own left to carry it.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tombstone` (" +
                        "`uid` TEXT NOT NULL, `entity` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
                )
            }
        }

        fun getInstance(context: Context): SproutDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SproutDatabase::class.java,
                    "sprout.db",
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                    MIGRATION_12_13, MIGRATION_13_14,
                ).build().also { instance = it }
            }
    }
}
