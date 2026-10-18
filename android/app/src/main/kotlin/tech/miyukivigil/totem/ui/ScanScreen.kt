package tech.miyukivigil.totem.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.GoogleAuthMigration
import tech.miyukivigil.totem.core.OtpAuthUri
import java.util.concurrent.Executors

/** Accounts found in one QR code, ready to confirm. */
data class ScanResult(
    val accounts: List<Account>,
    /** Accounts in a Google Authenticator export that Totem can't use, with the reason. */
    val skipped: List<String> = emptyList(),
    /** For Google Authenticator exports split over several QR codes: 0-based index and total. */
    val batchIndex: Int = 0,
    val batchSize: Int = 1,
) {
    companion object {
        /** Throws IllegalArgumentException with a user-readable message if it's not a 2FA code. */
        fun fromQrText(text: String): ScanResult =
            if (GoogleAuthMigration.isMigrationUri(text)) {
                val batch = GoogleAuthMigration.parse(text)
                ScanResult(batch.accounts, batch.skipped, batch.batchIndex, batch.batchSize)
            } else {
                ScanResult(listOf(OtpAuthUri.parse(text)))
            }
    }
}

/**
 * Camera QR scanner. [onScanned] gets each decoded code and returns null to accept it (scanning
 * stops) or an error message to show while scanning continues.
 */
@Composable
fun ScanScreen(title: String, hint: String, onScanned: (String) -> String?, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) requestPermission.launch(Manifest.permission.CAMERA) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TotemTopBar(title, onBack = onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (hasPermission) {
                CameraQrScanner(
                    onText = { text ->
                        val error = onScanned(text)
                        message = error
                        error == null
                    },
                )
                Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                    Text(
                        message ?: hint,
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Centered {
                    Text("Totem needs the camera to scan QR codes. The camera is only used on this screen and images never leave the phone.")
                    Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }
        }
    }
}

/**
 * Camera preview that decodes QR codes on-device with ZXing. [onText] is called on the main
 * thread; return true to stop scanning.
 */
@Composable
private fun CameraQrScanner(onText: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    var done by remember { mutableStateOf(false) }
    var lastRejected by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, QrAnalyzer { text ->
                    ContextCompat.getMainExecutor(ctx).execute {
                        if (!done && text != lastRejected) {
                            if (currentOnText(text)) done = true else lastRejected = text
                        }
                    }
                })
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    Log.e("TotemScan", "camera bind failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}

private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    override fun analyze(image: ImageProxy) {
        try {
            // The Y (luminance) plane is all a QR decoder needs.
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            onText(reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text)
        } catch (e: ReaderException) {
            // No QR code in this frame.
        } finally {
            reader.reset()
            image.close()
        }
    }
}
