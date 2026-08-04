package com.gproust.sprout.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.DeliveryType
import com.gproust.sprout.data.local.ParentProfileEntity
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

class StartupViewModel(private val repository: SproutRepository) : ViewModel() {

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
    ) {
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
                ),
            )
        }
    }
}
