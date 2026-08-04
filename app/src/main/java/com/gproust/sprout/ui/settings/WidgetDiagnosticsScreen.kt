package com.gproust.sprout.ui.settings

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.widget.WidgetDiagnostics

/**
 * What the home-screen widget did, and what happens when it is asked to render
 * right now. The widget lives in a broadcast receiver with no UI of its own, so
 * without this there is nothing to inspect when it misbehaves — least of all on
 * a Play build, where logcat isn't realistically reachable.
 *
 * The report is plain text the user copies or shares deliberately; Sprout sends
 * nothing anywhere by itself.
 */
@Composable
fun WidgetDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf<String?>(null) }
    // Bumping this re-runs the self-test, so "Run again" is meaningful after
    // reinstalling or re-adding the widget.
    var run by remember { mutableIntStateOf(0) }

    LaunchedEffect(run) {
        report = null
        report = WidgetDiagnostics.report(context)
    }

    Scaffold(
        topBar = { SproutTopBar(stringResource(R.string.screen_widget_diagnostics), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.widget_diagnostics_intro),
                style = MaterialTheme.typography.bodyMedium,
            )

            val text = report
            if (text == null) {
                CircularProgressIndicator()
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(12.dp)
                            // Stack traces are wide; let them scroll rather
                            // than wrap into an unreadable block.
                            .horizontalScroll(rememberScrollState()),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Sprout widget diagnostics")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(
                            Intent.createChooser(
                                send,
                                context.getString(R.string.widget_diagnostics_share),
                            ),
                        )
                    }) {
                        Text(stringResource(R.string.widget_diagnostics_share))
                    }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                        Text(stringResource(R.string.widget_diagnostics_copy))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { run++ }) {
                        Text(stringResource(R.string.widget_diagnostics_rerun))
                    }
                    OutlinedButton(onClick = {
                        WidgetDiagnostics.clear(context)
                        run++
                    }) {
                        Text(stringResource(R.string.widget_diagnostics_clear))
                    }
                }
            }
        }
    }
}
