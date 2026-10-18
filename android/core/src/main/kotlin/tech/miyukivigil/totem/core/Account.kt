package tech.miyukivigil.totem.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class OtpType { TOTP, HOTP }

/** What the phone does when a paired PC asks for this account's code. */
enum class RemotePolicy {
    /** Reply straight away (a "code sent" notification is still shown). */
    AUTO,
    /** Ask on the phone with Approve / Deny. */
    ASK,
    /** Never available remotely. */
    NEVER,
}

/** One 2FA account. [secret] is the raw key (already base32-decoded). */
data class Account(
    val id: String = UUID.randomUUID().toString(),
    val issuer: String,
    val label: String,
    val secret: ByteArray,
    val type: OtpType = OtpType.TOTP,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    /** HOTP only: the counter value the *next* code will use. */
    val counter: Long = 0,
    val policy: RemotePolicy = RemotePolicy.ASK,
    val createdAt: Long = System.currentTimeMillis(),
    /** Brand icon slug chosen by the user; null = pick from the issuer, "" = no icon. */
    val icon: String? = null,
) {
    init {
        require(secret.isNotEmpty()) { "secret must not be empty" }
        require(digits in 6..10) { "digits must be between 6 and 10" }
        require(period > 0) { "period must be positive" }
        require(counter >= 0) { "counter must not be negative" }
    }

    /** Current TOTP code at [unixTime], or the HOTP code for [counter]. */
    fun code(unixTime: Long): String = when (type) {
        OtpType.TOTP -> Otp.totp(secret, unixTime, period.toLong(), digits, algorithm)
        OtpType.HOTP -> Otp.hotp(secret, counter, digits, algorithm)
    }

    /** Title shown in lists: "Issuer (label)", or whichever of the two exists. */
    val displayName: String
        get() = when {
            issuer.isNotBlank() && label.isNotBlank() -> "$issuer ($label)"
            issuer.isNotBlank() -> issuer
            else -> label
        }

    /** Same secret and settings, ignoring id, names, policy and timestamps. Used to skip duplicates on import. */
    fun sameKeyAs(other: Account): Boolean =
        secret.contentEquals(other.secret) && type == other.type && algorithm == other.algorithm &&
            digits == other.digits && period == other.period

    // ByteArray needs content-based equality.
    override fun equals(other: Any?): Boolean =
        other is Account && id == other.id && issuer == other.issuer && label == other.label &&
            secret.contentEquals(other.secret) && type == other.type && algorithm == other.algorithm &&
            digits == other.digits && period == other.period && counter == other.counter &&
            policy == other.policy && createdAt == other.createdAt && icon == other.icon

    override fun hashCode(): Int = id.hashCode()

    /** Never prints the secret. */
    override fun toString(): String = "Account(id=$id, issuer=$issuer, label=$label, type=$type)"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("issuer", issuer)
        .put("label", label)
        .put("secret", Base32.encode(secret))
        .put("type", type.name)
        .put("algorithm", algorithm.name)
        .put("digits", digits)
        .put("period", period)
        .put("counter", counter)
        .put("policy", policy.name)
        .put("created_at", createdAt)
        .apply { icon?.let { put("icon", it) } }

    companion object {
        fun fromJson(o: JSONObject) = Account(
            id = o.getString("id"),
            issuer = o.optString("issuer"),
            label = o.optString("label"),
            secret = Base32.decode(o.getString("secret")),
            type = OtpType.valueOf(o.optString("type", "TOTP")),
            algorithm = OtpAlgorithm.parse(o.optString("algorithm", "SHA1")),
            digits = o.optInt("digits", 6),
            period = o.optInt("period", 30),
            counter = o.optLong("counter", 0),
            policy = RemotePolicy.valueOf(o.optString("policy", "ASK")),
            createdAt = o.optLong("created_at", 0),
            icon = if (o.has("icon")) o.getString("icon") else null,
        )
    }
}

/** The plaintext contents of the vault and of backups. */
object AccountListCodec {
    const val VERSION = 1

    fun encode(accounts: List<Account>): ByteArray =
        JSONObject()
            .put("version", VERSION)
            .put("accounts", JSONArray().apply { accounts.forEach { put(it.toJson()) } })
            .toString()
            .toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): List<Account> {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val version = root.getInt("version")
        require(version <= VERSION) { "made by a newer Totem (format $version)" }
        val list = root.getJSONArray("accounts")
        return (0 until list.length()).map { Account.fromJson(list.getJSONObject(it)) }
    }
}
