package tech.miyukivigil.totem.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.Otp
import tech.miyukivigil.totem.core.OtpType
import tech.miyukivigil.totem.core.RemotePolicy

@Composable
fun AccountListScreen(
    accounts: List<Account>,
    showNoLockWarning: Boolean,
    onScan: () -> Unit,
    onAddManually: () -> Unit,
    onEdit: (Account) -> Unit,
    onSettings: () -> Unit,
    onHotpUsed: (Account) -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var addMenu by remember { mutableStateOf(false) }
    val now = rememberNowMillis()
    val shown = if (query.isBlank()) accounts else accounts.filter { it.displayName.contains(query.trim(), ignoreCase = true) }

    Scaffold(
        topBar = {
            TotemTopBar("Totem") {
                IconButton(onClick = { searching = !searching; query = "" }) {
                    Icon(if (searching) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search")
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add") },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Scan QR code") }, onClick = { addMenu = false; onScan() })
                    DropdownMenuItem(text = { Text("Enter key manually") }, onClick = { addMenu = false; onAddManually() })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showNoLockWarning) {
                Banner("This phone has no screen lock, so Totem can't lock itself. Set a PIN or fingerprint in Android settings.")
            }
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search accounts") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (accounts.isEmpty()) {
                Centered {
                    Text("No accounts yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap Add to scan a 2FA QR code, or import from Google Authenticator (Transfer accounts → Export) by scanning its QR code.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { account ->
                        AccountRow(account, now, onEdit = { onEdit(account) }, onHotpUsed = { onHotpUsed(account) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountRow(account: Account, nowMillis: Long, onEdit: () -> Unit, onHotpUsed: () -> Unit) {
    val context = LocalContext.current
    val seconds = nowMillis / 1000
    // HOTP codes stay hidden until asked for: showing one doesn't use it up, but generating
    // the next one does, so it's an explicit tap.
    var hotpShown by remember(account.id) { mutableStateOf(false) }
    val code = when (account.type) {
        OtpType.TOTP -> account.code(seconds)
        OtpType.HOTP -> if (hotpShown) account.code(seconds) else null
    }

    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = {
                if (code != null) {
                    copyCode(context, code)
                    Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                }
            },
            onLongClick = onEdit,
        ),
        colors = CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountIcon(account)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    account.issuer.ifBlank { account.label },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (account.issuer.isNotBlank() && account.label.isNotBlank()) {
                    Text(
                        account.label,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    code?.let(::formatCode) ?: "••• •••",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 32.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(policyHint(account.policy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            when (account.type) {
                OtpType.TOTP -> {
                    val remaining = Otp.secondsRemaining(seconds, account.period.toLong())
                    val fraction = ((account.period * 1000 - nowMillis % (account.period * 1000)) / (account.period * 1000f))
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                        CircularProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.size(36.dp),
                            color = if (remaining <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text("$remaining", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OtpType.HOTP -> IconButton(onClick = {
                    if (hotpShown) onHotpUsed() else hotpShown = true
                }) { Icon(Icons.Default.Refresh, contentDescription = if (hotpShown) "Next code" else "Show code") }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
        }
    }
}

private fun policyHint(policy: RemotePolicy) = when (policy) {
    RemotePolicy.AUTO -> "PC: sends automatically"
    RemotePolicy.ASK -> "PC: asks you first"
    RemotePolicy.NEVER -> "PC: never"
}

@Composable
fun Banner(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}
