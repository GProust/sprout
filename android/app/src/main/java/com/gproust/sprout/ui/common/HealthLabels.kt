package com.gproust.sprout.ui.common

import android.content.Context
import com.gproust.sprout.R
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

fun Bleeding.label(context: Context): String = context.getString(
    when (this) {
        Bleeding.NONE -> R.string.bleeding_none
        Bleeding.LIGHT -> R.string.bleeding_light
        Bleeding.MODERATE -> R.string.bleeding_moderate
        Bleeding.HEAVY -> R.string.bleeding_heavy
    },
)

fun BreastState.label(context: Context): String = context.getString(
    when (this) {
        BreastState.NORMAL -> R.string.breast_normal
        BreastState.TENDER -> R.string.breast_tender
        BreastState.ENGORGED -> R.string.breast_engorged
        BreastState.PAINFUL -> R.string.breast_painful
    },
)

fun Recovery.label(context: Context): String = context.getString(
    when (this) {
        Recovery.GREAT -> R.string.recovery_great
        Recovery.GOOD -> R.string.recovery_good
        Recovery.SORE -> R.string.recovery_sore
        Recovery.PAINFUL -> R.string.recovery_painful
    },
)

fun DeliveryType.label(context: Context): String = context.getString(
    when (this) {
        DeliveryType.VAGINAL -> R.string.delivery_vaginal
        DeliveryType.CESAREAN -> R.string.delivery_cesarean
    },
)

/** The check-in's healing question, worded for the delivery type. */
fun healingQuestion(context: Context, deliveryType: DeliveryType?): String = context.getString(
    when (deliveryType) {
        DeliveryType.CESAREAN -> R.string.healing_q_cesarean
        DeliveryType.VAGINAL -> R.string.healing_q_vaginal
        null -> R.string.healing_q_generic
    },
)

/** Short field label for the healing question, worded for the delivery type. */
fun healingFieldLabel(context: Context, deliveryType: DeliveryType?): String = context.getString(
    when (deliveryType) {
        DeliveryType.CESAREAN -> R.string.healing_label_cesarean
        DeliveryType.VAGINAL -> R.string.healing_label_vaginal
        null -> R.string.healing_label_generic
    },
)
