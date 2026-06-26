package com.gproust.sprout.ui.common

import com.gproust.sprout.data.local.Bleeding
import com.gproust.sprout.data.local.BreastState
import com.gproust.sprout.data.local.ParentRole
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

fun ParentRole.label(): String = when (this) {
    ParentRole.MOTHER -> "I'm the mother"
    ParentRole.CO_PARENT -> "I'm the co-parent"
}
