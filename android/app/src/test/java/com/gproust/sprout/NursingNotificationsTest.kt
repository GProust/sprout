package com.gproust.sprout

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.SproutDatabase
import com.gproust.sprout.notifications.NursingNotifications
import com.gproust.sprout.ui.feeding.NursingControl
import com.gproust.sprout.ui.feeding.NursingSession
import com.gproust.sprout.ui.feeding.NursingSessionStore
import com.gproust.sprout.ui.settings.NursingNotificationSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A running breastfeed in the notifications (BDR-24): one per feed, the timer's
 * own buttons, nothing about the baby on a locked screen, and a tap there that
 * is the same act as the same tap on the timer — a second *Pause* or a second
 * *Stop & save* changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class NursingNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var db: SproutDatabase
    private lateinit var repository: SproutRepository
    private lateinit var leaUid: String
    private lateinit var noahUid: String
    private val start = System.currentTimeMillis() - 10 * 60_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SproutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SproutRepository(db)
        runBlocking {
            repository.saveParentProfile(ParentProfileEntity(name = "Alex"))
            val lea = repository.addBaby("Léa", birthDate = 1_700_000_000_000)
            val noah = repository.addBaby("Noah", birthDate = 1_700_000_000_000)
            leaUid = db.babyDao().uidById(lea)!!
            noahUid = db.babyDao().uidById(noah)!!
        }
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NursingSessionStore.clear(context)
        NursingNotificationSettings.setEnabled(context, true)
    }

    @After
    fun tearDown() {
        NursingSessionStore.clear(context)
        NursingNotificationSettings.setEnabled(context, false)
        manager.cancelAll()
        db.close()
    }

    private fun feed(babyUid: String, side: BreastSide, at: Long = start) =
        NursingSession(sessionStart = at, currentSide = side, segmentStart = at, babyUid = babyUid)

    private fun refresh() = runBlocking { NursingNotifications.refresh(context, repository) }

    private fun posted(babyUid: String): Notification? =
        shadowOf(manager).getNotification("nursing:$babyUid", NOTIFICATION_ID)

    private fun Notification.title(): String? =
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.buttons(): List<String> = actions.orEmpty().map { it.title.toString() }

    private fun tap(babyUid: String, control: NursingControl) = runBlocking {
        NursingNotifications.handle(
            context,
            repository,
            Intent(NursingNotifications.ACTION_CONTROL)
                .putExtra(NursingNotifications.EXTRA_CONTROL, control.name)
                .putExtra(NursingNotifications.EXTRA_BABY_UID, babyUid),
        )
    }

    @Test
    fun aRunningFeedIsShownWithItsBabyAndTheTimersButtons() {
        NursingSessionStore.save(context, feed(leaUid, BreastSide.LEFT))

        refresh()

        val shown = posted(leaUid)!!
        assertEquals("Léa · Feeding · Left", shown.title())
        assertEquals(listOf("Pause", "Switch side", "Stop & save"), shown.buttons())
        assertTrue(shown.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    /** No name on a locked screen. */
    @Test
    fun theLockScreenVersionDoesNotNameTheBaby() {
        NursingSessionStore.save(context, feed(leaUid, BreastSide.LEFT))

        refresh()

        val shown = posted(leaUid)!!
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertEquals("Feeding · Left", shown.publicVersion.title())
        assertEquals(listOf("Pause", "Switch side", "Stop & save"), shown.publicVersion.buttons())
    }

    /** Twins: a notification each, told apart on a locked screen without their names. */
    @Test
    fun twoFeedsAreTwoNotificationsNumberedOnTheLockScreen() {
        NursingSessionStore.save(context, feed(leaUid, BreastSide.LEFT))
        NursingSessionStore.save(context, feed(noahUid, BreastSide.RIGHT, start + 60_000L))

        refresh()

        assertEquals("Léa · Feeding · Left", posted(leaUid)!!.title())
        assertEquals("Noah · Feeding · Right", posted(noahUid)!!.title())
        assertEquals("Feed 1 · Left", posted(leaUid)!!.publicVersion.title())
        assertEquals("Feed 2 · Right", posted(noahUid)!!.publicVersion.title())
    }

    /** Coming back names the breast, and a break shows a still figure. */
    @Test
    fun aFeedOnABreakOffersEachBreastAndNoClock() {
        NursingSessionStore.save(context, feed(leaUid, BreastSide.LEFT).paused(start + 8 * 60_000L))

        refresh()

        val shown = posted(leaUid)!!
        assertEquals("Léa · Feeding · Paused", shown.title())
        assertEquals(listOf("Resume left", "Resume right", "Stop & save"), shown.buttons())
        assertFalse(shown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("On a break · 8m at the breast", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun withTheSwitchOffNothingIsShown() {
        NursingNotificationSettings.setEnabled(context, false)
        NursingSessionStore.save(context, feed(leaUid, BreastSide.LEFT))

        refresh()

        assertNull(posted(leaUid))
    }

    @Test
    fun turningTheSwitchOffTakesTheNotificationAway() {
        NursingSessionStore.save(context, feed(leaUid, BreastSide.LEFT))
        refresh()

        NursingNotificationSettings.setEnabled(context, false)
        refresh()

        assertNull(posted(leaUid))
    }

    /** Pause from the notification is the timer's pause, and a second one is a no-op. */
    @Test
    fun pausingTwiceFromTheNotificationKeepsTheFirstBreak() {
        NursingSessionStore.save(context, feed(leaUid, BreastSide.LEFT))

        tap(leaUid, NursingControl.PAUSE)
        val firstBreak = NursingSessionStore.load(context, leaUid)?.pausedAt
        Thread.sleep(5)
        tap(leaUid, NursingControl.PAUSE)

        assertNotNull(firstBreak)
        assertEquals(firstBreak, NursingSessionStore.load(context, leaUid)?.pausedAt)
        assertEquals(1, NursingSessionStore.load(context, leaUid)?.segments?.size)
    }

    /** Stop & save twice saves one feed, to its own baby, and leaves the twin's running. */
    @Test
    fun stoppingTwiceFromTheNotificationSavesOneFeed() = runBlocking {
        NursingSessionStore.save(context, feed(leaUid, BreastSide.LEFT))
        NursingSessionStore.save(context, feed(noahUid, BreastSide.RIGHT, start + 60_000L))
        refresh()

        tap(leaUid, NursingControl.STOP)
        tap(leaUid, NursingControl.STOP)

        val saved = db.feedingDao().allForSync()
        assertEquals(1, saved.size)
        assertEquals(leaUid, db.babyDao().uidById(saved.single().babyId))
        assertNull(posted(leaUid))
        assertNotNull(NursingSessionStore.load(context, noahUid))
    }

    private companion object {
        /** NursingNotifications' id; every feed is told apart by its tag. */
        const val NOTIFICATION_ID = 5_000_000
    }
}
