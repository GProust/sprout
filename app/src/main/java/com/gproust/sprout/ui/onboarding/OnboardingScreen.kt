package com.gproust.sprout.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.data.local.DeliveryType
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.label

@Composable
fun OnboardingScreen(
    onFinish: (
        name: String,
        gaveBirth: Boolean,
        breastfeeding: Boolean,
        deliveryType: DeliveryType?,
        babyName: String,
        birthDate: Long,
    ) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var babyName by remember { mutableStateOf("") }
    var birthDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var gaveBirth by remember { mutableStateOf(false) }
    var breastfeeding by remember { mutableStateOf(false) }
    var deliveryType by remember { mutableStateOf<DeliveryType?>(null) }

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
                    onBack = { step = 0 },
                    onNext = { step = 2 },
                )
                2 -> BabyStep(
                    babyName = babyName,
                    onBabyName = { babyName = it },
                    birthDate = birthDate,
                    onBirthDate = { birthDate = it },
                    onBack = { step = 1 },
                    onNext = { step = 3 },
                )
                else -> CareStep(
                    babyName = babyName,
                    gaveBirth = gaveBirth,
                    onGaveBirth = { gaveBirth = it },
                    deliveryType = deliveryType,
                    onDeliveryType = { deliveryType = it },
                    breastfeeding = breastfeeding,
                    onBreastfeeding = { breastfeeding = it },
                    onBack = { step = 2 },
                    onFinish = {
                        onFinish(
                            name,
                            gaveBirth,
                            breastfeeding,
                            if (gaveBirth) deliveryType else null,
                            babyName,
                            birthDate,
                        )
                    },
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
        stringResource(R.string.onboarding_welcome_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.onboarding_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_get_started))
    }
}

@Composable
private fun AboutYouStep(
    name: String,
    onName: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Text(
        stringResource(R.string.onboarding_about_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.onboarding_about_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text(stringResource(R.string.onboarding_your_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    StepButtons(
        onBack = onBack,
        nextLabel = stringResource(R.string.action_next),
        nextEnabled = name.isNotBlank(),
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
    onNext: () -> Unit,
) {
    Text(
        stringResource(R.string.onboarding_baby_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.onboarding_baby_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = babyName,
        onValueChange = onBabyName,
        label = { Text(stringResource(R.string.field_baby_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldLabel(stringResource(R.string.field_date_of_birth))
    DatePickerField(label = stringResource(R.string.picker_born), millis = birthDate, onChange = onBirthDate)
    Spacer(Modifier.height(32.dp))
    StepButtons(
        onBack = onBack,
        nextLabel = stringResource(R.string.action_next),
        nextEnabled = true,
        onNext = onNext,
    )
}

@Composable
private fun CareStep(
    babyName: String,
    gaveBirth: Boolean,
    onGaveBirth: (Boolean) -> Unit,
    deliveryType: DeliveryType?,
    onDeliveryType: (DeliveryType?) -> Unit,
    breastfeeding: Boolean,
    onBreastfeeding: (Boolean) -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val defaultBaby = stringResource(R.string.onboarding_default_baby)
    val who = babyName.trim().ifBlank { defaultBaby }
    Text(
        stringResource(R.string.onboarding_care_title, who),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.onboarding_care_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    ToggleRow(
        label = stringResource(R.string.onboarding_gave_birth_q, who),
        help = stringResource(R.string.onboarding_gave_birth_help),
        checked = gaveBirth,
        onChecked = onGaveBirth,
    )
    if (gaveBirth) {
        FieldLabel(stringResource(R.string.onboarding_birth_how))
        ChoiceChips(
            options = DeliveryType.entries,
            selected = deliveryType,
            onSelect = { onDeliveryType(if (deliveryType == it) null else it) },
            labelOf = { it.label(context) },
        )
        Spacer(Modifier.height(8.dp))
    }
    ToggleRow(
        label = stringResource(R.string.onboarding_breastfeeding_q, who),
        help = stringResource(R.string.onboarding_breastfeeding_help),
        checked = breastfeeding,
        onChecked = onBreastfeeding,
    )
    Spacer(Modifier.height(32.dp))
    StepButtons(
        onBack = onBack,
        nextLabel = stringResource(R.string.onboarding_all_done),
        nextEnabled = true,
        onNext = onFinish,
    )
}

@Composable
private fun ToggleRow(
    label: String,
    help: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun StepButtons(
    onBack: () -> Unit,
    nextLabel: String,
    nextEnabled: Boolean,
    onNext: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_back))
        }
        Button(onClick = onNext, enabled = nextEnabled, modifier = Modifier.weight(1f)) {
            Text(nextLabel)
        }
    }
}
