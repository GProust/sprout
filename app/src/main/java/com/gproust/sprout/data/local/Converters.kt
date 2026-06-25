package com.gproust.sprout.data.local

import androidx.room.TypeConverter

/** Room type converters that store enums as their name strings. */
class Converters {
    @TypeConverter
    fun feedTypeToString(value: FeedType?): String? = value?.name

    @TypeConverter
    fun stringToFeedType(value: String?): FeedType? = value?.let { FeedType.valueOf(it) }

    @TypeConverter
    fun breastSideToString(value: BreastSide?): String? = value?.name

    @TypeConverter
    fun stringToBreastSide(value: String?): BreastSide? = value?.let { BreastSide.valueOf(it) }

    @TypeConverter
    fun diaperTypeToString(value: DiaperType?): String? = value?.name

    @TypeConverter
    fun stringToDiaperType(value: String?): DiaperType? = value?.let { DiaperType.valueOf(it) }

    @TypeConverter
    fun bleedingToString(value: Bleeding?): String? = value?.name

    @TypeConverter
    fun stringToBleeding(value: String?): Bleeding? = value?.let { Bleeding.valueOf(it) }

    @TypeConverter
    fun breastStateToString(value: BreastState?): String? = value?.name

    @TypeConverter
    fun stringToBreastState(value: String?): BreastState? = value?.let { BreastState.valueOf(it) }
}
