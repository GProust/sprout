package com.gproust.sprout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gproust.sprout.data.local.Bleeding
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.BreastState
import com.gproust.sprout.data.local.DeliveryType
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.Recovery
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.local.StoolColor
import com.gproust.sprout.data.local.TreatmentEntity
import com.gproust.sprout.data.local.WellbeingEntity
import com.gproust.sprout.ui.settings.FeedingReminderSettings
import com.gproust.sprout.ui.checkin.DailyCheckInScreen
import com.gproust.sprout.ui.diaper.DiaperScreen
import com.gproust.sprout.ui.feeding.FeedingScreen
import com.gproust.sprout.ui.feeding.NursingScreen
import com.gproust.sprout.ui.growth.GrowthScreen
import com.gproust.sprout.ui.health.HealthScreen
import com.gproust.sprout.ui.home.HomeScreen
import com.gproust.sprout.ui.onboarding.OnboardingScreen
import com.gproust.sprout.ui.profile.ProfileScreen
import com.gproust.sprout.ui.settings.SettingsScreen
import com.gproust.sprout.ui.sleep.SleepScreen
import com.gproust.sprout.ui.treatments.TreatmentsScreen
import com.gproust.sprout.ui.feeding.NursingSessionStore
import com.gproust.sprout.ui.theme.SproutTheme
import com.gproust.sprout.widget.SproutWidgetUi
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders each visual feature — clicking through the multi-step flows — and
 * writes a PNG per page to the app's internal files dir, which CI pulls off the
 * emulator and commits to the PR. Not a pass/fail test; it produces screenshots.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val app
        get() = instrumentation.targetContext.applicationContext as SproutApplication

    private val outputDir: File
        get() = File(instrumentation.targetContext.filesDir, "screenshots").also { it.mkdirs() }

    /** Set in [seed] so captures can reference the first baby (e.g. its reminder override). */
    private var leaId = 0L

    private fun seed() = runBlocking {
        val repo = app.repository
        val now = System.currentTimeMillis()
        val min = 60_000L
        val hour = 3_600_000L
        val day = 24L * hour
        // Twins, to show the baby switcher and the babies manager.
        val lea = repo.addBaby("Léa", now - 21 * day)
        leaId = lea
        repo.addBaby("Noah", now - 21 * day)
        repo.saveParentProfile(
            ParentProfileEntity(
                1L,
                "Marise",
                gaveBirth = true,
                breastfeeding = true,
                deliveryType = DeliveryType.CESAREAN,
                lastCheckIn = null,
                activeBabyId = lea,
            ),
        )
        // Logs are stamped with the active baby (Léa). The odd 2 h 15 min age
        // lets the widget capture show both units of its elapsed-time line.
        repo.addFeeding(FeedingEntity(type = FeedType.BREAST, side = BreastSide.LEFT, startTime = now - 2 * hour - 15 * min))
        // A completed breastfeed that switched sides twice (left → right → left)
        // — shows the per-stretch breakdown with a time range for each.
        val nurseStart = now - 4 * hour
        repo.addFeeding(
            FeedingEntity(
                type = FeedType.BREAST,
                side = BreastSide.BOTH,
                startTime = nurseStart,
                endTime = nurseStart + 13 * min,
                leftDurationMs = 9 * min,
                rightDurationMs = 4 * min,
                segments = listOf(
                    NursingSegment(BreastSide.LEFT, nurseStart, nurseStart + 6 * min),
                    NursingSegment(BreastSide.RIGHT, nurseStart + 6 * min, nurseStart + 10 * min),
                    NursingSegment(BreastSide.LEFT, nurseStart + 10 * min, nurseStart + 13 * min),
                ),
            ),
        )
        repo.addFeeding(FeedingEntity(type = FeedType.BOTTLE, amountMl = 120, startTime = now - 5 * hour))
        repo.addSleep(SleepEntity(startTime = now - 4 * hour, endTime = now - 2 * hour))
        repo.addDiaper(DiaperEntity(time = now - hour, wet = true))
        repo.addDiaper(DiaperEntity(time = now - 3 * hour, wet = true, dirty = true, stoolColor = StoolColor.YELLOW))
        repo.addDiaper(DiaperEntity(time = now - 5 * hour, wet = true, dirty = true, stoolColor = StoolColor.GREEN))
        repo.addGrowth(GrowthEntity(time = now - 14 * day, weightGrams = 3200, heightMm = 500))
        repo.addGrowth(GrowthEntity(time = now, weightGrams = 3900, heightMm = 530))
        repo.addTreatment(
            TreatmentEntity(
                name = "Vitamin D",
                dose = "1 drop",
                intervalDays = 1,
                timesOfDay = listOf(9 * 60),
                startDate = now - 7 * day,
                endDate = now + 358 * day,
            ),
        )
        repo.addWellbeing(
            WellbeingEntity(
                time = now - day,
                mood = 4,
                bleeding = Bleeding.LIGHT,
                recovery = Recovery.GOOD,
                breast = BreastState.TENDER,
                notes = "Feeling a bit more like myself today",
            ),
        )
        // Feeding reminders are off by default; the Settings captures toggle them
        // on explicitly (see captureScreens) to show both states honestly.
        FeedingReminderSettings.setEnabled(app, false)
    }

    private val slot = mutableStateOf<@Composable () -> Unit>({})

    private fun settle() {
        rule.waitForIdle()
        Thread.sleep(400)
        rule.waitForIdle()
    }

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { slot.value = content }
        settle()
    }

    private fun tap(text: String) {
        rule.onNodeWithText(text).performClick()
        settle()
    }

    private fun tapDesc(description: String) {
        rule.onNodeWithContentDescription(description).performClick()
        settle()
    }

    /**
     * Scrolls the Settings list down to the daily check-in section, which sits
     * below the eight language rows and so never fits on a first screenful.
     */
    private fun scrollToCheckInSection() {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Daily check-in"))
        settle()
    }

    private fun type(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.waitForIdle()
    }

    private fun save(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(outputDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Full-screen capture via UiAutomation. Needed when a modal bottom sheet is
     * open: the sheet lives in its own window, which [save]'s onRoot() capture
     * can't see (and with two compose roots onRoot() isn't even unique).
     */
    private fun saveScreen(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        File(outputDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Renders the home-screen widget's Glance content to RemoteViews (the same
     * path the launcher uses), inflates them off-screen at a 2x1-ish widget
     * size, and saves the result — no launcher automation needed. Shows the
     * live nursing session when one is in the store, like the real widget.
     */
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun saveWidget(name: String) {
        val context = instrumentation.targetContext
        val session = NursingSessionStore.load(context)
        val feed = runBlocking { app.repository.lastFeedForActiveBaby() }
        val babyName = runBlocking { app.repository.activeBabyName() }
        val size = DpSize(180.dp, 110.dp)
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(context, size) {
                GlanceTheme { SproutWidgetUi(session, feed, babyName) }
            }.remoteViews
        }
        val density = context.resources.displayMetrics.density
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            val view = remoteViews.apply(context, FrameLayout(context))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
            view.draw(Canvas(bmp))
        }
        File(outputDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun captureScreens() {
        seed()
        rule.setContent { SproutTheme { slot.value() } }

        // Onboarding — step through the whole flow.
        show { OnboardingScreen { _, _, _, _, _, _ -> } }
        save("01-onboarding-1-welcome")
        tap("Get started")
        save("01-onboarding-2-about-you")
        type("Marise")
        tap("Next")
        save("01-onboarding-3-baby")
        type("Léa")
        tap("Next")
        save("01-onboarding-4-care")

        // Daily check-in for a birthing, breastfeeding parent (all questions).
        show {
            DailyCheckInScreen(
                profile = ParentProfileEntity(
                    1L,
                    "Marise",
                    gaveBirth = true,
                    breastfeeding = true,
                    deliveryType = DeliveryType.CESAREAN,
                ),
                onSubmit = {},
                onSkip = {},
                onOptOut = {},
            )
        }
        save("02-checkin-birthing-1-intro")
        tap("Begin")
        save("02-checkin-birthing-2-mood")
        tap("Next")
        save("02-checkin-birthing-3-healing")
        tap("Next")
        save("02-checkin-birthing-4-bleeding")
        tap("Next")
        save("02-checkin-birthing-5-breasts")
        tap("Next")
        save("02-checkin-birthing-6-notes")

        // Daily check-in for a non-birthing parent (just mood + notes).
        show {
            DailyCheckInScreen(
                profile = ParentProfileEntity(
                    1L,
                    "Tom",
                    gaveBirth = false,
                    breastfeeding = false,
                    deliveryType = null,
                ),
                onSubmit = {},
                onSkip = {},
                onOptOut = {},
            )
        }
        save("03-checkin-partner-1-intro")
        tap("Begin")
        save("03-checkin-partner-2-mood")
        tap("Next")
        save("03-checkin-partner-3-notes")

        // Single-page screens. The Home top bar shows the active baby (Léa) with
        // the switcher affordance, since two babies are seeded.
        show { HomeScreen {} }
        save("04-home")
        // The feeding history now fills the screen, newest first with day
        // headers; the log form lives in a bottom sheet behind the "+" button.
        show { FeedingScreen() }
        save("05-feeding")
        // The completed switched breastfeed (left → right → left) shows a light
        // preview (per-side + total); "Details" expands the per-stretch
        // breakdown. Captured before any live session so the list is idle.
        rule.onNodeWithTag("feedingList").performScrollToNode(hasText("Details"))
        tap("Details")
        save("05-feeding-6-history-details")
        // Widget (idle): captured before any live session starts, so it shows
        // the last logged breastfeed. Named 13-* to sort with the widget shots.
        saveWidget("13-widget-last-breastfeed")
        // Manual log: open the sheet and add two more sides so the form shows a
        // left → right → left entry. The sheet is its own window, so captures go
        // through the full-screen path.
        tapDesc("Log a feeding")
        rule.onNodeWithText("Add a side").performScrollTo().performClick()
        settle()
        rule.onNodeWithText("Add a side").performScrollTo().performClick()
        settle()
        saveScreen("05-feeding-5-manual-lrl")
        // Live breastfeeding timer, now its own screen: start on the left, let it
        // run, then switch sides. The ticking LaunchedEffect never completes, so we
        // stop the test clock auto-advancing (which would spin waitForIdle) and
        // drive frames by hand around each capture.
        rule.mainClock.autoAdvance = false
        show { NursingScreen(side = BreastSide.LEFT) }
        rule.mainClock.advanceTimeByFrame() // let the start-session effect run
        Thread.sleep(2200)
        rule.mainClock.advanceTimeBy(1000) // let the per-second tick fire
        save("05-feeding-2-nursing")
        rule.onNodeWithText("Switch to right").performClick()
        rule.mainClock.advanceTimeByFrame()
        Thread.sleep(1500)
        rule.mainClock.advanceTimeBy(1000)
        save("05-feeding-3-switched")
        // Switch back to the left, so the session lists two left stretches and one
        // right — the per-stretch breakdown building up live.
        rule.onNodeWithText("Switch to left").performClick()
        rule.mainClock.advanceTimeByFrame()
        Thread.sleep(1500)
        rule.mainClock.advanceTimeBy(1000)
        save("05-feeding-4-two-left-one-right")
        rule.mainClock.autoAdvance = true

        show { SleepScreen() }
        save("06-sleep")
        show { DiaperScreen() }
        save("07-diaper")
        // The change form opens in a bottom sheet: tick "Stool" to reveal the
        // predefined stool-colour scale, then pick one.
        tapDesc("Log a change")
        tap("Stool")
        saveScreen("07-diaper-2-stool-colour")
        tapDesc("Brown")
        saveScreen("07-diaper-3-colour-picked")
        show { GrowthScreen() }
        save("08-growth")
        show { HealthScreen {} }
        save("09-wellbeing")
        // Babies manager: both babies, the active marker, and add/track/delete actions.
        show { ProfileScreen {} }
        save("10-profile-babies")
        // Same manager after giving Léa a custom feeding-reminder override (every
        // 2h) while Noah keeps following the app default — shows the per-baby
        // summary line and the reminder action. (The override dialog itself is an
        // AlertDialog popup, which onRoot() can't capture.)
        runBlocking {
            app.repository.activeBaby(leaId)?.let {
                app.repository.updateBaby(
                    it.copy(feedingReminderEnabled = true, feedingReminderIntervalMinutes = 120),
                )
            }
        }
        show { ProfileScreen {} }
        save("10-profile-babies-2-reminder-override")
        // Settings with feeding reminders OFF — the real default (opt-in).
        FeedingReminderSettings.setEnabled(app, false)
        show { SettingsScreen {} }
        save("11-settings")
        // Same screen with reminders turned ON, to show the interval options.
        FeedingReminderSettings.setEnabled(app, true)
        FeedingReminderSettings.setIntervalMinutes(app, 180)
        show { SettingsScreen {} }
        save("11-settings-2-feeding-on")
        // The daily check-in section lives below the language list, so scroll
        // down to it: the master switch on, with a toggle per body question.
        show { SettingsScreen {} }
        scrollToCheckInSection()
        save("11-settings-3-checkin")
        // Same section with wellbeing tracking switched off — the check-in
        // leaves the dashboard and the per-question toggles fold away with it.
        // Restored afterwards so later captures see the normal state.
        runBlocking { app.repository.setTrackWellbeing(false) }
        show { SettingsScreen {} }
        scrollToCheckInSection()
        save("11-settings-4-checkin-off")
        runBlocking { app.repository.setTrackWellbeing(true) }
        show { TreatmentsScreen {} }
        save("12-treatments")

        // Widget (live): the nursing session started for the timer captures is
        // still running (back on the left), so the widget shows the ongoing
        // side with its ticking chronometer.
        saveWidget("14-widget-nursing")
    }
}
