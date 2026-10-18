package tech.miyukivigil.totem.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.miyukivigil.totem.TotemApp
import tech.miyukivigil.totem.core.ActivityEntry
import tech.miyukivigil.totem.core.ActivityKind
import tech.miyukivigil.totem.core.PcExpiry
import tech.miyukivigil.totem.core.RequestGuard
import tech.miyukivigil.totem.device.PairedPc
import tech.miyukivigil.totem.relay.RelaySync
import java.text.DateFormat
import java.util.Date

/** Settings section: paired PCs (rename, remove, auto-forget), activity, and pairing a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PairedPcsSection(app: TotemApp, onPair: () -> Unit, onActivity: () -> Unit) {
    var pcs by remember { mutableStateOf<List<PairedPc>?>(null) }
    var forgetAfter by remember { mutableStateOf(PcExpiry.DEFAULT_DAYS) }
    var removing by remember { mutableStateOf<PairedPc?>(null) }
    var renaming by remember { mutableStateOf<PairedPc?>(null) }
    val scope = rememberCoroutineScope()

    fun reload(change: () -> Unit = {}) = scope.launch {
        val d = withContext(Dispatchers.IO) {
            change()
            app.devices.get()
        }
        pcs = d.pcs
        forgetAfter = d.forgetAfterDays
    }
    LaunchedEffect(Unit) {
        reload {
            if (RelaySync.forgetExpired(app).isNotEmpty()) RelaySync.syncAll(app)
        }
    }

    Text("Paired PCs", style = MaterialTheme.typography.titleMedium)
    when (val list = pcs) {
        null -> CircularProgressIndicator()
        else -> {
            if (list.isEmpty()) Text("None yet. Open Totem on your PC, then tap below.", style = MaterialTheme.typography.bodyMedium)
            list.forEach { pc ->
                Card(Modifier.fillMaxWidth().clickable { renaming = pc }) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(pc.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Paired ${day(pc.pairedAt)} · " + if (pc.lastUsedAt > 0) "last used ${day(pc.lastUsedAt)}" else "not used yet",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { removing = pc }) { Text("Remove") }
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPair) { Text("Pair with a PC") }
        OutlinedButton(onClick = onActivity) { Text("Activity") }
    }

    Text("Forget a PC that hasn't asked for a code in", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PcExpiry.CHOICES_DAYS.forEach { days ->
            FilterChip(
                selected = forgetAfter == days,
                onClick = { reload { app.devices.setForgetAfterDays(days) } },
                label = { Text(if (days == 0) "Never" else if (days == 365) "1 year" else "$days days") },
            )
        }
    }

    renaming?.let { pc ->
        var name by remember(pc.id) { mutableStateOf(pc.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename PC") },
            text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = null
                    reload { app.devices.renamePc(pc.id, name.trim()) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    removing?.let { pc ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${pc.name}?") },
            text = { Text("It won't be able to get codes any more. To use it again you'll need to pair again.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    reload {
                        app.devices.removePc(pc.id)
                        RelaySync.syncAll(app)
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** Everything paired PCs did, newest first. */
@Composable
fun ActivityScreen(app: TotemApp, onBack: () -> Unit) {
    var entries by remember { mutableStateOf<List<ActivityEntry>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { entries = withContext(Dispatchers.IO) { app.activity.entries().asReversed() } }

    Scaffold(topBar = { TotemTopBar("Activity", onBack = onBack) }) { padding ->
        when (val list = entries) {
            null -> Centered { CircularProgressIndicator() }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Text(
                        "What your paired PCs asked for. Kept on this phone only (the last ${tech.miyukivigil.totem.core.ActivityCodec.MAX_ENTRIES} events).",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                if (list.isEmpty()) item { Text("Nothing yet.", modifier = Modifier.padding(vertical = 8.dp)) }
                items(list) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(describe(e), style = MaterialTheme.typography.bodyLarge, color = if (e.kind == ActivityKind.ALERT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text("${e.pcName} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.at * 1000))}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
                if (list.isNotEmpty()) item {
                    TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(vertical = 8.dp)) { Text("Clear activity") }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear activity?") },
            text = { Text("This also resets the protection that pauses a PC asking for too many codes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { app.activity.clear() }
                        entries = emptyList()
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun day(unixSecs: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unixSecs * 1000))

private fun describe(e: ActivityEntry): String = when (e.kind) {
    ActivityKind.LIST -> "Looked up your account names"
    ActivityKind.CODE -> "Got a code for ${e.account}"
    ActivityKind.DENIED -> "Didn't get a code for ${e.account ?: "an unknown account"}: " + when (e.detail) {
        "user_denied" -> "you denied it"
        "approval_timeout" -> "nobody approved it"
        "policy_never" -> "set to phone-only"
        "paused" -> "PC was paused"
        "unknown_account" -> "no such account"
        else -> e.detail ?: "refused"
    }
    ActivityKind.ALERT -> when (e.detail) {
        RequestGuard.PAUSED -> "Paused after too many code requests"
        "wrong_key" -> "Someone posed as this PC with the wrong key"
        else -> "Warning: ${e.detail}"
    }
}
