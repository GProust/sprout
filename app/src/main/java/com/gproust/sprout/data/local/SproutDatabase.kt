package com.gproust.sprout.data.local

import android.content.Context
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
        WellbeingEntity::class,
        ParentProfileEntity::class,
    ],
    version = 6,
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
    abstract fun wellbeingDao(): WellbeingDao
    abstract fun parentProfileDao(): ParentProfileDao

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

        fun getInstance(context: Context): SproutDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SproutDatabase::class.java,
                    "sprout.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { instance = it }
            }
    }
}
