package tech.miyukivigil.totem

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.miyukivigil.totem.core.Base32
import tech.miyukivigil.totem.core.Otp
import tech.miyukivigil.totem.core.OtpAlgorithm
import kotlinx.coroutines.delay

/**
 * M0 smoke-test screen: shows live codes computed by the shared core engine, so we can check the
 * engine and the build on a real device. The real vault and account list come in M1.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Codes must not show up in screenshots, screen recordings or the recent-apps preview.
        // Dev builds skip this so the app can be checked with adb screenshots (test secrets only).
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { DemoScreen() }
            }
        }
    }
}

/** Public test secrets from the RFCs. Never real accounts. */
private data class DemoAccount(val issuer: String, val label: String, val secretBase32: String, val algorithm: OtpAlgorithm)

private val demoAccounts = listOf(
    DemoAccount("RFC 6238", "SHA1 test secret", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", OtpAlgorithm.SHA1),
    DemoAccount(
        "RFC 6238", "SHA256 test secret",
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA", OtpAlgorithm.SHA256,
    ),
)

@Composable
private fun DemoScreen() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val seconds = now / 1000
    val remaining = Otp.secondsRemaining(seconds, 30)

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Totem", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Dev build · M0 engine check. These are public RFC test secrets, not real accounts.",
            style = MaterialTheme.typography.bodyMedium,
        )
        for (account in demoAccounts) {
            val code = Otp.totp(Base32.decode(account.secretBase32), seconds, 30, 6, account.algorithm)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${account.issuer} · ${account.label}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        code.chunked(3).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 36.sp,
                    )
                    LinearProgressIndicator(
                        progress = { ((now / 1000.0) % 30).let { 1f - (it / 30.0).toFloat() } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${remaining}s left", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
