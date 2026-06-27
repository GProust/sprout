package com.gproust.sprout

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.Bleeding
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.BreastState
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.DiaperType
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.MotherHealthEntity
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.ParentRole
import com.gproust.sprout.data.local.Recovery
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.ui.checkin.DailyCheckInScreen
import com.gproust.sprout.ui.diaper.DiaperScreen
import com.gproust.sprout.ui.feeding.FeedingScreen
import com.gproust.sprout.ui.growth.GrowthScreen
import com.gproust.sprout.ui.health.HealthScreen
import com.gproust.sprout.ui.home.HomeScreen
import com.gproust.sprout.ui.onboarding.OnboardingScreen
import com.gproust.sprout.ui.profile.ProfileScreen
import com.gproust.sprout.ui.sleep.SleepScreen
import com.gproust.sprout.ui.theme.SproutTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders each visual feature and writes a PNG to the app's external files dir,
 * which CI pulls off the emulator and commits to the PR. Not a pass/fail test —
 * it exists to produce screenshots.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val app
        get() = instrumentation.targetContext.applicationContext as SproutApplication

    // Internal files dir so CI can pull via `run-as` (external Android/data is
    // not adb-accessible on API 30+).
    private val outputDir: File
        get() = File(instrumentation.targetContext.filesDir, "screenshots").also { it.mkdirs() }

    private fun seed() = runBlocking {
        val repo = app.repository
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val day = 24L * hour
        repo.saveParentProfile(ParentProfileEntity(1L, "Marise", ParentRole.MOTHER, null))
        repo.saveBaby(BabyEntity(1L, "Léa", now - 21 * day))
        repo.addFeeding(FeedingEntity(type = FeedType.BREAST, side = BreastSide.LEFT, startTime = now - 2 * hour))
        repo.addFeeding(FeedingEntity(type = FeedType.BOTTLE, amountMl = 120, startTime = now - 5 * hour))
        repo.addSleep(SleepEntity(startTime = now - 4 * hour, endTime = now - 2 * hour))
        repo.addDiaper(DiaperEntity(time = now - hour, type = DiaperType.WET))
        repo.addDiaper(DiaperEntity(time = now - 3 * hour, type = DiaperType.DIRTY))
        repo.addGrowth(GrowthEntity(time = now - 14 * day, weightGrams = 3200, heightMm = 500))
        repo.addGrowth(GrowthEntity(time = now, weightGrams = 3900, heightMm = 530))
        repo.addMotherHealth(
            MotherHealthEntity(
                time = now - day,
                mood = 4,
                bleeding = Bleeding.LIGHT,
                breast = BreastState.TENDER,
                recovery = Recovery.GOOD,
                notes = "Feeling a bit more like myself today",
            ),
        )
    }

    private fun save(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(outputDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun captureScreens() {
        seed()

        val screens: List<Pair<String, @androidx.compose.runtime.Composable () -> Unit>> = listOf(
            "01-onboarding" to { OnboardingScreen { _, _, _, _ -> } },
            "02-daily-checkin-mother" to { DailyCheckInScreen("Marise", ParentRole.MOTHER, "Léa", {}, {}) },
            "03-daily-checkin-coparent" to { DailyCheckInScreen("Tom", ParentRole.CO_PARENT, "Léa", {}, {}) },
            "04-home" to { HomeScreen {} },
            "05-feeding" to { FeedingScreen() },
            "06-sleep" to { SleepScreen() },
            "07-diaper" to { DiaperScreen() },
            "08-growth" to { GrowthScreen() },
            "09-wellbeing-board" to { HealthScreen {} },
            "10-profile" to { ProfileScreen {} },
        )

        var index by mutableIntStateOf(0)
        rule.setContent {
            SproutTheme {
                screens[index].second()
            }
        }

        for (i in screens.indices) {
            rule.runOnUiThread { index = i }
            rule.waitForIdle()
            // Give Room flows a moment to emit seeded data into the screen.
            Thread.sleep(700)
            rule.waitForIdle()
            save(screens[i].first)
        }
    }
}
