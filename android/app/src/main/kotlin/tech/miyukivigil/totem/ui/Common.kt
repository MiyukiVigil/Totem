package tech.miyukivigil.totem.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TotemTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
        },
        actions = actions,
    )
}

/** Wall-clock milliseconds, updated a few times a second so codes and countdowns stay live. */
@Composable
fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    return now
}

private const val CLIP_LABEL = "Totem code"
private const val CLEAR_AFTER_MS = 30_000L

/**
 * Copies a code, marked sensitive so Android hides it in the clipboard preview and keyboard
 * suggestions, and clears it again after 30 seconds if it's still ours.
 */
fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(CLIP_LABEL, code).apply {
        description.extras = PersistableBundle().apply {
            // ClipDescription.EXTRA_IS_SENSITIVE; the constant is only public from Android 13.
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    clipboard.setPrimaryClip(clip)
    val copiedAt = clip.description.timestamp
    Handler(Looper.getMainLooper()).postDelayed({
        // Android 10+ only lets us see the clipboard while focused; if we can't tell, leave it.
        val current = runCatching { clipboard.primaryClipDescription }.getOrNull()
        if (current?.label == CLIP_LABEL && current.timestamp == copiedAt) clipboard.clearPrimaryClip()
    }, CLEAR_AFTER_MS)
}

/** "123456" -> "123 456", "12345678" -> "1234 5678". */
fun formatCode(code: String): String = when (code.length) {
    6 -> code.substring(0, 3) + " " + code.substring(3)
    8 -> code.substring(0, 4) + " " + code.substring(4)
    else -> code
}
