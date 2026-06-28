package com.gproust.sprout.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.gproust.sprout.SproutApplication
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.ui.diaper.DiaperViewModel
import com.gproust.sprout.ui.feeding.FeedingViewModel
import com.gproust.sprout.ui.growth.GrowthViewModel
import com.gproust.sprout.ui.health.HealthViewModel
import com.gproust.sprout.ui.home.HomeViewModel
import com.gproust.sprout.ui.profile.ProfileViewModel
import com.gproust.sprout.ui.sleep.SleepViewModel
import com.gproust.sprout.ui.startup.StartupViewModel
import com.gproust.sprout.ui.treatments.TreatmentsViewModel

/**
 * Builds every ViewModel in the app from the shared [SproutRepository].
 */
class SproutViewModelFactory(
    private val repository: SproutRepository,
    private val context: Context,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return when {
            modelClass.isAssignableFrom(StartupViewModel::class.java) -> StartupViewModel(repository)
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(repository, context)
            modelClass.isAssignableFrom(FeedingViewModel::class.java) -> FeedingViewModel(repository, context)
            modelClass.isAssignableFrom(SleepViewModel::class.java) -> SleepViewModel(repository)
            modelClass.isAssignableFrom(DiaperViewModel::class.java) -> DiaperViewModel(repository)
            modelClass.isAssignableFrom(GrowthViewModel::class.java) -> GrowthViewModel(repository)
            modelClass.isAssignableFrom(HealthViewModel::class.java) -> HealthViewModel(repository)
            modelClass.isAssignableFrom(ProfileViewModel::class.java) -> ProfileViewModel(repository)
            modelClass.isAssignableFrom(TreatmentsViewModel::class.java) -> TreatmentsViewModel(repository, context)
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        } as T
    }
}

/** Remembers the app-wide [SproutViewModelFactory] from the [SproutApplication]. */
@Composable
fun rememberSproutViewModelFactory(): SproutViewModelFactory {
    val app = LocalContext.current.applicationContext as SproutApplication
    return remember { SproutViewModelFactory(app.repository, app) }
}
