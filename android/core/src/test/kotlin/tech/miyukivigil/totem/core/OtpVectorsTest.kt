package tech.miyukivigil.totem.core

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Checks the OTP engine against the shared vectors in `test-vectors/otp.json`. */
class OtpVectorsTest {
    private val vectors = JSONObject(File(System.getProperty("totem.vectors"), "otp.json").readText())

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hotpMatchesRfc4226() {
        val list = vectors.getJSONArray("hotp")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val secret = v.getString("secret_hex").hex()
            assertContentEquals(secret, Base32.decode(v.getString("secret_base32")))
            val code = Otp.hotp(secret, v.getLong("counter"), v.getInt("digits"), OtpAlgorithm.parse(v.getString("algorithm")))
            assertEquals(v.getString("code"), code, "counter ${v.getLong("counter")}")
        }
    }

    @Test
    fun totpMatchesRfc6238() {
        val list = vectors.getJSONArray("totp")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val secret = v.getString("secret_hex").hex()
            assertContentEquals(secret, Base32.decode(v.getString("secret_base32")))
            val code = Otp.totp(
                secret, v.getLong("time"), v.getLong("period"), v.getInt("digits"),
                OtpAlgorithm.parse(v.getString("algorithm")),
            )
            assertEquals(v.getString("code"), code, "${v.getString("algorithm")} at ${v.getLong("time")}")
        }
    }

    @Test
    fun base32MatchesRfc4648() {
        val list = vectors.getJSONArray("base32")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            assertContentEquals(v.getString("plain_hex").hex(), Base32.decode(v.getString("base32")))
        }
    }

    @Test
    fun base32IsLenientAboutFormatting() {
        assertContentEquals("foobar".toByteArray(), Base32.decode("mzxw 6ytb-oi=="))
    }

    @Test
    fun rejectsBadParameters() {
        assertFailsWith<IllegalArgumentException> { Otp.hotp(byteArrayOf(1), 0, 5, OtpAlgorithm.SHA1) }
        assertFailsWith<IllegalArgumentException> { Otp.hotp(ByteArray(0), 0, 6, OtpAlgorithm.SHA1) }
        assertFailsWith<IllegalArgumentException> { Otp.totp(byteArrayOf(1), 0, 0, 6, OtpAlgorithm.SHA1) }
        assertFailsWith<IllegalArgumentException> { Base32.decode("ABC1") }
    }

    @Test
    fun secondsRemainingCountsDown() {
        assertEquals(30, Otp.secondsRemaining(0, 30))
        assertEquals(1, Otp.secondsRemaining(29, 30))
        assertEquals(30, Otp.secondsRemaining(30, 30))
    }
}
