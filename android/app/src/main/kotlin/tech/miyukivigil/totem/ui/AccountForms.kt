package tech.miyukivigil.totem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.Base32
import tech.miyukivigil.totem.core.OtpAlgorithm
import tech.miyukivigil.totem.core.OtpType
import tech.miyukivigil.totem.core.RemotePolicy

@Composable
fun AddManualScreen(onSave: (Account) -> Unit, onBack: () -> Unit) {
    var issuer by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(OtpType.TOTP) }
    var algorithm by remember { mutableStateOf(OtpAlgorithm.SHA1) }
    var digits by remember { mutableStateOf(6) }
    var period by remember { mutableStateOf("30") }
    var counter by remember { mutableStateOf("0") }
    var error by remember { mutableStateOf<String?>(null) }

    fun build(): Account {
        require(issuer.isNotBlank() || label.isNotBlank()) { "Enter a service name or account name." }
        val key = try {
            Base32.decode(secret)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The key can only contain letters A–Z and digits 2–7.")
        }
        require(key.size >= 10) { "That key is too short. Setup keys are usually 16 or 32 characters." }
        return Account(
            issuer = issuer.trim(),
            label = label.trim(),
            secret = key,
            type = type,
            algorithm = algorithm,
            digits = digits,
            period = period.toIntOrNull()?.takeIf { it in 1..600 } ?: throw IllegalArgumentException("Period must be 1–600 seconds."),
            counter = if (type == OtpType.HOTP) counter.toLongOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Counter must be a whole number.") else 0,
        )
    }

    Scaffold(topBar = { TotemTopBar("Enter key manually", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(issuer, { issuer = it }, label = { Text("Service (e.g. GitHub)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(label, { label = it }, label = { Text("Account (e.g. your email)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                secret, { secret = it.uppercase() },
                label = { Text("Setup key") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced options" else "Advanced options") }
            if (advanced) {
                ChipRow("Type", OtpType.entries, type, { if (it == OtpType.TOTP) "Time-based" else "Counter-based" }) { type = it }
                ChipRow("Algorithm", OtpAlgorithm.entries, algorithm, { it.name }) { algorithm = it }
                ChipRow("Digits", listOf(6, 8), digits, { "$it" }) { digits = it }
                if (type == OtpType.TOTP) {
                    OutlinedTextField(period, { period = it }, label = { Text("Period (seconds)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                } else {
                    OutlinedTextField(counter, { counter = it }, label = { Text("Counter") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                try {
                    onSave(build())
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            }) { Text("Save") }
        }
    }
}

@Composable
fun EditAccountScreen(account: Account, onSave: (Account) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    var issuer by remember { mutableStateOf(account.issuer) }
    var label by remember { mutableStateOf(account.label) }
    var policy by remember { mutableStateOf(account.policy) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(topBar = { TotemTopBar("Edit account", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(issuer, { issuer = it }, label = { Text("Service") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(label, { label = it }, label = { Text("Account") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("When your PC asks for this code", style = MaterialTheme.typography.titleSmall)
            ChipRow(null, RemotePolicy.entries, policy, {
                when (it) {
                    RemotePolicy.AUTO -> "Send automatically"
                    RemotePolicy.ASK -> "Ask me first"
                    RemotePolicy.NEVER -> "Never"
                }
            }) { policy = it }
            Text(
                when (policy) {
                    RemotePolicy.AUTO -> "Fine for low-risk accounts. You still get a notification each time."
                    RemotePolicy.ASK -> "Recommended for email, banking and password managers: approve each request on the phone or watch."
                    RemotePolicy.NEVER -> "Only shown on this phone."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text(
                "${if (account.type == OtpType.TOTP) "Time-based, every ${account.period}s" else "Counter-based, next counter ${account.counter}"} · " +
                    "${account.digits} digits · ${account.algorithm}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onSave(account.copy(issuer = issuer.trim(), label = label.trim(), policy = policy)) },
                    enabled = issuer.isNotBlank() || label.isNotBlank(),
                ) { Text("Save") }
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${account.displayName}?") },
            text = { Text("Make sure you've turned off 2FA for this account or have another way in. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(title: String?, options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { FilterChip(selected = it == selected, onClick = { onSelect(it) }, label = { Text(name(it)) }) }
        }
    }
}
