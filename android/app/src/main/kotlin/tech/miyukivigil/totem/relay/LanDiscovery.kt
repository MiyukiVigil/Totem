package tech.miyukivigil.totem.relay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet6Address
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Finds the desktop app's relay on the home network (mDNS `_totem._tcp`, TXT `id` = the PC's
 * relay id), for when the PC got a new address since pairing.
 *
 * Anyone on the network could announce a matching id, but that gains them nothing: the phone only
 * answers Noise handshakes from the paired PC's key, and the address must still be local.
 */
object LanDiscovery {
    private const val TYPE = "_totem._tcp."
    private const val TAG = "TotemLan"

    /** Blocking. Returns `ws://host:port/path` for the first PC in [pcIds] that answers, or null. */
    fun find(context: Context, pcIds: Set<String>, timeoutMs: Long = 5_000): String? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val result = CompletableFuture<String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = resolve(nsd, info, pcIds, result)
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                result.complete(null)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        return try {
            result.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            null
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                // never started
            }
        }
    }

    @Suppress("DEPRECATION") // resolveService still works; its replacement needs API 34.
    private fun resolve(nsd: NsdManager, found: NsdServiceInfo, pcIds: Set<String>, result: CompletableFuture<String>) {
        try {
            nsd.resolveService(found, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val id = info.attributes["id"]?.let { String(it) } ?: return
                    if (id !in pcIds) return
                    val host = info.host ?: return
                    val path = info.attributes["path"]?.let { String(it) }?.takeIf { it.startsWith("/") } ?: "/v1/ws"
                    val address = host.hostAddress?.substringBefore('%') ?: return
                    val hostPart = if (host is Inet6Address) "[$address]" else address
                    result.complete("ws://$hostPart:${info.port}$path")
                }
            })
        } catch (e: IllegalArgumentException) {
            // Another resolve is still running (older Android versions allow only one).
        }
    }
}
