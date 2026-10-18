package tech.miyukivigil.totem.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Mirrors `protocol/src/relay.rs`: logging in to the relay and its message format. */
object RelayProtocol {
    val AUTH_CONTEXT = "totem-relay-auth-v1".toByteArray()

    /** First 16 bytes of SHA-256 of the Ed25519 public key, as hex. */
    fun deviceId(ed25519Public: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(ed25519Public).copyOf(16).joinToString("") { "%02x".format(it) }

    fun authMessage(nonce: ByteArray): ByteArray = AUTH_CONTEXT + nonce

    /**
     * Throws unless [url] is `wss://`, or plain `ws://` to a local-network address (private IPv4,
     * IPv6 unique-local / link-local, loopback, `*.local`). Mirrors `check_relay_url` in
     * `protocol/src/relay.rs`.
     */
    fun checkRelayUrl(url: String) {
        val lower = url.trim().lowercase()
        val secure = lower.startsWith("wss://")
        if (!secure && !lower.startsWith("ws://")) throw IllegalArgumentException("the relay address must start with ws:// or wss://")
        val authority = lower.substringAfter("://").split('/', '?', '#').first()
        val host = if (authority.startsWith("[")) authority.removePrefix("[").substringBefore("]") else authority.substringBeforeLast(":", authority)
        if (host.isEmpty()) throw IllegalArgumentException("the relay address has no host")
        if (!secure && !isLocalHost(host)) {
            throw IllegalArgumentException("$host isn't on your local network, so the relay address must use wss:// (encrypted)")
        }
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        if (host.contains(':')) {
            // IPv6 literal.
            val first = host.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
            return host == "::1" || (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
        }
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    fun auth(identity: RelayIdentity, nonce: ByteArray): String = JSONObject()
        .put("t", "auth")
        .put("pubkey", b64.encodeToString(identity.publicKey))
        .put("sig", b64.encodeToString(identity.sign(authMessage(nonce))))
        .toString()

    fun registerPush(fcmToken: String): String = JSONObject().put("t", "register_push").put("fcm", fcmToken).toString()

    fun allow(peers: List<String>): String = JSONObject().put("t", "allow").put("peers", JSONArray(peers)).toString()

    fun send(to: String, sid: String, data: ByteArray): String =
        JSONObject().put("t", "send").put("to", to).put("sid", sid).put("data", b64.encodeToString(data)).toString()

    sealed interface ServerMsg {
        class Challenge(val nonce: ByteArray) : ServerMsg
        data class Ready(val id: String) : ServerMsg
        class Msg(val from: String, val sid: String, val data: ByteArray) : ServerMsg
        data class Error(val code: String, val message: String) : ServerMsg
    }

    fun parse(text: String): ServerMsg {
        val o = JSONObject(text)
        return when (val t = o.getString("t")) {
            "challenge" -> ServerMsg.Challenge(unb64.decode(o.getString("nonce")))
            "ready" -> ServerMsg.Ready(o.getString("id"))
            "msg" -> ServerMsg.Msg(o.getString("from"), o.getString("sid"), unb64.decode(o.getString("data")))
            "error" -> ServerMsg.Error(o.getString("code"), o.getString("msg"))
            else -> throw IllegalArgumentException("unknown relay message: $t")
        }
    }
}

/** Ed25519 key a device uses to log in to the relay. [seed] is the 32-byte private key. */
class RelayIdentity(val seed: ByteArray) {
    private val key = Ed25519PrivateKeyParameters(seed, 0)
    val publicKey: ByteArray = key.generatePublicKey().encoded
    val deviceId: String = RelayProtocol.deviceId(publicKey)

    fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, key)
        update(message, 0, message.size)
        generateSignature()
    }

    companion object {
        fun generate() = RelayIdentity(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}

/** Contents of the pairing QR code shown by the PC. Mirrors `PairingQr` in `protocol/src/app.rs`. */
class PairingQr(val relay: String, val pcId: String, val pcKey: ByteArray, val secret: ByteArray, val expires: Long) {
    companion object {
        const val PREFIX = "totem://pair?"
        const val VERSION = "1"

        fun isPairingUri(text: String) = text.trim().startsWith(PREFIX)

        fun parse(uri: String): PairingQr {
            val query = uri.trim().removePrefix(PREFIX).takeIf { uri.trim().startsWith(PREFIX) }
                ?: throw IllegalArgumentException("not a Totem pairing code")
            val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            fun get(k: String) = params[k] ?: throw IllegalArgumentException("pairing code has no $k")
            if (get("v") != VERSION) throw IllegalArgumentException("pairing code is from a different Totem version")
            val b64 = Base64.getUrlDecoder()
            val key = b64.decode(get("k"))
            val secret = b64.decode(get("s"))
            require(key.size == 32 && secret.size == TotemNoise.PAIRING_SECRET_LEN) { "pairing code is damaged" }
            return PairingQr(
                relay = URLDecoder.decode(get("relay"), "UTF-8"),
                pcId = get("id"),
                pcKey = key,
                secret = secret,
                expires = get("exp").toLongOrNull() ?: throw IllegalArgumentException("bad expiry"),
            )
        }
    }
}

/** Messages inside the Noise channel. Mirrors `protocol/src/app.rs`. */
object AppMessages {
    private val b64 = Base64.getEncoder()

    fun pairingHello(secret: ByteArray, phoneId: String, name: String): ByteArray =
        JSONObject().put("secret", b64.encodeToString(secret)).put("phone_id", phoneId).put("name", name).toString().toByteArray()

    fun pairingWelcomeName(payload: ByteArray): String = JSONObject(String(payload)).getString("name")

    fun pairingDone(accepted: Boolean): ByteArray = JSONObject().put("accepted", accepted).toString().toByteArray()

    sealed interface Request {
        val req: String
        val ts: Long
        data class ListAccounts(override val req: String, override val ts: Long) : Request
        data class GetCode(override val req: String, override val ts: Long, val account: String) : Request
    }

    fun parseRequest(payload: ByteArray): Request {
        val o = JSONObject(String(payload))
        val req = o.getString("req")
        val ts = o.getLong("ts")
        return when (val type = o.getString("type")) {
            "list_accounts" -> Request.ListAccounts(req, ts)
            "get_code" -> Request.GetCode(req, ts, o.getString("account"))
            else -> throw IllegalArgumentException("unknown request: $type")
        }
    }

    fun accounts(req: String, accounts: List<Account>): ByteArray = JSONObject()
        .put("type", "accounts")
        .put("req", req)
        .put("items", JSONArray().apply {
            accounts.forEach {
                put(JSONObject().put("id", it.id).put("issuer", it.issuer).put("label", it.label).put("policy", it.policy.name.lowercase()))
            }
        })
        .toString().toByteArray()

    fun code(req: String, code: String, validUntil: Long, nextCode: String?): ByteArray = JSONObject()
        .put("type", "code").put("req", req).put("code", code).put("valid_until", validUntil)
        .apply { if (nextCode != null) put("next_code", nextCode) }
        .toString().toByteArray()

    fun denied(req: String, reason: String): ByteArray =
        JSONObject().put("type", "denied").put("req", req).put("reason", reason).toString().toByteArray()

    /** Requests whose timestamp is further than this from the phone's clock are refused. */
    const val MAX_CLOCK_SKEW_SECS = 120L
}
