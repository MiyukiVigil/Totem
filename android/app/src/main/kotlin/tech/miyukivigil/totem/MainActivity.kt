package tech.miyukivigil.totem

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.miyukivigil.totem.core.PairingQr
import tech.miyukivigil.totem.push.TotemMessagingService
import tech.miyukivigil.totem.ui.LockState
import tech.miyukivigil.totem.ui.TotemRoot

/**
 * Hosts the Compose UI and owns the app lock: Totem asks for fingerprint / face / screen-lock PIN
 * on launch and whenever it comes back after [RELOCK_AFTER_MS] in the background.
 */
class MainActivity : FragmentActivity() {
    private val app get() = application as TotemApp
    private val lock: LockModel by viewModels()
    private var lockState: LockState
        get() = lock.state
        set(value) { lock.state = value }
    private var promptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Codes must not show up in screenshots, screen recordings or the recent-apps preview.
        if (!app.debugFlag("debug_allow_screenshots")) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (app.isDebugBuild) {
            // M0 push spike: scripts/push-spike.mjs reads this token from logcat.
            FirebaseMessaging.getInstance().token.addOnSuccessListener { Log.i(TotemMessagingService.TAG, "FCM token: $it") }
        }
        lifecycleScope.launch(Dispatchers.IO) { app.vault.load() }
        if (savedInstanceState == null) takePairingLink(intent)

        enableEdgeToEdge()
        setContent {
            TotemRoot(app, lockState, ::showPrompt, pairingUri = lock.pairingUri, onPairingUriUsed = { lock.pairingUri = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takePairingLink(intent)
    }

    /** Debug builds only: `totem://pair?...` links (see the debug manifest) open the pairing screen. */
    private fun takePairingLink(intent: Intent?) {
        val uri = intent?.dataString ?: return
        if (app.isDebugBuild && PairingQr.isPairingUri(uri)) lock.pairingUri = uri
    }

    override fun onStart() {
        super.onStart()
        if (lock.backgroundedAt != 0L && SystemClock.elapsedRealtime() - lock.backgroundedAt > RELOCK_AFTER_MS) {
            lockState = LockState.LOCKED
        }
    }

    override fun onResume() {
        super.onResume()
        if (lockState == LockState.LOCKED) showPrompt()
    }

    override fun onStop() {
        super.onStop()
        // Changing configuration (e.g. rotation) isn't really leaving the app.
        lock.backgroundedAt = if (isChangingConfigurations) 0L else SystemClock.elapsedRealtime()
    }

    private fun authenticators(): Int =
        // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is not supported before Android 11.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private fun showPrompt() {
        if (promptShowing || lockState != LockState.LOCKED) return
        if (app.debugFlag("debug_skip_lock")) {
            lockState = LockState.UNLOCKED
            return
        }
        when (BiometricManager.from(this).canAuthenticate(authenticators())) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            else -> {
                // No screen lock set up at all: nothing to check against. The UI shows a warning.
                lockState = LockState.NO_DEVICE_LOCK
                return
            }
        }
        promptShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    lockState = LockState.UNLOCKED
                    // The unlock also opens the extra-locked phone-only accounts, if that's on.
                    lifecycleScope.launch(Dispatchers.IO) { app.vault.unseal() }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled or too many attempts: stay locked; the lock screen has a retry button.
                    promptShowing = false
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Totem")
                .setAllowedAuthenticators(authenticators())
                .build(),
        )
    }

    private companion object {
        const val RELOCK_AFTER_MS = 30_000L
    }
}

/**
 * Survives rotation but not process death, so a process killed in the background always comes
 * back locked.
 */
class LockModel : ViewModel() {
    var state by mutableStateOf(LockState.LOCKED)
    var backgroundedAt = 0L
    var pairingUri by mutableStateOf<String?>(null)
}
