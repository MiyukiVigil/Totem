package tech.miyukivigil.totem.device

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import tech.miyukivigil.totem.core.RelayIdentity
import tech.miyukivigil.totem.core.TotemNoise
import tech.miyukivigil.totem.vault.VaultCrypto
import java.io.File
import java.util.Base64

/** A PC this phone has paired with. */
data class PairedPc(
    val id: String,
    val name: String,
    /** The PC's Noise static public key. */
    val noisePublic: ByteArray,
    val relay: String,
    val pairedAt: Long,
) {
    override fun equals(other: Any?) = other is PairedPc && id == other.id
    override fun hashCode() = id.hashCode()
}

/**
 * This phone's own keys (relay login + Noise) and its paired PCs, stored encrypted in
 * `device.bin` with the same Keystore key as the vault (different associated data).
 */
class DeviceStore(dir: File, private val crypto: VaultCrypto = VaultCrypto()) {
    class Data(val relaySeed: ByteArray, val noisePrivate: ByteArray, val noisePublic: ByteArray, val pcs: List<PairedPc>) {
        val identity by lazy { RelayIdentity(relaySeed) }
    }

    private val file = AtomicFile(File(dir, "device.bin"))
    private var cached: Data? = null

    /** Loads, or creates this phone's keys on first use. Blocking. */
    @Synchronized
    fun get(): Data {
        cached?.let { return it }
        val data = if (file.baseFile.exists()) decode(crypto.decrypt(file.readFully(), AAD)) else create()
        cached = data
        return data
    }

    @Synchronized
    fun addPc(pc: PairedPc) = update { d -> Data(d.relaySeed, d.noisePrivate, d.noisePublic, d.pcs.filterNot { it.id == pc.id } + pc) }

    @Synchronized
    fun removePc(id: String) = update { d -> Data(d.relaySeed, d.noisePrivate, d.noisePublic, d.pcs.filterNot { it.id == id }) }

    private fun create(): Data {
        val noise = TotemNoise.generateKeyPair()
        return Data(RelayIdentity.generate().seed, noise.private, noise.public, emptyList()).also(::save)
    }

    private fun update(change: (Data) -> Data) {
        val next = change(get())
        save(next)
        cached = next
    }

    private fun save(d: Data) {
        val out = file.startWrite()
        try {
            out.write(crypto.encrypt(encode(d), AAD))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        val AAD = "totem-device-v1".toByteArray()
        val b64: Base64.Encoder = Base64.getEncoder()
        val unb64: Base64.Decoder = Base64.getDecoder()

        fun encode(d: Data): ByteArray = JSONObject()
            .put("relay_seed", b64.encodeToString(d.relaySeed))
            .put("noise_private", b64.encodeToString(d.noisePrivate))
            .put("noise_public", b64.encodeToString(d.noisePublic))
            .put("pcs", JSONArray().apply {
                d.pcs.forEach {
                    put(
                        JSONObject().put("id", it.id).put("name", it.name).put("noise_public", b64.encodeToString(it.noisePublic))
                            .put("relay", it.relay).put("paired_at", it.pairedAt),
                    )
                }
            })
            .toString().toByteArray()

        fun decode(bytes: ByteArray): Data {
            val o = JSONObject(String(bytes))
            val pcs = o.getJSONArray("pcs")
            return Data(
                unb64.decode(o.getString("relay_seed")),
                unb64.decode(o.getString("noise_private")),
                unb64.decode(o.getString("noise_public")),
                (0 until pcs.length()).map { i ->
                    val p = pcs.getJSONObject(i)
                    PairedPc(p.getString("id"), p.getString("name"), unb64.decode(p.getString("noise_public")), p.getString("relay"), p.getLong("paired_at"))
                },
            )
        }
    }
}
