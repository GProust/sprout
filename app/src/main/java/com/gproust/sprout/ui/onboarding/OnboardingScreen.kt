package com.gproust.sprout.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gproust.sprout.data.local.ParentRole
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.label

@Composable
fun OnboardingScreen(
    onFinish: (name: String, role: ParentRole, babyName: String, birthDate: Long) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf<ParentRole?>(null) }
    var babyName by remember { mutableStateOf("") }
    var birthDate by remember { mutableLongStateOf(System.currentTimeMillis()) }

    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            when (step) {
                0 -> WelcomeStep(onNext = { step = 1 })
                1 -> AboutYouStep(
                    name = name,
                    onName = { name = it },
                    role = role,
                    onRole = { role = it },
                    onBack = { step = 0 },
                    onNext = { step = 2 },
                )
                else -> BabyStep(
                    babyName = babyName,
                    onBabyName = { babyName = it },
                    birthDate = birthDate,
                    onBirthDate = { birthDate = it },
                    onBack = { step = 1 },
                    onFinish = { onFinish(name, role ?: ParentRole.CO_PARENT, babyName, birthDate) },
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Text("🌱", style = MaterialTheme.typography.displayLarge)
    Spacer(Modifier.height(16.dp))
    Text(
        "Welcome to Sprout",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "A gentle space to track your little one's first weeks — and to look after you, too.\n\nLet's set things up. It'll only take a moment.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
}

@Composable
private fun AboutYouStep(
    name: String,
    onName: (String) -> Unit,
    role: ParentRole?,
    onRole: (ParentRole) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Text(
        "A little about you",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "So we can greet you properly each day.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text("Your name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldLabel("Which best describes you?")
    ChoiceChips(
        options = ParentRole.entries,
        selected = role,
        onSelect = onRole,
        labelOf = { it.label() },
    )
    Spacer(Modifier.height(32.dp))
    StepButtons(
        onBack = onBack,
        nextLabel = "Next",
        nextEnabled = name.isNotBlank() && role != null,
        onNext = onNext,
    )
}

@Composable
private fun BabyStep(
    babyName: String,
    onBabyName: (String) -> Unit,
    birthDate: Long,
    onBirthDate: (Long) -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    Text(
        "Your little one",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "You can always change this later in the profile.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = babyName,
        onValueChange = onBabyName,
        label = { Text("Baby's name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldLabel("Date of birth")
    DatePickerField(label = "Born", millis = birthDate, onChange = onBirthDate)
    Spacer(Modifier.height(32.dp))
    StepButtons(
        onBack = onBack,
        nextLabel = "All done",
        nextEnabled = true,
        onNext = onFinish,
    )
}

@Composable
private fun StepButtons(
    onBack: () -> Unit,
    nextLabel: String,
    nextEnabled: Boolean,
    onNext: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
        Button(onClick = onNext, enabled = nextEnabled, modifier = Modifier.weight(1f)) {
            Text(nextLabel)
        }
    }
}
