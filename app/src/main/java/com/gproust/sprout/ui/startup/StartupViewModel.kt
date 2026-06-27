package com.gproust.sprout.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BabyEntity
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
                Startup.CheckIn(profile.name, profile.gaveBirth, profile.breastfeeding)
            else -> Startup.Main
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Startup.Loading)

    fun completeOnboarding(
        name: String,
        gaveBirth: Boolean,
        breastfeeding: Boolean,
        babyName: String,
        birthDate: Long,
    ) {
        viewModelScope.launch {
            if (babyName.isNotBlank()) {
                repository.saveBaby(BabyEntity(id = 1L, name = babyName.trim(), birthDate = birthDate))
            }
            repository.saveParentProfile(
                ParentProfileEntity(
                    id = 1L,
                    name = name.trim(),
                    gaveBirth = gaveBirth,
                    breastfeeding = breastfeeding,
                    lastCheckIn = null,
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
