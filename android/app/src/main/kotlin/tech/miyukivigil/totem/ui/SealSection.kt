package tech.miyukivigil.totem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.miyukivigil.totem.TotemApp
import tech.miyukivigil.totem.vault.Vault

/** Settings: the extra lock for phone-only accounts (see [Vault]). */
@Composable
fun SealSection(app: TotemApp) {
    val seal by app.vault.seal.collectAsState()
    var confirmOn by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun run(block: Vault.() -> Unit, done: String) = scope.launch {
        message = try {
            withContext(Dispatchers.IO) { app.vault.block() }
            done
        } catch (e: Exception) {
            if (e is android.security.keystore.UserNotAuthenticatedException || e is IllegalStateException) {
                "Lock and unlock Totem, then try again within ${Vault.SEAL_UNLOCK_SECS / 60} minutes."
            } else {
                "That didn't work: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    Text("Phone-only accounts", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Extra lock", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = seal != Vault.Seal.Off,
            onCheckedChange = { on ->
                if (on) confirmOn = true else run({ disableSeal() }, "Extra lock is off.")
            },
        )
    }
    Text(
        "Keeps accounts set to \"Phone only\" under a second key that only works for ${Vault.SEAL_UNLOCK_SECS / 60} minutes " +
            "after you unlock. Even if something took over Totem in the background, it couldn't read them. " +
            "They're also hidden from your PC's list.",
        style = MaterialTheme.typography.bodyMedium,
    )
    when (val s = seal) {
        is Vault.Seal.Broken -> Text(
            "Your phone-only accounts can't be opened: ${s.reason}. Import a backup to get them back, " +
                "then turn the extra lock off and on again.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
        )
        Vault.Seal.Locked -> Text("Locked. Lock and unlock Totem to open them.", style = MaterialTheme.typography.bodySmall)
        else -> Unit
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

    if (confirmOn) {
        AlertDialog(
            onDismissRequest = { confirmOn = false },
            title = { Text("Turn on the extra lock?") },
            text = {
                Text(
                    "If you ever remove your phone's screen lock, Android deletes this key and your phone-only accounts with it. " +
                        "Changing your PIN or adding a fingerprint is fine.\n\nExport a backup first so you can always restore them.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOn = false
                    run({ enableSeal() }, "Extra lock is on.")
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { confirmOn = false }) { Text("Cancel") } },
        )
    }
}
