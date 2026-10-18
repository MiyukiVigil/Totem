package tech.miyukivigil.totem.push

import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import tech.miyukivigil.totem.TotemApp
import tech.miyukivigil.totem.core.Base32
import tech.miyukivigil.totem.core.Otp
import tech.miyukivigil.totem.core.OtpAlgorithm
import tech.miyukivigil.totem.relay.RelaySync
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Receives pushes from the relay. A `wake` push carries no data; it just starts
 * [RequestService], which fetches the actual (encrypted) requests from the relay.
 */
class TotemMessagingService : FirebaseMessagingService() {
    private val app get() = application as TotemApp

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token refreshed")
        if (app.isDebugBuild) Log.i(TAG, "FCM token: $token")
        // The relay needs the new token to wake us.
        thread { RelaySync.syncAll(app, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["kind"]) {
            "wake" -> {
                Log.i(TAG, "wake push (priority ${message.priority})")
                RequestService.start(this)
            }
            "ping" -> if (app.isDebugBuild) answerSpikePing(message)
        }
    }

    /**
     * M0 push spike (`scripts/push-spike.mjs`): replies to a URL inside the push with a code for the
     * RFC test secret and the phone's power state. Debug builds only.
     */
    private fun answerSpikePing(message: RemoteMessage) {
        val receivedMs = System.currentTimeMillis()
        val req = message.data["req"] ?: return
        val reply = message.data["reply"] ?: return
        val power = getSystemService(PowerManager::class.java)
        val now = receivedMs / 1000
        val body = JSONObject()
            .put("req", req)
            .put("code", Otp.totp(Base32.decode(DEMO_SECRET), now, 30, 6, OtpAlgorithm.SHA1))
            .put("code_time", now)
            .put("phone_received_ms", receivedMs)
            .put("priority", if (message.priority == RemoteMessage.PRIORITY_HIGH) "high" else "normal")
            .put("original_priority", if (message.originalPriority == RemoteMessage.PRIORITY_HIGH) "high" else "normal")
            .put("device_idle", power.isDeviceIdleMode)
            .put("power_save", power.isPowerSaveMode)
            .put("interactive", power.isInteractive)
        try {
            val conn = URL(reply).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "spike reply failed", e)
        }
    }

    companion object {
        const val TAG = "TotemPush"
        /** RFC 6238 SHA1 test secret. */
        private const val DEMO_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    }
}
