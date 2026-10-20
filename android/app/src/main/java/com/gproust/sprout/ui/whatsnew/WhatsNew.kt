@file:OptIn(ExperimentalMaterial3Api::class)

package com.gproust.sprout.ui.whatsnew

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.ui.navigation.Routes

/** What one announcement says, and the screen *Show me* opens, if any. */
data class AnnouncementContent(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val route: String?,
)

/**
 * The words for each id in `ANNOUNCEMENT_IDS`, in all seven languages like
 * every other string. Null for an id this build has no text for, which
 * `WhatsNewTest` keeps from happening.
 */
fun announcementContent(id: Int): AnnouncementContent? = when (id) {
    1 -> AnnouncementContent(R.string.whats_new_1_title, R.string.whats_new_1_body, Routes.FOODS)
    else -> null
}

/**
 * "New in Sprout": the one line the dashboard shows after an update that brings
 * something new (BDR-22).
 *
 * It waits on the dashboard rather than standing in front of it: launch still
 * goes straight to the dashboard (BDR-6), and a parent mid-feed can ignore it.
 * The card opens the list. The cross puts it away, and so does reading it.
 */
@Composable
fun WhatsNewCard(
    newest: Int,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = announcementContent(newest) ?: return
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.NewReleases, contentDescription = null)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    stringResource(R.string.whats_new_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    stringResource(content.title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_whats_new_dismiss),
                )
            }
        }
    }
}

/**
 * The announcements themselves, newest first: a title, a few sentences, and
 * *Show me* where there is a screen to go to.
 */
@Composable
fun WhatsNewSheet(
    ids: List<Int>,
    onShowMe: (route: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.whats_new_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            ids.mapNotNull(::announcementContent).forEach { content ->
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(content.title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(content.body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                content.route?.let { route ->
                    TextButton(onClick = { onShowMe(route) }) {
                        Text(stringResource(R.string.whats_new_show_me))
                    }
                }
            }
        }
    }
}
