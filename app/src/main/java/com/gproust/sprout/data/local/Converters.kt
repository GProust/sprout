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
    fun stoolColorToString(value: StoolColor?): String? = value?.name

    @TypeConverter
    fun stringToStoolColor(value: String?): StoolColor? = value?.let { StoolColor.valueOf(it) }

    @TypeConverter
    fun bleedingToString(value: Bleeding?): String? = value?.name

    @TypeConverter
    fun stringToBleeding(value: String?): Bleeding? = value?.let { Bleeding.valueOf(it) }

    @TypeConverter
    fun breastStateToString(value: BreastState?): String? = value?.name

    @TypeConverter
    fun stringToBreastState(value: String?): BreastState? = value?.let { BreastState.valueOf(it) }

    @TypeConverter
    fun recoveryToString(value: Recovery?): String? = value?.name

    @TypeConverter
    fun stringToRecovery(value: String?): Recovery? = value?.let { Recovery.valueOf(it) }

    @TypeConverter
    fun deliveryTypeToString(value: DeliveryType?): String? = value?.name

    @TypeConverter
    fun stringToDeliveryType(value: String?): DeliveryType? = value?.let { DeliveryType.valueOf(it) }

    /** Stores a list of ints (e.g. reminder times in minutes) as a comma-separated string. */
    @TypeConverter
    fun intListToString(value: List<Int>?): String? = value?.joinToString(",")

    @TypeConverter
    fun stringToIntList(value: String?): List<Int>? =
        value?.split(",")?.filter { it.isNotBlank() }?.map { it.toInt() }

    /**
     * Stores breastfeeding segments as "SIDE,start,end" triples joined by ";".
     * Non-null (the column is NOT NULL, defaulting to an empty string).
     */
    @TypeConverter
    fun nursingSegmentsToString(value: List<NursingSegment>?): String =
        value.orEmpty().joinToString(";") { "${it.side.name},${it.startTime},${it.endTime}" }

    @TypeConverter
    fun stringToNursingSegments(value: String?): List<NursingSegment> =
        value?.split(";")?.filter { it.isNotBlank() }?.map { triple ->
            val (side, start, end) = triple.split(",")
            NursingSegment(BreastSide.valueOf(side), start.toLong(), end.toLong())
        } ?: emptyList()
}
