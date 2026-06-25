package com.gproust.sprout.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        BabyEntity::class,
        FeedingEntity::class,
        SleepEntity::class,
        DiaperEntity::class,
        GrowthEntity::class,
        MotherHealthEntity::class,
    ],
    version = 1,
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

    companion object {
        @Volatile
        private var instance: SproutDatabase? = null

        fun getInstance(context: Context): SproutDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SproutDatabase::class.java,
                    "sprout.db",
                ).build().also { instance = it }
            }
    }
}
