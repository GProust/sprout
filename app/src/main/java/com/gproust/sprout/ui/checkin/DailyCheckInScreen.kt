package com.gproust.sprout.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gproust.sprout.data.local.Bleeding
import com.gproust.sprout.data.local.BreastState
import com.gproust.sprout.data.local.MotherHealthEntity
import com.gproust.sprout.data.local.ParentRole
import com.gproust.sprout.data.local.Recovery
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.greetingFor
import com.gproust.sprout.ui.common.label
import com.gproust.sprout.ui.common.moodEmoji

@Composable
fun DailyCheckInScreen(
    name: String,
    role: ParentRole,
    babyName: String?,
    onSubmitMother: (MotherHealthEntity) -> Unit,
    onDone: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val greeting = "${greetingFor(now)}, $name"

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))
        if (role == ParentRole.MOTHER) {
            MotherCheckIn(greeting = greeting, now = now, onSubmit = onSubmitMother, onSkip = onDone)
        } else {
            CoParentCheckIn(greeting = greeting, babyName = babyName, onContinue = onDone)
        }
    }
}

@Composable
private fun MotherCheckIn(
    greeting: String,
    now: Long,
    onSubmit: (MotherHealthEntity) -> Unit,
    onSkip: () -> Unit,
) {
    var mood by remember { mutableIntStateOf(3) }
    var recovery by remember { mutableStateOf<Recovery?>(null) }
    var breast by remember { mutableStateOf<BreastState?>(null) }
    var bleeding by remember { mutableStateOf<Bleeding?>(null) }
    var notes by remember { mutableStateOf("") }

    Text("$greeting 🌸", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Let's take a moment for you.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    FieldLabel("How are you feeling today?")
    ChoiceChips(
        options = listOf(1, 2, 3, 4, 5),
        selected = mood,
        onSelect = { mood = it },
        labelOf = { "${moodEmoji(it)} $it" },
    )

    FieldLabel("How is your healing coming along?")
    ChoiceChips(
        options = Recovery.entries,
        selected = recovery,
        onSelect = { recovery = if (recovery == it) null else it },
        labelOf = { it.label() },
    )

    FieldLabel("How do your breasts feel?")
    ChoiceChips(
        options = BreastState.entries,
        selected = breast,
        onSelect = { breast = if (breast == it) null else it },
        labelOf = { it.label() },
    )

    FieldLabel("Any bleeding today?")
    ChoiceChips(
        options = Bleeding.entries,
        selected = bleeding,
        onSelect = { bleeding = if (bleeding == it) null else it },
        labelOf = { it.label() },
    )

    Spacer(Modifier.height(8.dp))
    NotesField(value = notes, onChange = { notes = it })

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = {
            onSubmit(
                MotherHealthEntity(
                    time = now,
                    mood = mood,
                    bleeding = bleeding,
                    breast = breast,
                    recovery = recovery,
                    notes = notes.ifBlank { null },
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Save today's check-in")
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text("Not now")
    }
}

@Composable
private fun CoParentCheckIn(
    greeting: String,
    babyName: String?,
    onContinue: () -> Unit,
) {
    val who = babyName?.takeIf { it.isNotBlank() } ?: "your little one"
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$greeting 👋", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Hope you got some rest. Ready to look after $who today?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Let's go")
            }
        }
    }
}
