package com.gproust.sprout.ui.baby

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.babyAge
import com.gproust.sprout.ui.home.BabyPane
import com.gproust.sprout.ui.home.GrowthSpurtNote
import com.gproust.sprout.ui.home.HomeViewModel
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.delay

/** How often the "how long ago" chips are refreshed. */
private const val CHIP_REFRESH_MS = 60_000L

/**
 * One baby on their own: the same pane the dashboard shows in place when there
 * is only one child, given a tab of its own once there is a choice to make.
 *
 * Which baby this is stays the active-baby selection (BDR-3) — opening a card
 * on the dashboard sets it before navigating here, so the two can't disagree.
 */
@Composable
fun BabyScreen(
    onNavigate: (String) -> Unit,
    onQuickFeed: (BreastSide) -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val state by vm.uiState.collectAsState()
    val activeId by vm.activeBabyId.collectAsState()
    val context = LocalContext.current

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CHIP_REFRESH_MS)
            now = System.currentTimeMillis()
        }
    }

    // Falls back to the first tracked baby: a selection can go stale when the
    // baby it named is archived or deleted from another screen.
    val summary = state.babies.firstOrNull { it.baby.id == activeId }
        ?: state.babies.firstOrNull()

    Scaffold(
        topBar = {
            SproutTopBar(summary?.baby?.name ?: stringResource(R.string.nav_baby))
        },
    ) { padding ->
        if (summary == null) {
            EmptyHint(
                stringResource(R.string.home_setup_prompt),
                Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BabyPane(
                summary = summary,
                tracksWellbeing = state.tracksWellbeing,
                now = now,
                onFeed = onQuickFeed,
                onNavigate = onNavigate,
                header = {
                    Text(
                        babyAge(context, summary.baby.birthDate, now),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                },
            )
            state.spurts[summary.baby.id]?.let {
                Spacer(Modifier.height(16.dp))
                GrowthSpurtNote(it)
            }
        }
    }
}
