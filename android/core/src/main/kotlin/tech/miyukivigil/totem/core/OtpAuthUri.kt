package tech.miyukivigil.totem.core

import java.net.URLDecoder

/**
 * Parses `otpauth://` URIs (the format inside 2FA setup QR codes):
 *
 * `otpauth://totp/Issuer:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Issuer&digits=6&period=30`
 *
 * See https://github.com/google/google-authenticator/wiki/Key-Uri-Format.
 */
object OtpAuthUri {
    class InvalidUri(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Account {
        // Split by hand rather than with java.net.URI: real-world QR codes often contain
        // unencoded spaces, which URI rejects.
        val trimmed = text.trim()
        if (!trimmed.startsWith("otpauth://", ignoreCase = true)) throw InvalidUri("not an otpauth:// link")
        val rest = trimmed.substring("otpauth://".length)
        val query = rest.substringAfter('?', "")
        val beforeQuery = rest.substringBefore('?')
        val host = beforeQuery.substringBefore('/')
        val rawPath = beforeQuery.substringAfter('/', "")
        val type = when (host.lowercase()) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            else -> throw InvalidUri("unsupported type: $host")
        }
        val params = parseQuery(query)

        val secretText = params["secret"] ?: throw InvalidUri("missing secret")
        val secret = try {
            Base32.decode(secretText)
        } catch (e: IllegalArgumentException) {
            throw InvalidUri("secret is not valid base32")
        }
        if (secret.isEmpty()) throw InvalidUri("secret is empty")

        // Path is "/Issuer:label" or "/label"; the issuer parameter wins if both are present.
        val path = decode(rawPath)
        val (pathIssuer, label) = path.indexOf(':').let { i ->
            if (i >= 0) path.substring(0, i).trim() to path.substring(i + 1).trim() else "" to path.trim()
        }
        val issuer = params["issuer"]?.trim()?.takeIf { it.isNotEmpty() } ?: pathIssuer

        val algorithm = params["algorithm"]?.let {
            try {
                OtpAlgorithm.parse(it)
            } catch (e: IllegalArgumentException) {
                throw InvalidUri("unsupported algorithm: $it")
            }
        } ?: OtpAlgorithm.SHA1
        val digits = params["digits"]?.toIntOrNull()?.takeIf { it in 6..10 }
            ?: if (params.containsKey("digits")) throw InvalidUri("unsupported digits: ${params["digits"]}") else 6
        val period = params["period"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: if (params.containsKey("period")) throw InvalidUri("invalid period: ${params["period"]}") else 30
        val counter = when (type) {
            OtpType.HOTP -> params["counter"]?.toLongOrNull()?.takeIf { it >= 0 } ?: throw InvalidUri("HOTP link without a valid counter")
            OtpType.TOTP -> 0
        }

        return Account(
            issuer = issuer,
            label = label,
            secret = secret,
            type = type,
            algorithm = algorithm,
            digits = digits,
            period = period,
            counter = counter,
        )
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.isNotEmpty() }.associate { part ->
            val i = part.indexOf('=')
            if (i < 0) decode(part).lowercase() to "" else decode(part.substring(0, i)).lowercase() to decode(part.substring(i + 1))
        }

    // URLDecoder turns '+' into a space, which is what issuers mean in practice. The String
    // charset overload exists on every Android version (the Charset one needs Android 13).
    internal fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        throw InvalidUri("badly encoded link")
    }
}
