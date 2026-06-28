package com.gproust.sprout.ui.common

import com.gproust.sprout.data.local.Bleeding
import com.gproust.sprout.data.local.BreastState
import com.gproust.sprout.data.local.DeliveryType
import com.gproust.sprout.data.local.Recovery

/** Emoji face for a 1–5 mood rating. */
fun moodEmoji(mood: Int): String = when (mood) {
    1 -> "😢"
    2 -> "🙁"
    3 -> "😐"
    4 -> "🙂"
    else -> "😄"
}

fun Bleeding.label(): String = when (this) {
    Bleeding.NONE -> "None"
    Bleeding.LIGHT -> "Light"
    Bleeding.MODERATE -> "Moderate"
    Bleeding.HEAVY -> "Heavy"
}

fun BreastState.label(): String = when (this) {
    BreastState.NORMAL -> "Normal"
    BreastState.TENDER -> "Tender"
    BreastState.ENGORGED -> "Engorged"
    BreastState.PAINFUL -> "Painful"
}

fun Recovery.label(): String = when (this) {
    Recovery.GREAT -> "Great"
    Recovery.GOOD -> "Good"
    Recovery.SORE -> "Sore"
    Recovery.PAINFUL -> "Painful"
}

fun DeliveryType.label(): String = when (this) {
    DeliveryType.VAGINAL -> "Vaginal"
    DeliveryType.CESAREAN -> "C-section"
}

/** The check-in's healing question, worded for the delivery type. */
fun healingQuestion(deliveryType: DeliveryType?): String = when (deliveryType) {
    DeliveryType.CESAREAN -> "How is your incision healing?"
    DeliveryType.VAGINAL -> "How is your perineal healing?"
    null -> "How is your healing coming along?"
}

/** Short field label for the healing question, worded for the delivery type. */
fun healingFieldLabel(deliveryType: DeliveryType?): String = when (deliveryType) {
    DeliveryType.CESAREAN -> "Incision healing"
    DeliveryType.VAGINAL -> "Perineal healing"
    null -> "Recovery / healing"
}
