package tech.miyukivigil.totem.relay

import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import tech.miyukivigil.totem.TotemApp
import tech.miyukivigil.totem.core.AppMessages
import tech.miyukivigil.totem.core.PairingQr
import tech.miyukivigil.totem.core.RelayProtocol
import tech.miyukivigil.totem.core.TotemNoise
import tech.miyukivigil.totem.core.Transport
import tech.miyukivigil.totem.device.PairedPc
import tech.miyukivigil.totem.push.Notifications
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A handshake with a PC that's waiting for the user to compare codes. */
class PendingPairing internal constructor(
    private val app: TotemApp,
    private val conn: RelayConnection,
    private val transport: Transport,
    private val sid: String,
    val pc: PairedPc,
    /** Six digits; the PC shows the same number. */
    val confirmationCode: String,
) {
    /** Blocking. */
    fun confirm() {
        try {
            conn.sendTo(pc.id, sid, transport.encrypt(AppMessages.pairingDone(true)))
            app.devices.addPc(pc)
        } finally {
            conn.close()
        }
        RelaySync.syncAll(app)
    }

    /** Blocking. */
    fun reject() {
        try {
            conn.sendTo(pc.id, sid, transport.encrypt(AppMessages.pairingDone(false)))
        } catch (e: IOException) {
            // The PC will time out instead.
        } finally {
            conn.close()
        }
        RelaySync.syncAll(app)
    }
}

object Pairing {
    private const val TAG = "TotemPairing"

    /** Runs the handshake with the PC from a scanned `totem://pair?...` code. Blocking. */
    fun start(app: TotemApp, uri: String): PendingPairing {
        val qr = PairingQr.parse(uri)
        val now = System.currentTimeMillis() / 1000
        if (now > qr.expires + 30) throw IOException("This pairing code has expired. Run `totem pair` again on the PC.")
        val device = app.devices.get()

        val conn = RelayConnection.open(qr.relay, device.identity)
        try {
            // Let this PC reach the phone (plus the ones already paired on this relay).
            val peers = device.pcs.filter { it.relay == qr.relay }.map { it.id } + qr.pcId
            conn.send(RelayProtocol.allow(peers.distinct()))
            fcmToken()?.let { conn.send(RelayProtocol.registerPush(it)) }

            val sid = UUID.randomUUID().toString()
            val hs = TotemNoise.pairingInitiator(device.noisePrivate, qr.pcKey)
            conn.sendTo(qr.pcId, sid, hs.writeMessage(AppMessages.pairingHello(qr.secret, device.identity.deviceId, phoneName(app))))
            val reply = conn.nextFor(sid, 20_000)
            val welcome = try {
                hs.readMessage(reply.data)
            } catch (e: Exception) {
                throw IOException("The PC's reply didn't verify. Scan the code again.")
            }
            val pc = PairedPc(
                id = qr.pcId,
                name = AppMessages.pairingWelcomeName(welcome),
                noisePublic = qr.pcKey,
                relay = qr.relay,
                pairedAt = now,
            )
            val code = TotemNoise.confirmationCode(hs.handshakeHash())
            return PendingPairing(app, conn, hs.split(), sid, pc, code)
        } catch (e: Exception) {
            conn.close()
            Log.w(TAG, "pairing failed", e)
            throw e
        }
    }

    fun phoneName(app: TotemApp): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Blocking; null if Firebase can't give a token right now. */
    fun fcmToken(): String? = try {
        Tasks.await(FirebaseMessaging.getInstance().token, 10, TimeUnit.SECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "no FCM token", e)
        null
    }
}

/** Keeps each relay's view of this phone (allow list, push token) up to date. */
object RelaySync {
    /** Forgets PCs unused for longer than Settings allows, and says so. Blocking. */
    fun forgetExpired(app: TotemApp): List<PairedPc> {
        val forgotten = app.devices.forgetExpired(System.currentTimeMillis() / 1000)
        for (pc in forgotten) {
            Notifications.alert(
                app, "Forgot ${pc.name}",
                "${pc.name} hadn't asked for a code in over ${app.devices.get().forgetAfterDays} days, so Totem unpaired it. " +
                    "Pair it again from Settings if you still use it.",
            )
        }
        return forgotten
    }

    /**
     * Connects to [relay]. If it's a home-network relay that doesn't answer, looks for the PC on
     * the network (it may have a new address) and remembers where it went. Blocking.
     */
    fun connect(app: TotemApp, relay: String): RelayConnection {
        val device = app.devices.get()
        try {
            return RelayConnection.open(relay, device.identity)
        } catch (e: IOException) {
            if (!relay.lowercase().startsWith("ws://")) throw e
            val ids = device.pcs.filter { it.relay == relay }.map { it.id }.toSet()
            val found = LanDiscovery.find(app, ids)?.takeIf { it != relay } ?: throw e
            Log.i("TotemRelay", "relay moved on the home network; trying the new address")
            val conn = RelayConnection.open(found, device.identity)
            app.devices.moveRelay(relay, found)
            return conn
        }
    }

    /** Blocking, best effort. */
    fun syncAll(app: TotemApp, token: String? = Pairing.fcmToken()) {
        val device = app.devices.get()
        for (relay in device.pcs.map { it.relay }.distinct().ifEmpty { emptyList() }) {
            try {
                val conn = connect(app, relay)
                try {
                    sync(conn, app, token)
                } finally {
                    conn.close()
                }
            } catch (e: Exception) {
                Log.w("TotemRelay", "sync with $relay failed", e)
            }
        }
    }

    fun sync(conn: RelayConnection, app: TotemApp, token: String?) {
        val device = app.devices.get()
        val relay = conn.url.trimEnd('/')
        conn.send(RelayProtocol.allow(device.pcs.filter { it.relay.trimEnd('/') == relay }.map { it.id }))
        token?.let { conn.send(RelayProtocol.registerPush(it)) }
    }
}
