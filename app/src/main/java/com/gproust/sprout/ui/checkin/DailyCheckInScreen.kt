package com.gproust.sprout.ui.checkin

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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.greetingFor
import com.gproust.sprout.ui.common.label
import com.gproust.sprout.ui.common.moodEmoji

private const val MOTHER_QUESTIONS = 5

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

    Surface(Modifier.fillMaxSize()) {
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
    var step by remember { mutableIntStateOf(0) }
    var mood by remember { mutableIntStateOf(3) }
    var recovery by remember { mutableStateOf<Recovery?>(null) }
    var breast by remember { mutableStateOf<BreastState?>(null) }
    var bleeding by remember { mutableStateOf<Bleeding?>(null) }
    var notes by remember { mutableStateOf("") }

    fun save() {
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
    }

    when (step) {
        0 -> IntroPage(greeting = greeting, onSkip = onSkip, onBegin = { step = 1 })
        1 -> QuestionPage(
            step = 1,
            title = "How are you feeling today?",
            onSkip = onSkip,
            onBack = { step = 0 },
            onNext = { step = 2 },
        ) {
            ChoiceChips(
                options = listOf(1, 2, 3, 4, 5),
                selected = mood,
                onSelect = { mood = it },
                labelOf = { "${moodEmoji(it)} $it" },
            )
        }
        2 -> QuestionPage(
            step = 2,
            title = "How is your healing coming along?",
            onSkip = onSkip,
            onBack = { step = 1 },
            onNext = { step = 3 },
        ) {
            ChoiceChips(
                options = Recovery.entries,
                selected = recovery,
                onSelect = { recovery = if (recovery == it) null else it },
                labelOf = { it.label() },
            )
        }
        3 -> QuestionPage(
            step = 3,
            title = "How do your breasts feel?",
            onSkip = onSkip,
            onBack = { step = 2 },
            onNext = { step = 4 },
        ) {
            ChoiceChips(
                options = BreastState.entries,
                selected = breast,
                onSelect = { breast = if (breast == it) null else it },
                labelOf = { it.label() },
            )
        }
        4 -> QuestionPage(
            step = 4,
            title = "Any bleeding today?",
            onSkip = onSkip,
            onBack = { step = 3 },
            onNext = { step = 5 },
        ) {
            ChoiceChips(
                options = Bleeding.entries,
                selected = bleeding,
                onSelect = { bleeding = if (bleeding == it) null else it },
                labelOf = { it.label() },
            )
        }
        else -> QuestionPage(
            step = 5,
            title = "Anything you'd like to note?",
            onSkip = onSkip,
            onBack = { step = 4 },
            onNext = { save() },
            nextLabel = "Save today's check-in",
        ) {
            NotesField(value = notes, onChange = { notes = it })
        }
    }
}

@Composable
private fun IntroPage(greeting: String, onSkip: () -> Unit, onBegin: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SkipRow(onSkip)
        Spacer(Modifier.height(32.dp))
        Text("$greeting 🌸", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Let's take a moment for you — just a few quick questions, one at a time.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onBegin, modifier = Modifier.fillMaxWidth()) { Text("Begin") }
    }
}

@Composable
private fun QuestionPage(
    step: Int,
    title: String,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String = "Next",
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SkipRow(onSkip)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { step / MOTHER_QUESTIONS.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Question $step of $MOTHER_QUESTIONS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        content()
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text(nextLabel) }
        }
    }
}

@Composable
private fun SkipRow(onSkip: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onSkip) { Text("Not now") }
    }
}

@Composable
private fun CoParentCheckIn(
    greeting: String,
    babyName: String?,
    onContinue: () -> Unit,
) {
    val who = babyName?.takeIf { it.isNotBlank() } ?: "your little one"
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$greeting 👋", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Hope you got some rest. Ready to look after $who today?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Let's go") }
    }
}
