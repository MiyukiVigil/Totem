package tech.miyukivigil.totem.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tech.miyukivigil.totem.core.RelayIdentity
import tech.miyukivigil.totem.core.RelayProtocol
import tech.miyukivigil.totem.core.RelayProtocol.ServerMsg
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A logged-in relay connection with a blocking API, for use from background threads.
 * See `protocol/src/relay.rs` for the protocol. [url] is the relay URL as given (OkHttp itself
 * rewrites ws:// to http://).
 */
class RelayConnection private constructor(val url: String, private val ws: WebSocket, private val events: LinkedBlockingQueue<Any>) {

    fun send(text: String) {
        if (!ws.send(text)) throw IOException("relay connection closed")
    }

    fun sendTo(to: String, sid: String, data: ByteArray) = send(RelayProtocol.send(to, sid, data))

    /** Next message from the relay, or null if nothing arrives within [timeoutMs]. */
    fun next(timeoutMs: Long): ServerMsg? = when (val e = events.poll(timeoutMs, TimeUnit.MILLISECONDS)) {
        null -> null
        is String -> RelayProtocol.parse(e)
        is Throwable -> throw IOException("relay connection lost: ${e.message}", e)
        else -> error("unexpected event $e")
    }

    /** Next message for session [sid] within [timeoutMs]; relay errors are thrown. */
    fun nextFor(sid: String, timeoutMs: Long): ServerMsg.Msg {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw IOException("timed out waiting for the PC")
            when (val m = next(left)) {
                is ServerMsg.Msg -> if (m.sid == sid) return m
                is ServerMsg.Error -> throw IOException("relay: ${m.message}")
                else -> Unit
            }
        }
    }

    fun close() {
        ws.close(1000, null)
    }

    companion object {
        private val client by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }

        /** Connects and logs in. Blocking. */
        fun open(url: String, identity: RelayIdentity, timeoutMs: Long = 10_000): RelayConnection {
            // Plain ws:// only inside the home network; anything else must be wss://.
            try {
                RelayProtocol.checkRelayUrl(url)
            } catch (e: IllegalArgumentException) {
                throw IOException(e.message)
            }
            val events = LinkedBlockingQueue<Any>()
            val ws = client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        events.put(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        events.put(t)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        events.put(IOException("closed by relay ($code $reason)"))
                    }
                },
            )
            val conn = RelayConnection(url, ws, events)
            try {
                val challenge = conn.next(timeoutMs) as? ServerMsg.Challenge ?: throw IOException("relay did not answer")
                conn.send(RelayProtocol.auth(identity, challenge.nonce))
                when (val r = conn.next(timeoutMs)) {
                    is ServerMsg.Ready -> return conn
                    is ServerMsg.Error -> throw IOException("relay refused login: ${r.message}")
                    else -> throw IOException("relay did not confirm login")
                }
            } catch (e: Exception) {
                conn.close()
                throw e
            }
        }
    }
}
