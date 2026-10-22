package com.gproust.sprout.ui.startup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.DeliveryType
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.ui.tour.TourSettings
import com.gproust.sprout.ui.whatsnew.WhatsNewSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the app should show right after launch. Only the first run stops on
 * anything: the daily check-in waits on the dashboard instead of gating the
 * app, so opening Sprout mid-feed always lands straight on the main screen.
 */
sealed interface Startup {
    data object Loading : Startup
    data object Onboarding : Startup
    data object Main : Startup
}

class StartupViewModel(
    private val repository: SproutRepository,
    private val context: Context,
) : ViewModel() {

    val startup = repository.parentProfile.map { profile ->
        if (profile == null) Startup.Onboarding else Startup.Main
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Startup.Loading)

    fun completeOnboarding(
        name: String,
        gaveBirth: Boolean,
        breastfeeding: Boolean,
        deliveryType: DeliveryType?,
        babyName: String,
        birthDate: Long,
        trackWellbeing: Boolean,
    ) {
        // Everything is new to someone who has just arrived, and nothing has
        // changed: a fresh install starts with no "what's new" to catch up on
        // (BDR-22). Before the profile is saved, because saving it is what
        // opens the dashboard.
        WhatsNewSettings.markAllSeen(context)
        // This is also the one launch that cannot be the middle of a feed, so
        // the dashboard about to open owes a short tour of itself (BDR-27).
        // Nothing else ever writes it: a phone that already had a profile —
        // set up before the tour existed, or restored — is never shown round.
        TourSettings.markPending(context)
        viewModelScope.launch {
            val babyId = if (babyName.isNotBlank()) {
                repository.addBaby(babyName, birthDate)
            } else {
                null
            }
            repository.saveParentProfile(
                ParentProfileEntity(
                    id = 1L,
                    name = name.trim(),
                    gaveBirth = gaveBirth,
                    breastfeeding = breastfeeding,
                    deliveryType = if (gaveBirth) deliveryType else null,
                    lastCheckIn = null,
                    activeBabyId = babyId,
                    trackWellbeing = trackWellbeing,
                ),
            )
        }
    }
}
