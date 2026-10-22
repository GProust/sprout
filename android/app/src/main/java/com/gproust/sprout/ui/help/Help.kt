@file:OptIn(ExperimentalMaterial3Api::class)

package com.gproust.sprout.ui.help

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R

/**
 * The **?** in a page's top bar, and the sheet it opens (BDR-26).
 *
 * Help is on demand and nowhere else: nothing opens it but this button — no
 * first launch, no badge, no tip that appears on its own — because a page
 * that explains itself unasked is a page standing between a parent and the
 * thing they opened it for (BDR-6). The button keeps its own open state, so a
 * screen gains help with one line in its top bar.
 */
@Composable
fun HelpButton(page: HelpPage) {
    // Saveable, so turning the phone mid-read does not close what was being read.
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.cd_help),
        )
    }
    if (open) {
        HelpSheet(page = page, onDismiss = { open = false })
    }
}

/**
 * One page's help: its name, then each section's heading and words.
 *
 * Text only. A screenshot in here would have to be taken again in seven
 * languages and two themes every time the page behind it changed, and the page
 * is right there behind the sheet anyway. The headings are headings to
 * TalkBack, so a parent listening can jump straight to "Good to know".
 */
@Composable
fun HelpSheet(page: HelpPage, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(page.title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            helpEntries(page).forEach { entry ->
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(entry.section.title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(entry.body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
