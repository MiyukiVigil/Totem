package tech.miyukivigil.totem.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForeignImportTest {
    private val samples = File(System.getProperty("totem.importSamples") ?: "missing")

    private fun sample(name: String): ByteArray {
        val f = File(samples, name)
        assumeTrue(f.exists(), "run scripts/fetch-import-samples.sh for the real-app samples")
        return f.readBytes()
    }

    private fun read(bytes: ByteArray, password: String? = null): ForeignImport.Result {
        val detected = assertNotNull(ForeignImport.detect(bytes))
        return ForeignImport.read(detected, password?.toCharArray())
    }

    private fun summary(r: ForeignImport.Result) =
        r.accounts.map { "${it.issuer}|${it.label}|${Base32.encode(it.secret)}|${it.type}|${it.algorithm}|${it.digits}|${it.period}|${it.counter}" }

    @Test
    fun builtInAegisSample() {
        val json = """
            {"version":1,"header":{"slots":null,"params":null},"db":{"version":2,"entries":[
              {"type":"totp","uuid":"x","name":"me@example.com","issuer":"GitHub","info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA256","digits":8,"period":60}},
              {"type":"hotp","uuid":"y","name":"bob","issuer":"Bank","info":{"secret":"GEZDGNBV","algo":"SHA1","digits":6,"counter":7}},
              {"type":"steam","uuid":"z","name":"gamer","issuer":"Steam","info":{"secret":"GEZDGNBV","algo":"SHA1","digits":5,"period":30}}
            ]}}
        """.trimIndent().toByteArray()
        val d = assertNotNull(ForeignImport.detect(json))
        assertEquals(ForeignImport.Format.AEGIS, d.format)
        assertEquals(false, d.needsPassword)
        val r = ForeignImport.read(d)
        assertEquals(
            listOf("GitHub|me@example.com|JBSWY3DPEHPK3PXP|TOTP|SHA256|8|60|0", "Bank|bob|GEZDGNBV|HOTP|SHA1|6|30|7"),
            summary(r),
        )
        assertEquals(listOf("Steam gamer (steam)"), r.skipped)
    }

    @Test
    fun builtInTwoFasSample() {
        val json = """
            {"services":[{"name":"Deno","secret":"4SJHB4GSD43FZBAI7C2HLRJGPQ","otp":{"label":"Deno:Mason","account":"Mason","issuer":"Deno","digits":6,"period":30,"algorithm":"SHA1","tokenType":"TOTP"}}],
             "schemaVersion":4}
        """.trimIndent().toByteArray()
        assertEquals(listOf("Deno|Mason|4SJHB4GSD43FZBAI7C2HLRJGPQ|TOTP|SHA1|6|30|0"), summary(read(json)))
    }

    @Test
    fun otherFilesAreNotDetected() {
        assertNull(ForeignImport.detect("not json".toByteArray()))
        assertNull(ForeignImport.detect("""{"version":1,"accounts":[]}""".toByteArray()))
        assertNull(ForeignImport.detect(byteArrayOf(0x54, 0x4f, 0x54, 0x45, 0x4d, 0x42, 0x4b, 1)))
    }

    @Test
    fun realAegisEncryptedMatchesPlain() {
        val plain = read(sample("aegis_plain.json"))
        val enc = sample("aegis_encrypted.json")
        assertTrue(assertNotNull(ForeignImport.detect(enc)).needsPassword)
        assertEquals(summary(plain), summary(read(enc, "test")))
        assertTrue(plain.accounts.size >= 5, "${plain.accounts.size} accounts")
        assertTrue(plain.skipped.any { "steam" in it })
        assertFailsWith<ForeignImport.WrongPassword> { read(enc, "nope") }
    }

    @Test
    fun realTwoFasEncryptedMatchesPlain() {
        for ((plainName, encName) in listOf(
            "2fas_authenticator_plain_v4.2fas" to "2fas_authenticator_encrypted_v4.2fas",
            "2fas_authenticator_plain.2fas" to "2fas_authenticator_encrypted.2fas",
        )) {
            val plain = read(sample(plainName))
            val enc = sample(encName)
            assertTrue(assertNotNull(ForeignImport.detect(enc)).needsPassword, encName)
            assertEquals(summary(plain), summary(read(enc, "test")), encName)
            assertTrue(plain.accounts.isNotEmpty())
            assertFailsWith<ForeignImport.WrongPassword> { read(enc, "nope") }
        }
        // Same account in both apps' samples, so both importers agree on the fields.
        val deno = read(sample("2fas_authenticator_plain_v4.2fas")).accounts.first { it.issuer == "Deno" }
        assertEquals("Mason", deno.label)
        assertEquals("4SJHB4GSD43FZBAI7C2HLRJGPQ", Base32.encode(deno.secret))
    }
}
