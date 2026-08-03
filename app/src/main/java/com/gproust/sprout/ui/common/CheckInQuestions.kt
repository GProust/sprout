package com.gproust.sprout.ui.common

/** A single question in a parent's daily wellbeing check-in. */
enum class CheckInQuestion { MOOD, HEALING, BLEEDING, BREASTS, NOTES }

/**
 * The ordered questions for a parent, based on their capabilities. Mood and
 * notes are always asked; the postpartum questions (healing, bleeding) appear
 * only if they gave birth, and the breast question only if they're
 * breastfeeding — so the same flow fits every kind of parent. Each body
 * question can also be opted out of (its `ask*` flag false) once it no longer
 * needs a daily look.
 */
fun checkInQuestions(
    gaveBirth: Boolean,
    breastfeeding: Boolean,
    askHealing: Boolean = true,
    askBleeding: Boolean = true,
    askBreasts: Boolean = true,
): List<CheckInQuestion> =
    buildList {
        add(CheckInQuestion.MOOD)
        if (gaveBirth) {
            if (askHealing) add(CheckInQuestion.HEALING)
            if (askBleeding) add(CheckInQuestion.BLEEDING)
        }
        if (breastfeeding && askBreasts) {
            add(CheckInQuestion.BREASTS)
        }
        add(CheckInQuestion.NOTES)
    }

/** Whether a question offers "don't ask me this anymore" (and a Settings toggle). */
fun CheckInQuestion.isOptional(): Boolean = when (this) {
    CheckInQuestion.HEALING, CheckInQuestion.BLEEDING, CheckInQuestion.BREASTS -> true
    CheckInQuestion.MOOD, CheckInQuestion.NOTES -> false
}
