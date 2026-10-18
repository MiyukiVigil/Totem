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
import androidx.compose.runtime.collectAsState
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
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.Backup
import tech.miyukivigil.totem.core.ForeignImport
import tech.miyukivigil.totem.vault.Vault
import java.time.LocalDate

@Composable
fun SettingsScreen(app: TotemApp, onPair: () -> Unit, onActivity: () -> Unit, onBack: () -> Unit) {
    val vault = app.vault
    val seal by vault.seal.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var askExportPassword by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    /** A picked file waiting for its password: Totem backup (detected = null), Aegis or 2FAS. */
    var importFile by remember { mutableStateOf<Pair<ByteArray, ForeignImport.Detected?>?>(null) }

    fun finishImport(restored: List<Account>, skipped: List<String>): String {
        val fresh = restored.filter { r -> vault.accounts.none { it.sameKeyAs(r) } }
        vault.add(fresh)
        val dupes = restored.size - fresh.size
        return "Imported ${accountsText(fresh.size)}" +
            (if (dupes > 0) " (${accountsText(dupes)} ${if (dupes == 1) "was" else "were"} already in Totem)" else "") +
            (if (skipped.isNotEmpty()) ". Not supported, so skipped: ${skipped.joinToString()}" else "") + "."
    }

    fun decrypt(bytes: ByteArray, detected: ForeignImport.Detected?, password: CharArray?) {
        busy = "Decrypting…"
        scope.launch {
            result = try {
                withContext(Dispatchers.Default) {
                    if (detected == null) finishImport(Backup.decrypt(bytes, password!!), emptyList())
                    else ForeignImport.read(detected, password).let { finishImport(it.accounts, it.skipped) }
                }
            } catch (e: Backup.WrongPasswordOrCorrupted) {
                "Wrong password, or the file is damaged."
            } catch (e: ForeignImport.WrongPassword) {
                "Wrong password."
            } catch (e: Backup.NotABackup) {
                "That isn't a Totem, Aegis or 2FAS backup file."
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            } finally {
                password?.fill(' ')
                busy = null
            }
        }
    }

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
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            } catch (e: Exception) {
                result = "Couldn't read that file: ${e.message}"
                return@launch
            }
            val detected = ForeignImport.detect(bytes)
            if (detected != null && !detected.needsPassword) decrypt(bytes, detected, null) else importFile = bytes to detected
        }
    }

    Scaffold(topBar = { TotemTopBar("Settings", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PairedPcsSection(app, onPair, onActivity)

            HorizontalDivider()
            SealSection(app)

            HorizontalDivider()
            Text("Backups", style = MaterialTheme.typography.titleMedium)
            Text(
                "A backup is a password-protected file with all your accounts. Store it somewhere other than this phone. " +
                    "Totem never uploads it anywhere.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (seal == Vault.Seal.Locked || seal is Vault.Seal.Broken) {
                Text(
                    "Phone-only accounts with the extra lock aren't open right now, so a backup wouldn't include them. Lock and unlock Totem first.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = { askExportPassword = true }, enabled = busy == null && vault.accounts.isNotEmpty()) { Text("Export encrypted backup") }
            OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, enabled = busy == null) { Text("Import backup") }
            Text("Totem backups, and exports from Aegis or 2FAS.", style = MaterialTheme.typography.bodySmall)
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
    importFile?.let { (bytes, detected) ->
        PasswordDialog(
            title = if (detected == null) "Backup password" else "${detected.format.app} export password",
            confirm = false,
            onDismiss = { importFile = null },
            onDone = { password ->
                importFile = null
                decrypt(bytes, detected, password)
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
