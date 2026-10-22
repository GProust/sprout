package com.gproust.sprout.ui.tour

import android.content.Context
import androidx.core.content.edit
import com.gproust.sprout.data.TourMark
import com.gproust.sprout.data.tourMark

/**
 * Whether this phone owes the guided tour, or has had it (BDR-27).
 *
 * In the ordinary settings, not `device.xml`: having been shown round the app is
 * about a person, not about this handset's identity, so a restored phone carries
 * it like any other setting ([ADR-0011][1]). A restored phone also skips
 * onboarding — its profile came with it — so it never owes the tour either way.
 * Not synced: the other parent has been shown nothing on this phone.
 *
 * [1]: docs/adr/0011-what-survives-a-new-phone.md
 */
object TourSettings {
    private const val PREFS = "settings"
    private const val KEY = "tour"

    /** What is stored, or null when nothing is: a phone that never owed the tour. */
    fun mark(context: Context): TourMark? = tourMark(prefs(context).getString(KEY, null))

    /**
     * Onboarding has just finished, so the dashboard owes the tour. Written
     * before the profile is saved, because saving it is what opens the
     * dashboard.
     */
    fun markPending(context: Context) = store(context, TourMark.PENDING)

    /** Writes [mark], or clears the key when it is null. */
    fun store(context: Context, mark: TourMark?) = prefs(context).edit {
        if (mark == null) remove(KEY) else putString(KEY, mark.stored)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
