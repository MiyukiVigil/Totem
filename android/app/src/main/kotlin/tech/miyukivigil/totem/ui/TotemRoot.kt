package tech.miyukivigil.totem.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.miyukivigil.totem.TotemApp
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.BrandIcons
import tech.miyukivigil.totem.core.PairingQr
import tech.miyukivigil.totem.vault.Vault

enum class LockState { LOCKED, UNLOCKED, NO_DEVICE_LOCK }

/** Where the user is. Kept as plain state: the app is small enough not to need a nav library. */
sealed interface Screen {
    data object List : Screen
    data object Scan : Screen
    data object AddManual : Screen
    data class Confirm(val found: ScanResult) : Screen
    data class Edit(val accountId: String) : Screen
    data object Settings : Screen
    data object Activity : Screen
    data object PairScan : Screen
    data class Pairing(val uri: String) : Screen
}

@Composable
fun TotemTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors) { Surface(Modifier.fillMaxSize(), content = content) }
}

@Composable
fun TotemRoot(app: TotemApp, lockState: LockState, onUnlockRequested: () -> Unit, pairingUri: String?, onPairingUriUsed: () -> Unit) {
    val vault = app.vault
    val brandIcons by produceState<BrandIcons?>(null) { value = withContext(Dispatchers.IO) { app.brandIcons } }
    TotemTheme { CompositionLocalProvider(LocalBrandIcons provides brandIcons) {
        if (lockState == LockState.LOCKED) {
            LockedScreen(onUnlockRequested)
        } else when (val state = vault.state.collectAsState().value) {
            Vault.State.Loading -> Centered { CircularProgressIndicator() }
            is Vault.State.Broken -> BrokenVaultScreen(vault, state.reason)
            is Vault.State.Ready -> MainNavigation(app, state.accounts, lockState == LockState.NO_DEVICE_LOCK, pairingUri, onPairingUriUsed)
        }
    } }
}

@Composable
private fun MainNavigation(
    app: TotemApp,
    accounts: List<Account>,
    showNoLockWarning: Boolean,
    pairingUri: String?,
    onPairingUriUsed: () -> Unit,
) {
    val vault = app.vault
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    // A pairing link opened from outside (debug builds only, see the debug manifest).
    LaunchedEffect(pairingUri) {
        if (pairingUri != null) {
            screen = Screen.Pairing(pairingUri)
            onPairingUriUsed()
        }
    }
    val scope = rememberCoroutineScope()
    val goHome = { screen = Screen.List }
    if (screen != Screen.List) BackHandler(onBack = goHome)

    // Every vault write goes through here: off the main thread, then back to the list.
    fun write(block: Vault.() -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { vault.block() }
            screen = Screen.List
        }
    }

    when (val s = screen) {
        Screen.List -> AccountListScreen(
            accounts = accounts,
            showNoLockWarning = showNoLockWarning,
            onScan = { screen = Screen.Scan },
            onAddManually = { screen = Screen.AddManual },
            onEdit = { screen = Screen.Edit(it.id) },
            onSettings = { screen = Screen.Settings },
            onHotpUsed = { used -> scope.launch(Dispatchers.IO) { vault.replace(used.copy(counter = used.counter + 1)) } },
        )
        Screen.Scan -> ScanScreen(
            title = "Scan QR code",
            hint = "Point the camera at the 2FA QR code, or at Google Authenticator's \"Transfer accounts\" export code.",
            onScanned = { text ->
                try {
                    screen = Screen.Confirm(ScanResult.fromQrText(text))
                    null
                } catch (e: IllegalArgumentException) {
                    if (PairingQr.isPairingUri(text)) "That's a PC pairing code: use Settings → Pair with a PC." else "That QR code isn't a 2FA setup code (${e.message})."
                }
            },
            onBack = goHome,
        )
        Screen.PairScan -> ScanScreen(
            title = "Pair with a PC",
            hint = "On your PC, open Totem → Pair with phone → Show code, and point the camera at it.",
            onScanned = { text ->
                if (PairingQr.isPairingUri(text)) {
                    screen = Screen.Pairing(text)
                    null
                } else {
                    "That isn't a Totem pairing code."
                }
            },
            onBack = { screen = Screen.Settings },
        )
        is Screen.Pairing -> PairingScreen(app, s.uri, onDone = { screen = Screen.Settings })
        Screen.AddManual -> AddManualScreen(onSave = { write { add(listOf(it)) } }, onBack = goHome)
        is Screen.Confirm -> ConfirmImportScreen(
            found = s.found,
            existing = accounts,
            onSave = { toAdd -> write { add(toAdd) } },
            onScanAgain = { screen = Screen.Scan },
            onBack = goHome,
        )
        is Screen.Edit -> {
            val account = accounts.firstOrNull { it.id == s.accountId }
            if (account == null) {
                LaunchedEffect(Unit) { goHome() }
            } else {
                EditAccountScreen(
                    account = account,
                    onSave = { write { replace(it) } },
                    onDelete = { write { delete(account.id) } },
                    onBack = goHome,
                )
            }
        }
        Screen.Settings -> SettingsScreen(app, onPair = { screen = Screen.PairScan }, onActivity = { screen = Screen.Activity }, onBack = goHome)
        Screen.Activity -> ActivityScreen(app, onBack = { screen = Screen.Settings })
    }
}

@Composable
private fun LockedScreen(onUnlock: () -> Unit) = Centered {
    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.padding(bottom = 8.dp))
    Text("Totem is locked", style = MaterialTheme.typography.headlineSmall)
    Button(onClick = onUnlock) { Text("Unlock") }
}

@Composable
private fun BrokenVaultScreen(vault: Vault, reason: String) {
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Centered {
        Text("Totem can't open your accounts", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The encrypted account file exists, but this phone's key for it is missing or doesn't match " +
                "(this can happen after restoring the phone or clearing its security settings).\n\n" +
                "If you have a Totem backup file, you can reset and then import it from Settings.\n\nDetails: $reason",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { confirmReset = true }) { Text("Reset Totem…") }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Erase all accounts?") },
            text = { Text("This permanently deletes the unreadable account file. Only do this if you have a backup or can set up 2FA again.") },
            confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) { vault.reset() } }) { Text("Erase") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
