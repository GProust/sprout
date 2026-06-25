package com.gproust.sprout.ui.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.babyAge
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(private val repository: SproutRepository) : ViewModel() {
    val baby = repository.baby.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun save(name: String, birthDate: Long, onSaved: () -> Unit) {
        viewModelScope.launch {
            repository.saveBaby(BabyEntity(id = 1L, name = name.trim(), birthDate = birthDate))
            onSaved()
        }
    }
}

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val vm: ProfileViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val baby by vm.baby.collectAsState()
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var birthDate by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var initialized by remember { mutableStateOf(false) }

    // Pre-fill once the stored profile arrives.
    LaunchedEffect(baby) {
        val current = baby
        if (!initialized && current != null) {
            name = current.name
            birthDate = current.birthDate
            initialized = true
        }
    }

    Scaffold(topBar = { SproutTopBar("Baby profile", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Baby's name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            FieldLabel("Date of birth")
            DatePickerField(label = "Born", millis = birthDate, onChange = { birthDate = it })

            Spacer(Modifier.height(8.dp))
            Text("Age: ${babyAge(birthDate, System.currentTimeMillis())}")

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (name.isBlank()) {
                        Toast.makeText(context, "Please enter a name", Toast.LENGTH_SHORT).show()
                    } else {
                        vm.save(name, birthDate) {
                            Toast.makeText(context, "Profile saved", Toast.LENGTH_SHORT).show()
                            onBack()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}
