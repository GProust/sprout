package com.gproust.sprout

import com.gproust.sprout.data.ANNOUNCEMENT_IDS
import com.gproust.sprout.data.announcementsToShow
import com.gproust.sprout.ui.whatsnew.announcementContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Who sees the "what's new" card, and with what in it (BDR-22).
 *
 * The same cases are checked on iOS (`WhatsNewTests.swift`), because the two
 * phones in one household should not disagree about whether there is news.
 */
class WhatsNewTest {

    /** A phone updating from before announcements existed: the newest only, not the history. */
    @Test
    fun nothingStoredOffersTheNewestOnly() {
        assertEquals(listOf(3), announcementsToShow(listOf(1, 2, 3), lastSeen = null))
    }

    @Test
    fun theOnesNotYetSeenAreOfferedNewestFirst() {
        assertEquals(listOf(3, 2), announcementsToShow(listOf(1, 2, 3), lastSeen = 1))
    }

    /** Read, put away, or a fresh install that finished onboarding. */
    @Test
    fun everythingSeenOffersNothing() {
        assertEquals(emptyList<Int>(), announcementsToShow(listOf(1, 2, 3), lastSeen = 3))
    }

    /** A backup from a newer version restored onto this one. */
    @Test
    fun aPhoneThatHasSeenMoreThanThisVersionCarriesOffersNothing() {
        assertEquals(emptyList<Int>(), announcementsToShow(listOf(1, 2), lastSeen = 5))
    }

    @Test
    fun noAnnouncementsOffersNothing() {
        assertEquals(emptyList<Int>(), announcementsToShow(emptyList(), lastSeen = null))
    }

    /** Ids only ever grow: a reused or out-of-order one would read as already seen. */
    @Test
    fun idsAreDistinctAndAppendedInOrder() {
        assertEquals(ANNOUNCEMENT_IDS.distinct().sorted(), ANNOUNCEMENT_IDS)
    }

    @Test
    fun everyAnnouncementHasWords() {
        ANNOUNCEMENT_IDS.forEach { id ->
            assertNotNull("announcement $id has no text", announcementContent(id))
        }
    }
}
