package tech.miyukivigil.totem.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import tech.miyukivigil.totem.TotemApp
import tech.miyukivigil.totem.relay.Pairing
import tech.miyukivigil.totem.relay.RelayConnection
import tech.miyukivigil.totem.relay.RelaySync
import tech.miyukivigil.totem.relay.RequestHandler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Short-lived foreground service started by a "wake" push: connects to the relay, answers the
 * PC's requests (waiting for Approve / Deny when needed), and stops once things go quiet.
 */
class RequestService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        ServiceCompat.startForeground(this, Notifications.SERVICE_ID, Notifications.service(this), type)
        if (running.getAndSet(true)) return START_NOT_STICKY // already connected; the relay forwards live
        thread(name = "totem-requests") {
            try {
                work()
            } catch (e: Exception) {
                Log.w(TAG, "request handling failed", e)
            } finally {
                running.set(false)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun work() {
        val app = application as TotemApp
        app.vault.load()
        val forgotten = RelaySync.forgetExpired(app)
        val device = app.devices.get()
        val token = Pairing.fcmToken()
        // Also visit relays that only a forgotten PC used, to clear its permission there.
        for (relay in (device.pcs + forgotten).map { it.relay }.distinct()) {
            val conn = try {
                RelaySync.connect(app, relay)
            } catch (e: Exception) {
                Log.w(TAG, "can't reach relay", e)
                continue
            }
            try {
                RelaySync.sync(conn, app, token)
                RequestHandler(app, conn).run()
            } finally {
                conn.close()
            }
        }
    }

    // Android 14+: short services must stop when told to.
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    companion object {
        private const val TAG = "TotemRequests"
        private val running = AtomicBoolean(false)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RequestService::class.java))
        }
    }
}
