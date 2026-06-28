package com.gproust.sprout.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.DeliveryType
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.WellbeingEntity
import com.gproust.sprout.ui.common.isSameDay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the app should show right after launch. */
sealed interface Startup {
    data object Loading : Startup
    data object Onboarding : Startup
    data class CheckIn(
        val name: String,
        val gaveBirth: Boolean,
        val breastfeeding: Boolean,
        val deliveryType: DeliveryType?,
    ) : Startup
    data object Main : Startup
}

/** True when a daily check-in hasn't happened yet today. */
fun needsCheckIn(lastCheckIn: Long?, now: Long): Boolean =
    lastCheckIn == null || !isSameDay(lastCheckIn, now)

class StartupViewModel(private val repository: SproutRepository) : ViewModel() {

    val startup = repository.parentProfile.map { profile ->
        val now = System.currentTimeMillis()
        when {
            profile == null -> Startup.Onboarding
            needsCheckIn(profile.lastCheckIn, now) ->
                Startup.CheckIn(profile.name, profile.gaveBirth, profile.breastfeeding, profile.deliveryType)
            else -> Startup.Main
        }
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

    fun submitCheckIn(entity: WellbeingEntity) {
        viewModelScope.launch {
            repository.addWellbeing(entity)
            repository.updateParentLastCheckIn(System.currentTimeMillis())
        }
    }

    fun markCheckedIn() {
        viewModelScope.launch {
            repository.updateParentLastCheckIn(System.currentTimeMillis())
        }
    }
}
