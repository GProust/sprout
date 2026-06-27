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
import com.gproust.sprout.data.local.DeliveryType
import com.gproust.sprout.data.local.Recovery
import com.gproust.sprout.data.local.WellbeingEntity
import com.gproust.sprout.ui.common.CheckInQuestion
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.checkInQuestions
import com.gproust.sprout.ui.common.greetingFor
import com.gproust.sprout.ui.common.healingQuestion
import com.gproust.sprout.ui.common.label
import com.gproust.sprout.ui.common.moodEmoji

@Composable
fun DailyCheckInScreen(
    name: String,
    gaveBirth: Boolean,
    breastfeeding: Boolean,
    deliveryType: DeliveryType?,
    onSubmit: (WellbeingEntity) -> Unit,
    onSkip: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val greeting = "${greetingFor(now)}, $name"
    val questions = remember(gaveBirth, breastfeeding) { checkInQuestions(gaveBirth, breastfeeding) }

    Surface(Modifier.fillMaxSize()) {
        CheckInFlow(
            greeting = greeting,
            now = now,
            questions = questions,
            deliveryType = deliveryType,
            onSubmit = onSubmit,
            onSkip = onSkip,
        )
    }
}

@Composable
private fun CheckInFlow(
    greeting: String,
    now: Long,
    questions: List<CheckInQuestion>,
    deliveryType: DeliveryType?,
    onSubmit: (WellbeingEntity) -> Unit,
    onSkip: () -> Unit,
) {
    // step 0 = intro, steps 1..N = the questions.
    var step by remember { mutableIntStateOf(0) }
    var mood by remember { mutableIntStateOf(3) }
    var recovery by remember { mutableStateOf<Recovery?>(null) }
    var bleeding by remember { mutableStateOf<Bleeding?>(null) }
    var breast by remember { mutableStateOf<BreastState?>(null) }
    var notes by remember { mutableStateOf("") }

    fun save() {
        onSubmit(
            WellbeingEntity(
                time = now,
                mood = mood,
                bleeding = bleeding,
                recovery = recovery,
                breast = breast,
                notes = notes.ifBlank { null },
            ),
        )
    }

    val total = questions.size
    if (step == 0) {
        IntroPage(greeting = greeting, onSkip = onSkip, onBegin = { step = 1 })
        return
    }

    val question = questions[step - 1]
    val isLast = step == total
    QuestionPage(
        step = step,
        total = total,
        title = title(question, deliveryType),
        onSkip = onSkip,
        onBack = { step -= 1 },
        onNext = { if (isLast) save() else step += 1 },
        nextLabel = if (isLast) "Save today's check-in" else "Next",
    ) {
        when (question) {
            CheckInQuestion.MOOD -> ChoiceChips(
                options = listOf(1, 2, 3, 4, 5),
                selected = mood,
                onSelect = { mood = it },
                labelOf = { "${moodEmoji(it)} $it" },
            )
            CheckInQuestion.HEALING -> ChoiceChips(
                options = Recovery.entries,
                selected = recovery,
                onSelect = { recovery = if (recovery == it) null else it },
                labelOf = { it.label() },
            )
            CheckInQuestion.BLEEDING -> ChoiceChips(
                options = Bleeding.entries,
                selected = bleeding,
                onSelect = { bleeding = if (bleeding == it) null else it },
                labelOf = { it.label() },
            )
            CheckInQuestion.BREASTS -> ChoiceChips(
                options = BreastState.entries,
                selected = breast,
                onSelect = { breast = if (breast == it) null else it },
                labelOf = { it.label() },
            )
            CheckInQuestion.NOTES -> NotesField(value = notes, onChange = { notes = it })
        }
    }
}

private fun title(question: CheckInQuestion, deliveryType: DeliveryType?): String = when (question) {
    CheckInQuestion.MOOD -> "How are you feeling today?"
    CheckInQuestion.HEALING -> healingQuestion(deliveryType)
    CheckInQuestion.BLEEDING -> "Any bleeding today?"
    CheckInQuestion.BREASTS -> "How do your breasts feel?"
    CheckInQuestion.NOTES -> "Anything you'd like to note?"
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
    total: Int,
    title: String,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String,
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
            progress = { step / total.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Question $step of $total",
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
