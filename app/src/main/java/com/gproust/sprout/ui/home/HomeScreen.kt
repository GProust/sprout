@file:OptIn(ExperimentalMaterial3Api::class)

package com.gproust.sprout.ui.home

import android.content.Context
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
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

class HomeViewModel(repository: SproutRepository, private val context: Context) : ViewModel() {
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
            ageText = baby?.let { babyAge(context, it.birthDate, now) },
            hasProfile = baby != null,
            feedsToday = feedings.count { it.startTime >= dayStart },
            diapersToday = diapers.count { it.time >= dayStart },
            sleepTodayText = formatDuration(context, sleepMillisToday),
            lastFeedText = feedings.maxByOrNull { it.startTime }
                ?.let { formatRelative(context, it.startTime, now) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())
}

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { onNavigate(Routes.HEALTH) }) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = stringResource(R.string.cd_wellbeing),
                        )
                    }
                    IconButton(onClick = { onNavigate(Routes.PROFILE) }) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = stringResource(R.string.cd_baby_profile),
                        )
                    }
                    IconButton(onClick = { onNavigate(Routes.SETTINGS) }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.cd_settings),
                        )
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
                                stringResource(R.string.home_greeting, greetingFor(context, now), parent),
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
                                stringResource(R.string.home_last_feed, it),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.home_welcome_no_profile),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            stringResource(R.string.home_setup_prompt),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { onNavigate(Routes.PROFILE) }) {
                            Text(stringResource(R.string.home_setup_profile))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.home_today),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = stringResource(R.string.stat_feeds),
                    value = state.feedsToday.toString(),
                    icon = Icons.Filled.LocalDrink,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = stringResource(R.string.stat_sleep),
                    value = state.sleepTodayText,
                    icon = Icons.Filled.Bedtime,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = stringResource(R.string.stat_diapers),
                    value = state.diapersToday.toString(),
                    icon = Icons.Filled.BabyChangingStation,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.home_log),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            QuickAction(stringResource(R.string.quick_feeding), onClick = { onNavigate(Routes.FEEDING) })
            QuickAction(stringResource(R.string.quick_sleep), onClick = { onNavigate(Routes.SLEEP) })
            QuickAction(stringResource(R.string.quick_diaper), onClick = { onNavigate(Routes.DIAPER) })
            QuickAction(stringResource(R.string.quick_growth), onClick = { onNavigate(Routes.GROWTH) })
            QuickAction(stringResource(R.string.quick_wellbeing), onClick = { onNavigate(Routes.HEALTH) })
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
