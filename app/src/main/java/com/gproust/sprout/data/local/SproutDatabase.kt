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
        MotherHealthEntity::class,
        ParentProfileEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class SproutDatabase : RoomDatabase() {
    abstract fun babyDao(): BabyDao
    abstract fun feedingDao(): FeedingDao
    abstract fun sleepDao(): SleepDao
    abstract fun diaperDao(): DiaperDao
    abstract fun growthDao(): GrowthDao
    abstract fun motherHealthDao(): MotherHealthDao
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

        fun getInstance(context: Context): SproutDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SproutDatabase::class.java,
                    "sprout.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
