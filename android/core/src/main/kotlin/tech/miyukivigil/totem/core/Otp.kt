package tech.miyukivigil.totem.core

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HOTP (RFC 4226) / TOTP (RFC 6238). Mirrors `protocol/src/otp.rs`. */
enum class OtpAlgorithm(internal val jcaName: String) {
    SHA1("HmacSHA1"),
    SHA256("HmacSHA256"),
    SHA512("HmacSHA512");

    companion object {
        /** Parses the `algorithm` value used in `otpauth://` URIs and the test vectors. */
        fun parse(value: String): OtpAlgorithm =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown algorithm: $value")
    }
}

object Otp {
    fun hotp(secret: ByteArray, counter: Long, digits: Int, algorithm: OtpAlgorithm): String {
        require(digits in 6..10) { "digits must be between 6 and 10, got $digits" }
        require(secret.isNotEmpty()) { "secret must not be empty" }
        val mac = Mac.getInstance(algorithm.jcaName).run {
            init(SecretKeySpec(secret, algorithm.jcaName))
            doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        }
        // Dynamic truncation (RFC 4226 section 5.3).
        val offset = mac.last().toInt() and 0x0f
        val binary = ((mac[offset].toLong() and 0x7f) shl 24) or
            ((mac[offset + 1].toLong() and 0xff) shl 16) or
            ((mac[offset + 2].toLong() and 0xff) shl 8) or
            (mac[offset + 3].toLong() and 0xff)
        var modulus = 1L
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    fun totp(secret: ByteArray, unixTime: Long, period: Long, digits: Int, algorithm: OtpAlgorithm): String {
        require(period > 0) { "period must be greater than zero" }
        return hotp(secret, unixTime / period, digits, algorithm)
    }

    /** Seconds until the code at [unixTime] stops being valid. */
    fun secondsRemaining(unixTime: Long, period: Long): Long = period - (unixTime % period)
}

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** RFC 4648 base32 without padding, as used in `otpauth://` secrets. */
    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 31])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    /**
     * Decodes RFC 4648 base32 as found in `otpauth://` secrets: case-insensitive,
     * spaces/dashes ignored, `=` padding optional.
     */
    fun decode(input: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(input.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in input) {
            val value = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a'
                in '2'..'7' -> c - '2' + 26
                ' ', '-', '=' -> continue
                else -> throw IllegalArgumentException("invalid base32 character: '$c'")
            }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write(buffer shr bits)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}
