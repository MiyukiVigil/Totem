package tech.miyukivigil.totem.core

import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Export files from other authenticator apps: Aegis (`.json`, plain or password-protected) and
 * 2FAS (`.2fas`, plain or password-protected). Totem's own backups are [Backup].
 *
 * Unsupported entry types (Steam, mOTP, Yandex) are skipped and reported, never guessed at.
 */
object ForeignImport {
    enum class Format(val app: String) { AEGIS("Aegis"), TWOFAS("2FAS") }

    class Detected(val format: Format, val needsPassword: Boolean, internal val root: JSONObject)

    class Result(val accounts: List<Account>, val skipped: List<String>)

    class WrongPassword : Exception("wrong password")

    /** Returns null if [bytes] isn't an Aegis or 2FAS export. */
    fun detect(bytes: ByteArray): Detected? {
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        return when {
            root.has("db") && root.has("header") ->
                Detected(Format.AEGIS, root.get("db") is String, root)
            root.has("services") && root.has("schemaVersion") ->
                Detected(Format.TWOFAS, root.optString("servicesEncrypted").isNotEmpty(), root)
            else -> null
        }
    }

    /** [password] is needed when [Detected.needsPassword]. Throws [WrongPassword]. */
    fun read(file: Detected, password: CharArray? = null): Result = when (file.format) {
        Format.AEGIS -> aegisEntries(aegisDb(file.root, password))
        Format.TWOFAS -> twoFasServices(twoFasList(file.root, password))
    }

    // --- Aegis: https://github.com/beemdevelopment/Aegis/blob/master/docs/vault.md

    private fun aegisDb(root: JSONObject, password: CharArray?): JSONObject {
        val db = root.get("db")
        if (db is JSONObject) return db
        requireNotNull(password) { "password needed" }
        val header = root.getJSONObject("header")
        val slots = header.getJSONArray("slots")
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        var masterKey: ByteArray? = null
        for (i in 0 until slots.length()) {
            val slot = slots.getJSONObject(i)
            if (slot.getInt("type") != 1) continue // 1 = password; others are biometric slots
            val derived = SCrypt.generate(passwordBytes, hex(slot.getString("salt")), slot.getInt("n"), slot.getInt("r"), slot.getInt("p"), 32)
            val params = slot.getJSONObject("key_params")
            masterKey = try {
                gcmOpen(derived, hex(params.getString("nonce")), hex(slot.getString("key")) + hex(params.getString("tag")))
            } catch (e: AEADBadTagException) {
                null
            } finally {
                derived.fill(0)
            }
            if (masterKey != null) break
        }
        passwordBytes.fill(0)
        val key = masterKey ?: throw WrongPassword()
        val params = header.getJSONObject("params")
        val plain = try {
            gcmOpen(key, hex(params.getString("nonce")), Base64.getDecoder().decode(db as String) + hex(params.getString("tag")))
        } finally {
            key.fill(0)
        }
        return JSONObject(String(plain, Charsets.UTF_8)).also { plain.fill(0) }
    }

    private fun aegisEntries(db: JSONObject): Result {
        val entries = db.getJSONArray("entries")
        val accounts = mutableListOf<Account>()
        val skipped = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val name = listOf(e.optString("issuer"), e.optString("name")).filter { it.isNotBlank() }.joinToString(" ")
            val type = when (e.getString("type")) {
                "totp" -> OtpType.TOTP
                "hotp" -> OtpType.HOTP
                else -> {
                    skipped += "$name (${e.getString("type")})"
                    continue
                }
            }
            val info = e.getJSONObject("info")
            accounts += Account(
                issuer = e.optString("issuer"),
                label = e.optString("name"),
                secret = Base32.decode(info.getString("secret")),
                type = type,
                algorithm = OtpAlgorithm.parse(info.optString("algo", "SHA1")),
                digits = info.optInt("digits", 6),
                period = info.optInt("period", 30),
                counter = info.optLong("counter", 0),
            )
        }
        return Result(accounts, skipped)
    }

    // --- 2FAS: "servicesEncrypted" = base64(ciphertext+tag) : base64(salt) : base64(iv),
    // key = PBKDF2-HMAC-SHA256(password, salt, 10 000 iterations, 256 bits), AES-GCM.

    private fun twoFasList(root: JSONObject, password: CharArray?): JSONArray {
        val encrypted = root.optString("servicesEncrypted")
        if (encrypted.isEmpty()) return root.getJSONArray("services")
        requireNotNull(password) { "password needed" }
        val parts = encrypted.split(":")
        require(parts.size >= 3) { "unexpected 2FAS file layout" }
        val b64 = Base64.getDecoder()
        val (data, salt, iv) = Triple(b64.decode(parts[0]), b64.decode(parts[1]), b64.decode(parts[2]))
        val spec = PBEKeySpec(password, salt, 10_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        val plain = try {
            gcmOpen(key, iv, data)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()
        } finally {
            key.fill(0)
        }
        return JSONArray(String(plain, Charsets.UTF_8)).also { plain.fill(0) }
    }

    private fun twoFasServices(services: JSONArray): Result {
        val accounts = mutableListOf<Account>()
        val skipped = mutableListOf<String>()
        for (i in 0 until services.length()) {
            val s = services.getJSONObject(i)
            val otp = s.optJSONObject("otp") ?: JSONObject()
            val issuer = otp.optString("issuer").ifBlank { s.optString("name") }
            val label = otp.optString("account").ifBlank { otp.optString("label").substringAfter(':') }
            val type = when (otp.optString("tokenType", "TOTP").uppercase()) {
                "TOTP" -> OtpType.TOTP
                "HOTP" -> OtpType.HOTP
                else -> {
                    skipped += "$issuer (${otp.optString("tokenType").lowercase()})"
                    continue
                }
            }
            accounts += Account(
                issuer = issuer,
                label = label,
                secret = Base32.decode(s.getString("secret")),
                type = type,
                algorithm = OtpAlgorithm.parse(otp.optString("algorithm", "SHA1")),
                digits = otp.optInt("digits", 6),
                period = otp.optInt("period", 30),
                counter = otp.optLong("counter", 0),
            )
        }
        return Result(accounts, skipped)
    }

    private fun gcmOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertextAndTag)
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
