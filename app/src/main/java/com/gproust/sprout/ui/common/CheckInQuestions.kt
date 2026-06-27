package com.gproust.sprout.ui.common

/** A single question in a parent's daily wellbeing check-in. */
enum class CheckInQuestion { MOOD, HEALING, BLEEDING, BREASTS, NOTES }

/**
 * The ordered questions for a parent, based on their capabilities. Mood and
 * notes are always asked; the postpartum questions (healing, bleeding) appear
 * only if they gave birth, and the breast question only if they're
 * breastfeeding — so the same flow fits every kind of parent.
 */
fun checkInQuestions(gaveBirth: Boolean, breastfeeding: Boolean): List<CheckInQuestion> =
    buildList {
        add(CheckInQuestion.MOOD)
        if (gaveBirth) {
            add(CheckInQuestion.HEALING)
            add(CheckInQuestion.BLEEDING)
        }
        if (breastfeeding) {
            add(CheckInQuestion.BREASTS)
        }
        add(CheckInQuestion.NOTES)
    }
