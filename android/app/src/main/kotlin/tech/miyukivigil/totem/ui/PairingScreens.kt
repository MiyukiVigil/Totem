package tech.miyukivigil.totem.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.miyukivigil.totem.TotemApp
import tech.miyukivigil.totem.device.PairedPc
import tech.miyukivigil.totem.relay.Pairing
import tech.miyukivigil.totem.relay.PendingPairing
import tech.miyukivigil.totem.relay.RelaySync
import java.text.DateFormat
import java.util.Date

private sealed interface PairingState {
    data class Working(val text: String) : PairingState
    class Confirm(val pending: PendingPairing) : PairingState
    data class Finished(val text: String, val ok: Boolean) : PairingState
}

/** Runs pairing for a scanned `totem://pair?...` code and asks the user to compare codes. */
@Composable
fun PairingScreen(app: TotemApp, uri: String, onDone: () -> Unit) {
    var state by remember { mutableStateOf<PairingState>(PairingState.Working("Contacting your PC…")) }
    val scope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(uri) {
        // Needed for Approve / Deny prompts; asking here, when it's obviously relevant.
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        state = try {
            PairingState.Confirm(withContext(Dispatchers.IO) { Pairing.start(app, uri) })
        } catch (e: Exception) {
            PairingState.Finished("Pairing failed: ${e.message}", ok = false)
        }
    }
    // Leaving without answering counts as "no".
    DisposableEffect(Unit) {
        onDispose {
            (state as? PairingState.Confirm)?.let { pending ->
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                GlobalScope.launch(Dispatchers.IO) { runCatching { pending.pending.reject() } }
            }
        }
    }

    fun answer(pending: PendingPairing, accept: Boolean) {
        state = PairingState.Working(if (accept) "Saving…" else "Cancelling…")
        scope.launch {
            state = try {
                withContext(Dispatchers.IO) { if (accept) pending.confirm() else pending.reject() }
                if (accept) {
                    PairingState.Finished("Paired with ${pending.pc.name}. Try `totem list` on the PC.", ok = true)
                } else {
                    PairingState.Finished("Pairing cancelled.", ok = false)
                }
            } catch (e: Exception) {
                PairingState.Finished("Pairing failed: ${e.message}", ok = false)
            }
        }
    }

    Scaffold(topBar = { TotemTopBar("Pair with a PC", onBack = onDone) }) { padding ->
        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (val s = state) {
                is PairingState.Working -> {
                    CircularProgressIndicator()
                    Text(s.text)
                }
                is PairingState.Confirm -> {
                    Text("Does ${s.pending.pc.name} show this code?", style = MaterialTheme.typography.titleLarge)
                    Text(formatCode(s.pending.confirmationCode), fontFamily = FontFamily.Monospace, fontSize = 44.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Only confirm if the code on the PC is exactly the same. If it's different, someone may be " +
                            "trying to pair their computer instead of yours.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { answer(s.pending, true) }) { Text("Codes match") }
                        OutlinedButton(onClick = { answer(s.pending, false) }) { Text("They don't") }
                    }
                }
                is PairingState.Finished -> {
                    Text(s.text, style = MaterialTheme.typography.titleMedium, color = if (s.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    if (s.ok) {
                        Text(
                            "New accounts start as \"asks you first\": your phone will show Approve / Deny. " +
                                "Switch low-risk accounts to \"send automatically\" in each account's settings.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(onClick = onDone) { Text("Done") }
                }
            }
        }
    }
}

/** Settings section: paired PCs, with remove, plus the button to pair a new one. */
@Composable
fun PairedPcsSection(app: TotemApp, onPair: () -> Unit) {
    var pcs by remember { mutableStateOf<List<PairedPc>?>(null) }
    var removing by remember { mutableStateOf<PairedPc?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { pcs = withContext(Dispatchers.IO) { app.devices.get().pcs } }

    Text("Paired PCs", style = MaterialTheme.typography.titleMedium)
    when (val list = pcs) {
        null -> CircularProgressIndicator()
        else -> {
            if (list.isEmpty()) Text("None yet. On your PC run `totem pair`, then tap below.", style = MaterialTheme.typography.bodyMedium)
            list.forEach { pc ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(pc.name, style = MaterialTheme.typography.titleSmall)
                            Text("Paired ${DateFormat.getDateInstance().format(Date(pc.pairedAt * 1000))}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { removing = pc }) { Text("Remove") }
                    }
                }
            }
        }
    }
    Button(onClick = onPair) { Text("Pair with a PC") }

    removing?.let { pc ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${pc.name}?") },
            text = { Text("It won't be able to get codes any more. To use it again you'll need to pair again.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        pcs = withContext(Dispatchers.IO) {
                            app.devices.removePc(pc.id)
                            RelaySync.syncAll(app)
                            app.devices.get().pcs
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
