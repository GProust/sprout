package com.gproust.sprout

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gproust.sprout.data.local.Bleeding
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.BreastState
import com.gproust.sprout.data.local.DeliveryType
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.DiaperType
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.Recovery
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.local.TreatmentEntity
import com.gproust.sprout.data.local.WellbeingEntity
import com.gproust.sprout.ui.checkin.DailyCheckInScreen
import com.gproust.sprout.ui.diaper.DiaperScreen
import com.gproust.sprout.ui.feeding.FeedingScreen
import com.gproust.sprout.ui.growth.GrowthScreen
import com.gproust.sprout.ui.health.HealthScreen
import com.gproust.sprout.ui.home.HomeScreen
import com.gproust.sprout.ui.onboarding.OnboardingScreen
import com.gproust.sprout.ui.profile.ProfileScreen
import com.gproust.sprout.ui.settings.SettingsScreen
import com.gproust.sprout.ui.sleep.SleepScreen
import com.gproust.sprout.ui.treatments.TreatmentsScreen
import com.gproust.sprout.ui.theme.SproutTheme
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

    private fun seed() = runBlocking {
        val repo = app.repository
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val day = 24L * hour
        // Twins, to show the baby switcher and the babies manager.
        val lea = repo.addBaby("Léa", now - 21 * day)
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
        // Logs are stamped with the active baby (Léa).
        repo.addFeeding(FeedingEntity(type = FeedType.BREAST, side = BreastSide.LEFT, startTime = now - 2 * hour))
        repo.addFeeding(FeedingEntity(type = FeedType.BOTTLE, amountMl = 120, startTime = now - 5 * hour))
        repo.addSleep(SleepEntity(startTime = now - 4 * hour, endTime = now - 2 * hour))
        repo.addDiaper(DiaperEntity(time = now - hour, type = DiaperType.WET))
        repo.addDiaper(DiaperEntity(time = now - 3 * hour, type = DiaperType.DIRTY))
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

    private fun type(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.waitForIdle()
    }

    private fun save(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
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
                "Marise",
                gaveBirth = true,
                breastfeeding = true,
                deliveryType = DeliveryType.CESAREAN,
                onSubmit = {},
                onSkip = {},
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
                "Tom",
                gaveBirth = false,
                breastfeeding = false,
                deliveryType = null,
                onSubmit = {},
                onSkip = {},
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
        show { FeedingScreen() }
        save("05-feeding")
        show { SleepScreen() }
        save("06-sleep")
        show { DiaperScreen() }
        save("07-diaper")
        show { GrowthScreen() }
        save("08-growth")
        show { HealthScreen {} }
        save("09-wellbeing")
        // Babies manager: both babies, the active marker, and add/track/delete actions.
        show { ProfileScreen {} }
        save("10-profile-babies")
        show { SettingsScreen {} }
        save("11-settings")
        show { TreatmentsScreen {} }
        save("12-treatments")
    }
}
