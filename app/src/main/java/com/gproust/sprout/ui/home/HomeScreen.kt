@file:OptIn(ExperimentalMaterial3Api::class)

package com.gproust.sprout.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.ui.common.StatCard
import com.gproust.sprout.ui.common.babyAge
import com.gproust.sprout.ui.common.greetingFor
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.common.formatRelative
import com.gproust.sprout.ui.common.startOfDay
import com.gproust.sprout.ui.navigation.Routes
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val parentName: String? = null,
    val babyName: String? = null,
    val ageText: String? = null,
    val hasProfile: Boolean = false,
    val feedsToday: Int = 0,
    val diapersToday: Int = 0,
    val sleepTodayText: String = "0m",
    val lastFeedText: String? = null,
)

class HomeViewModel(repository: SproutRepository) : ViewModel() {
    val uiState = combine(
        repository.parentProfile,
        repository.baby,
        repository.feedings,
        repository.sleeps,
        repository.diapers,
    ) { parent, baby, feedings, sleeps, diapers ->
        val now = System.currentTimeMillis()
        val dayStart = startOfDay(now)

        val sleepMillisToday = sleeps
            .filter { it.startTime >= dayStart }
            .sumOf { (it.endTime ?: now) - it.startTime }

        HomeUiState(
            parentName = parent?.name,
            babyName = baby?.name,
            ageText = baby?.let { babyAge(it.birthDate, now) },
            hasProfile = baby != null,
            feedsToday = feedings.count { it.startTime >= dayStart },
            diapersToday = diapers.count { it.time >= dayStart },
            sleepTodayText = formatDuration(sleepMillisToday),
            lastFeedText = feedings.maxByOrNull { it.startTime }
                ?.let { formatRelative(it.startTime, now) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())
}

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val state by vm.uiState.collectAsState()
    val now = remember { System.currentTimeMillis() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Sprout") },
                actions = {
                    IconButton(onClick = { onNavigate(Routes.HEALTH) }) {
                        Icon(Icons.Filled.Favorite, contentDescription = "Wellbeing")
                    }
                    IconButton(onClick = { onNavigate(Routes.PROFILE) }) {
                        Icon(Icons.Filled.Person, contentDescription = "Baby profile")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    if (state.hasProfile) {
                        state.parentName?.let { parent ->
                            Text(
                                "${greetingFor(now)}, $parent 👋",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Text(
                            state.babyName.orEmpty(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            state.ageText.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.lastFeedText?.let {
                            Text(
                                "Last feed: $it",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else {
                        Text("Welcome to Sprout 🌱", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Set up your baby's profile to get started.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { onNavigate(Routes.PROFILE) }) {
                            Text("Set up profile")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "Feeds",
                    value = state.feedsToday.toString(),
                    icon = Icons.Filled.LocalDrink,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "Sleep",
                    value = state.sleepTodayText,
                    icon = Icons.Filled.Bedtime,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "Diapers",
                    value = state.diapersToday.toString(),
                    icon = Icons.Filled.BabyChangingStation,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            QuickAction("🍼  Feeding", onClick = { onNavigate(Routes.FEEDING) })
            QuickAction("😴  Sleep", onClick = { onNavigate(Routes.SLEEP) })
            QuickAction("🧷  Diaper", onClick = { onNavigate(Routes.DIAPER) })
            QuickAction("📏  Growth", onClick = { onNavigate(Routes.GROWTH) })
            QuickAction("💚  Wellbeing", onClick = { onNavigate(Routes.HEALTH) })
        }
    }
}

@Composable
private fun QuickAction(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}
