package tech.miyukivigil.totem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.OtpType

/** Shows what a scanned QR code contains before anything is saved. */
@Composable
fun ConfirmImportScreen(
    found: ScanResult,
    existing: List<Account>,
    onSave: (List<Account>) -> Unit,
    onScanAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val (duplicates, fresh) = found.accounts.partition { a -> existing.any { it.sameKeyAs(a) } }
    val multiPart = found.batchSize > 1

    Scaffold(topBar = { TotemTopBar(if (found.accounts.size == 1) "Add account" else "Import accounts", onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (multiPart) {
                item {
                    Text(
                        "Google Authenticator export, QR code ${found.batchIndex + 1} of ${found.batchSize}. " +
                            "Save these, then scan the next code.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(fresh, key = { it.id }) { AccountSummary(it, note = null) }
            items(duplicates, key = { it.id }) { AccountSummary(it, note = "Already in Totem, will be skipped") }
            items(found.skipped) { reason ->
                Card(Modifier.fillMaxWidth()) { Text("Can't import: $reason", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
            item {
                Text(
                    "New accounts start with \"PC asks you first\". You can change that per account later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { onSave(fresh) }, enabled = fresh.isNotEmpty()) {
                        Text(if (fresh.size == 1) "Save" else "Save ${fresh.size} accounts")
                    }
                    OutlinedButton(onClick = onScanAgain) { Text("Scan another") }
                }
            }
        }
    }
}

@Composable
private fun AccountSummary(account: Account, note: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(account.displayName, style = MaterialTheme.typography.titleMedium)
            val kind = when (account.type) {
                OtpType.TOTP -> "Time-based, ${account.digits} digits, every ${account.period}s"
                OtpType.HOTP -> "Counter-based, ${account.digits} digits"
            }
            Text("$kind · ${account.algorithm}", style = MaterialTheme.typography.bodySmall)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
