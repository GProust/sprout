package com.gproust.sprout.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Where a parent can chip in towards Sprout's development — and nothing beyond
 * that (BDR-11).
 *
 * Two properties of this file are load-bearing, and both are easy to undo by
 * accident:
 *
 * - **These are links, not payments.** Tapping one hands a URL to whatever
 *   browser the phone already has; Sprout opens nothing itself, which is why
 *   the manifest still declares no `INTERNET` permission and the app still
 *   cannot reach the network. Anything that fetches a page, a balance or a
 *   supporter count *in* the app would need that permission and take the one
 *   privacy claim a user can verify for themselves with it.
 * - **Nothing is ever given in return.** No feature unlocked, no limit lifted,
 *   no badge, no ad removed — there are no ads. A donation that buys something
 *   is an in-app purchase, and Google Play then requires it be sold through
 *   Play Billing rather than a link out.
 */
object SupportLinks {

    /** GitHub Sponsors — no platform fee, and the repo's own Sponsor button. */
    const val GITHUB_SPONSORS = "https://github.com/sponsors/gproust"

    /** Buy Me a Coffee — a one-off tip, for people who have no GitHub account. */
    const val BUY_ME_A_COFFEE = "https://buymeacoffee.com/gproust"

    /** Both destinations, in the order the Settings screen offers them. */
    val ALL = listOf(GITHUB_SPONSORS, BUY_ME_A_COFFEE)

    fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    /**
     * Opens [url] in a browser. Returns false — rather than crashing — when the
     * phone has nothing registered for `https`, which a stripped-down device or
     * a locked-down work profile genuinely can be.
     */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(viewIntent(url))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
