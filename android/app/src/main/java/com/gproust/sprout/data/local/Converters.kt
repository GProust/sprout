package com.gproust.sprout.data.local

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

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
    fun milkStorageToString(value: MilkStorage?): String? = value?.name

    @TypeConverter
    fun stringToMilkStorage(value: String?): MilkStorage? = value?.let { MilkStorage.valueOf(it) }

    @TypeConverter
    fun stoolColorToString(value: StoolColor?): String? = value?.name

    @TypeConverter
    fun stringToStoolColor(value: String?): StoolColor? = value?.let { StoolColor.valueOf(it) }

    @TypeConverter
    fun sleepPositionToString(value: SleepPosition?): String? = value?.name

    @TypeConverter
    fun stringToSleepPosition(value: String?): SleepPosition? = value?.let { SleepPosition.valueOf(it) }

    @TypeConverter
    fun sleepPlaceToString(value: SleepPlace?): String? = value?.name

    @TypeConverter
    fun stringToSleepPlace(value: String?): SleepPlace? = value?.let { SleepPlace.valueOf(it) }

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

    /**
     * Stores a meal's foods as a JSON array — `[{"name":"Carrot","portion":
     * "TASTE"}]` — and an empty list as an empty string, so every row from
     * before the column existed already reads as a meal that named nothing.
     *
     * JSON rather than a packed string like the segments: a food is the
     * parent's own words, and any separator a packing chose is a character
     * somebody will type. The same shape is what travels (spec §7).
     */
    @TypeConverter
    fun mealFoodsToString(value: List<MealFood>?): String =
        if (value.isNullOrEmpty()) "" else mealFoodsToJson(value).toString()

    @TypeConverter
    fun stringToMealFoods(value: String?): List<MealFood> =
        if (value.isNullOrBlank()) emptyList() else mealFoodsFromJson(JSONArray(value))

    companion object {
        fun mealFoodsToJson(foods: List<MealFood>): JSONArray = JSONArray().apply {
            foods.forEach { food ->
                put(
                    JSONObject().apply {
                        put("name", food.name)
                        put("portion", food.portion.name)
                        // Absent rather than null: "not noted" is no key at all,
                        // and a food the household typed in has no list id.
                        food.reception?.let { put("reception", it.name) }
                        food.ref?.let { put("ref", it) }
                    },
                )
            }
        }

        fun mealFoodsFromJson(array: JSONArray): List<MealFood> = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MealFood(
                name = o.getString("name"),
                portion = FoodPortion.valueOf(o.getString("portion")),
                reception = if (o.isNull("reception")) null else FoodReception.valueOf(o.getString("reception")),
                ref = if (o.isNull("ref")) null else o.getString("ref"),
            )
        }
    }
}
