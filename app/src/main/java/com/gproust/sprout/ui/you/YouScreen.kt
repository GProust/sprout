package com.gproust.sprout.ui.you

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.home.HomeViewModel
import com.gproust.sprout.ui.navigation.Routes
import com.gproust.sprout.ui.rememberSproutViewModelFactory

/**
 * The parent's own tab.
 *
 * Pumping and wellbeing are the two logs in Sprout that belong to a person
 * rather than to a child — neither carries a `babyId`, by decision, and neither
 * is deleted when a baby is (BDR-1, BDR-7). They used to sit in the dashboard's
 * log list next to the baby's screens with nothing to say why, and the daily
 * check-in had nowhere at all once it was dismissed. This is that shelf.
 */
@Composable
fun YouScreen(onNavigate: (String) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val state by vm.uiState.collectAsState()

    Scaffold(
        topBar = { SproutTopBar(stringResource(R.string.nav_you)) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.you_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            YouEntry(
                icon = Icons.Filled.WaterDrop,
                title = stringResource(R.string.screen_pumping),
                body = stringResource(R.string.you_pumping_body),
                onClick = { onNavigate(Routes.PUMPING) },
            )

            // Hidden for a parent who has turned their own tracking off, the
            // same way the log grid drops it — the history stays, untouched.
            if (state.tracksWellbeing) {
                Spacer(Modifier.height(12.dp))
                YouEntry(
                    icon = Icons.Filled.Favorite,
                    title = stringResource(R.string.screen_wellbeing),
                    body = stringResource(R.string.you_wellbeing_body),
                    onClick = { onNavigate(Routes.HEALTH) },
                )

                Spacer(Modifier.height(12.dp))
                YouEntry(
                    icon = Icons.Filled.Favorite,
                    title = stringResource(R.string.home_checkin_title),
                    body = stringResource(
                        if (state.checkInPending) R.string.home_checkin_body
                        else R.string.you_checkin_done,
                    ),
                    onClick = { onNavigate(Routes.CHECKIN) },
                )
            }
        }
    }
}

@Composable
private fun YouEntry(
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
