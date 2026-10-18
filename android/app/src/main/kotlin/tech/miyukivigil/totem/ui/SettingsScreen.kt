package tech.miyukivigil.totem.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.miyukivigil.totem.TotemApp
import tech.miyukivigil.totem.core.Backup
import java.time.LocalDate

@Composable
fun SettingsScreen(app: TotemApp, onPair: () -> Unit, onBack: () -> Unit) {
    val vault = app.vault
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var askExportPassword by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = exportPassword ?: return@rememberLauncherForActivityResult
        exportPassword = null
        if (uri == null) {
            password.fill(' ')
            return@rememberLauncherForActivityResult
        }
        busy = "Encrypting backup…"
        scope.launch {
            result = try {
                val bytes = withContext(Dispatchers.Default) { Backup.encrypt(vault.accounts, password) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                }
                "Backup saved with ${accountsText(vault.accounts.size)}. Keep the password somewhere safe: without it the file can't be opened."
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            } finally {
                password.fill(' ')
                busy = null
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }

    Scaffold(topBar = { TotemTopBar("Settings", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PairedPcsSection(app, onPair)

            HorizontalDivider()
            Text("Backups", style = MaterialTheme.typography.titleMedium)
            Text(
                "A backup is a password-protected file with all your accounts. Store it somewhere other than this phone. " +
                    "Totem never uploads it anywhere.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { askExportPassword = true }, enabled = busy == null && vault.accounts.isNotEmpty()) { Text("Export encrypted backup") }
            OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, enabled = busy == null) { Text("Import backup") }
            busy?.let {
                Text(it)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

            HorizontalDivider()
            Text("Network", style = MaterialTheme.typography.titleMedium)
            Text(
                "Totem only uses the network to talk to your paired PC. If your phone restricts network access for apps, " +
                    "allow Wi-Fi, mobile data and background data for Totem in Android settings, or your PC won't get codes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            Text("Totem ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (askExportPassword) {
        PasswordDialog(
            title = "Backup password",
            confirm = true,
            onDismiss = { askExportPassword = false },
            onDone = { password ->
                askExportPassword = false
                exportPassword = password
                createFile.launch("totem-backup-${LocalDate.now()}.totem")
            },
        )
    }
    importUri?.let { uri ->
        PasswordDialog(
            title = "Backup password",
            confirm = false,
            onDismiss = { importUri = null },
            onDone = { password ->
                importUri = null
                busy = "Decrypting backup…"
                scope.launch {
                    result = try {
                        val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                        val restored = withContext(Dispatchers.Default) { Backup.decrypt(bytes, password) }
                        val fresh = restored.filter { r -> vault.accounts.none { it.sameKeyAs(r) } }
                        withContext(Dispatchers.IO) { vault.add(fresh) }
                        val skipped = restored.size - fresh.size
                        "Imported ${accountsText(fresh.size)}" +
                            if (skipped > 0) " (${accountsText(skipped)} ${if (skipped == 1) "was" else "were"} already in Totem)." else "."
                    } catch (e: Backup.WrongPasswordOrCorrupted) {
                        "Wrong password, or the file is damaged."
                    } catch (e: Exception) {
                        "Import failed: ${e.message}"
                    } finally {
                        password.fill(' ')
                        busy = null
                    }
                }
            },
        )
    }
}

private fun accountsText(n: Int) = if (n == 1) "1 account" else "$n accounts"

@Composable
private fun PasswordDialog(title: String, confirm: Boolean, onDismiss: () -> Unit, onDone: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val tooShort = password.length < Backup.MIN_PASSWORD_LENGTH
    val mismatch = confirm && password != repeat
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirm) {
                    OutlinedTextField(
                        repeat, { repeat = it }, label = { Text("Repeat password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Text(
                        when {
                            tooShort -> "At least ${Backup.MIN_PASSWORD_LENGTH} characters. A few random words works well."
                            mismatch -> "Passwords don't match."
                            else -> "If you forget this password, the backup can't be opened."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(password.toCharArray()) }, enabled = !(confirm && (tooShort || mismatch)) && password.isNotEmpty()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
