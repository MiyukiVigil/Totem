package tech.miyukivigil.totem.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountFormatsTest {
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test
    fun base32RoundTrips() {
        for (len in 0..40) {
            val data = ByteArray(len) { (it * 37 + 11).toByte() }
            assertContentEquals(data, Base32.decode(Base32.encode(data)), "length $len")
        }
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.encode(rfcSecret))
    }

    @Test
    fun accountListRoundTripsThroughJson() {
        val accounts = listOf(
            Account(issuer = "GitHub", label = "miyuki", secret = rfcSecret, policy = RemotePolicy.AUTO),
            Account(
                issuer = "", label = "old-vpn", secret = byteArrayOf(1, 2, 3), type = OtpType.HOTP,
                algorithm = OtpAlgorithm.SHA512, digits = 8, counter = 42, policy = RemotePolicy.NEVER,
            ),
        )
        assertEquals(accounts, AccountListCodec.decode(AccountListCodec.encode(accounts)))
    }

    @Test
    fun accountCodeUsesItsSettings() {
        val totp = Account(issuer = "x", label = "y", secret = rfcSecret, digits = 8)
        assertEquals("94287082", totp.code(59))
        val hotp = Account(issuer = "x", label = "y", secret = rfcSecret, type = OtpType.HOTP, counter = 1)
        assertEquals("287082", hotp.code(0))
    }

    @Test
    fun toStringNeverShowsSecret() {
        val a = Account(issuer = "GitHub", label = "me", secret = rfcSecret)
        assertFalse(a.toString().contains(Base32.encode(rfcSecret)))
    }

    @Test
    fun parsesTypicalOtpauthUri() {
        val a = OtpAuthUri.parse("otpauth://totp/GitHub:miyuki%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=GitHub")
        assertEquals("GitHub", a.issuer)
        assertEquals("miyuki@example.com", a.label)
        assertContentEquals(rfcSecret, a.secret)
        assertEquals(OtpType.TOTP, a.type)
        assertEquals(OtpAlgorithm.SHA1, a.algorithm)
        assertEquals(6, a.digits)
        assertEquals(30, a.period)
    }

    @Test
    fun parsesAllParametersAndOddFormatting() {
        val a = OtpAuthUri.parse(
            "  OTPAUTH://TOTP/My Bank: alice smith?SECRET=gezd gnbv-gy3t qojq&algorithm=sha256&digits=8&period=60  ",
        )
        assertEquals("My Bank", a.issuer)
        assertEquals("alice smith", a.label)
        assertEquals(OtpAlgorithm.SHA256, a.algorithm)
        assertEquals(8, a.digits)
        assertEquals(60, a.period)
        assertEquals(10, a.secret.size)
    }

    @Test
    fun issuerParameterWinsAndLabelOnlyWorks() {
        assertEquals("Real", OtpAuthUri.parse("otpauth://totp/Fake:bob?secret=AAAA&issuer=Real").issuer)
        val plain = OtpAuthUri.parse("otpauth://totp/bob?secret=AAAA")
        assertEquals("", plain.issuer)
        assertEquals("bob", plain.label)
        assertEquals("Acme Corp", OtpAuthUri.parse("otpauth://totp/x?secret=AAAA&issuer=Acme+Corp").issuer)
    }

    @Test
    fun parsesHotpWithCounter() {
        val a = OtpAuthUri.parse("otpauth://hotp/VPN:me?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&counter=5")
        assertEquals(OtpType.HOTP, a.type)
        assertEquals(5, a.counter)
        assertEquals("254676", a.code(0))
    }

    @Test
    fun rejectsBadOtpauthUris() {
        val bad = listOf(
            "https://example.com",
            "otpauth://totp/x",
            "otpauth://totp/x?secret=",
            "otpauth://totp/x?secret=not*base32",
            "otpauth://steam/x?secret=AAAA",
            "otpauth://hotp/x?secret=AAAA",
            "otpauth://totp/x?secret=AAAA&algorithm=MD5",
            "otpauth://totp/x?secret=AAAA&digits=4",
            "otpauth://totp/x?secret=AAAA&period=0",
            "otpauth://totp/x%ZZ?secret=AAAA",
        )
        for (uri in bad) assertFailsWith<IllegalArgumentException>(uri) { OtpAuthUri.parse(uri) }
    }

    @Test
    fun parsesGoogleAuthenticatorExample() {
        // Commonly cited example export: one TOTP account "Example:alice@google.com",
        // secret JBSWY3DPEHPK3PXP, SHA1, 6 digits.
        val uri = "otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8SGEV4YW1wbGU6YWxpY2VAZ29vZ2xlLmNvbRoHRXhhbXBsZSABKAEwAhABGAEgACjr4JKK%2Bf%2F%2F%2F%2F8B"
        assertTrue(GoogleAuthMigration.isMigrationUri(uri))
        val batch = GoogleAuthMigration.parse(uri)
        assertEquals(1, batch.accounts.size)
        val a = batch.accounts.single()
        assertEquals("Example", a.issuer)
        assertEquals("alice@google.com", a.label)
        assertEquals("JBSWY3DPEHPK3PXP", Base32.encode(a.secret))
        assertEquals(OtpType.TOTP, a.type)
        assertEquals(OtpAlgorithm.SHA1, a.algorithm)
        assertEquals(6, a.digits)
    }

    @Test
    fun parsesMultiAccountBatchAndSkipsUnsupported() {
        val payload = GoogleAuthMigration.encodePayload(
            listOf(
                mapOf(1 to rfcSecret, 2 to "GitHub:miyuki", 3 to "GitHub", 4 to 1, 5 to 1, 6 to 2),
                mapOf(1 to rfcSecret, 2 to "vpn", 4 to 3, 5 to 2, 6 to 1, 7 to 9),
                mapOf(1 to rfcSecret, 2 to "Legacy:old", 3 to "Legacy", 4 to 4, 5 to 1, 6 to 2),
            ),
            batchSize = 2, batchIndex = 1,
        )
        val uri = "otpauth-migration://offline?data=" + java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(payload), "UTF-8")
        val batch = GoogleAuthMigration.parse(uri)
        assertEquals(2, batch.batchSize)
        assertEquals(1, batch.batchIndex)
        assertEquals(2, batch.accounts.size)
        assertEquals("miyuki", batch.accounts[0].label)
        val hotp = batch.accounts[1]
        assertEquals(OtpType.HOTP, hotp.type)
        assertEquals(OtpAlgorithm.SHA512, hotp.algorithm)
        assertEquals(8, hotp.digits)
        assertEquals(9, hotp.counter)
        assertEquals(listOf("Legacy / old: MD5 is not supported"), batch.skipped)
    }

    @Test
    fun rejectsCorruptMigrationData() {
        assertFailsWith<IllegalArgumentException> { GoogleAuthMigration.parse("otpauth-migration://offline?data=CgX%2F") }
        assertFailsWith<IllegalArgumentException> { GoogleAuthMigration.parse("otpauth-migration://offline") }
        assertFailsWith<IllegalArgumentException> { GoogleAuthMigration.parse("otpauth://totp/x?secret=AAAA") }
    }

    private val fastKdf = Backup.KdfParams(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val accounts = listOf(Account(issuer = "GitHub", label = "me", secret = rfcSecret))

    @Test
    fun backupRoundTrips() {
        val file = Backup.encrypt(accounts, "correct horse".toCharArray(), fastKdf)
        assertEquals(accounts, Backup.decrypt(file, "correct horse".toCharArray()))
        assertFalse(String(file, Charsets.ISO_8859_1).contains("GitHub"), "plaintext must not be visible")
    }

    @Test
    fun backupRejectsWrongPasswordAndTampering() {
        val file = Backup.encrypt(accounts, "correct horse".toCharArray(), fastKdf)
        assertFailsWith<Backup.WrongPasswordOrCorrupted> { Backup.decrypt(file, "wrong horse!".toCharArray()) }
        val flippedBody = file.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<Backup.WrongPasswordOrCorrupted> { Backup.decrypt(flippedBody, "correct horse".toCharArray()) }
        // Changing a header field (here: iterations 1 -> 2) must also fail, not silently re-derive.
        val flippedHeader = file.copyOf().also { it[15] = 2 }
        assertFailsWith<Backup.WrongPasswordOrCorrupted> { Backup.decrypt(flippedHeader, "correct horse".toCharArray()) }
    }

    @Test
    fun backupRejectsJunkAndShortPasswords() {
        assertFailsWith<Backup.NotABackup> { Backup.decrypt("hello".toByteArray(), "whatever1".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { Backup.encrypt(accounts, "short".toCharArray(), fastKdf) }
        val hostile = Backup.encrypt(accounts, "correct horse".toCharArray(), fastKdf).also {
            // memoryKiB = 0x7fffffff
            it[8] = 0x7f; it[9] = -1; it[10] = -1; it[11] = -1
        }
        assertFailsWith<Backup.NotABackup> { Backup.decrypt(hostile, "correct horse".toCharArray()) }
    }
}
