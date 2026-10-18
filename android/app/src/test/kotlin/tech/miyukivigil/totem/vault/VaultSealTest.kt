package tech.miyukivigil.totem.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.OtpType
import tech.miyukivigil.totem.core.RemotePolicy
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The extra lock for phone-only accounts, with a fake Keystore key that can be "locked"
 * (no recent unlock) or "deleted by Android". The point of these: no path loses an account.
 */
@RunWith(RobolectricTestRunner::class)
class VaultSealTest {
    @get:Rule val tmp = TemporaryFolder()

    /** AES-GCM like the real thing; [unlocked] and [invalidated] mimic an unlock-bound key. */
    class FakeBox(private val unlockBound: Boolean) : SecretBox {
        var key: SecretKey? = null
        var unlocked = true
        var invalidated = false

        private fun usable(): SecretKey {
            val k = key ?: throw IllegalStateException("no key")
            if (invalidated) throw KeyPermanentlyInvalidatedException()
            if (unlockBound && !unlocked) throw UserNotAuthenticatedException()
            return k
        }

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
            if (key == null && !unlockBound) createKeyNow()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, usable())
            c.updateAAD(aad)
            return c.iv + c.doFinal(plaintext)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, usable(), GCMParameterSpec(128, blob, 0, 12))
            c.updateAAD(aad)
            return c.doFinal(blob, 12, blob.size - 12)
        }

        override fun hasKey() = key != null
        override fun createKeyNow() {
            if (key == null) key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        }
        override fun deleteKey() {
            key = null
        }
    }

    private val main = FakeBox(unlockBound = false)
    private val seal = FakeBox(unlockBound = true)
    private val dir: File get() = tmp.root

    /** Plain temp-file-and-move: android.util.AtomicFile's rename can't replace files on Windows. */
    class PlainFile(private val f: File) : Vault.BlobFile {
        override fun exists() = f.exists()
        override fun read() = f.readBytes()
        override fun delete() {
            f.delete()
        }
        override fun write(bytes: ByteArray) {
            val tmp = File(f.path + ".tmp").apply { writeBytes(bytes) }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** A fresh Vault over the same files and keys: like the app process starting again. */
    private fun open() = vaultWith(main, seal).apply { load() }

    private fun vaultWith(m: SecretBox, s: SecretBox) = Vault(dir, m, s, ::PlainFile)

    private fun account(name: String, policy: RemotePolicy, type: OtpType = OtpType.TOTP) =
        Account(issuer = name, label = "me", secret = name.toByteArray() + ByteArray(10), policy = policy, type = type)

    private val github = account("GitHub", RemotePolicy.AUTO)
    private val bank = account("Bank", RemotePolicy.ASK)
    private val vault = account("Vault", RemotePolicy.NEVER)
    private val counter = account("Counter", RemotePolicy.AUTO, OtpType.HOTP)
    private val all = listOf(github, bank, vault, counter)

    private fun names(v: Vault) = v.accounts.map { it.issuer }.sorted()

    @Test
    fun withoutSealEverythingIsInTheMainFile() {
        open().add(all)
        assertEquals(names(open()), all.map { it.issuer }.sorted())
        assertEquals(Vault.Seal.Off, open().seal.value)
        assertFalse(File(dir, "sealed.bin").exists())
    }

    @Test
    fun sealedAccountsAreAbsentInTheBackgroundAndBackAfterUnlock() {
        open().apply { add(all); enableSeal() }
        assertTrue(File(dir, "sealed.bin").exists())

        // Push wakes the phone hours later: no recent unlock.
        seal.unlocked = false
        val background = open()
        assertEquals(Vault.Seal.Locked, background.seal.value)
        assertEquals(listOf("Bank", "Counter", "GitHub"), names(background))
        // Answering a PC still works, including HOTP counter updates.
        background.replace(counter.copy(counter = 1))
        background.unseal() // no recent unlock: stays locked, nothing lost
        assertEquals(Vault.Seal.Locked, background.seal.value)

        // The user opens Totem and unlocks.
        seal.unlocked = true
        val ui = open().apply { unseal() }
        assertEquals(Vault.Seal.Open, ui.seal.value)
        assertEquals(all.map { it.issuer }.sorted(), names(ui))
        assertEquals(1L, ui.accounts.first { it.id == counter.id }.counter)
    }

    @Test
    fun editAfterTheUnlockWindowIsSealedOnTheNextUnlock() {
        val v = open().apply { add(listOf(github)); enableSeal() }
        seal.unlocked = false // five minutes passed
        v.add(listOf(vault)) // can't seal now: kept in the main file
        assertEquals(listOf("GitHub", "Vault"), names(v))

        // Still readable after a restart even while locked (it's in the main file for now).
        assertEquals(listOf("GitHub", "Vault"), names(open()))

        seal.unlocked = true
        open().unseal()
        seal.unlocked = false
        assertEquals("now sealed, so hidden while locked", listOf("GitHub"), names(open()))
        seal.unlocked = true
        assertEquals(listOf("GitHub", "Vault"), names(open().apply { unseal() }))
    }

    @Test
    fun changingPolicyMovesAccountsBetweenFiles() {
        val v = open().apply { add(all); enableSeal() }
        v.replace(vault.copy(policy = RemotePolicy.AUTO))
        v.replace(github.copy(policy = RemotePolicy.NEVER))
        seal.unlocked = false
        assertEquals(listOf("Bank", "Counter", "Vault"), names(open()))
        seal.unlocked = true
        assertEquals(all.map { it.issuer }.sorted(), names(open().apply { unseal() }))
    }

    @Test
    fun deletingASealedAccount() {
        val v = open().apply { add(all); enableSeal() }
        v.delete(vault.id)
        assertEquals(listOf("Bank", "Counter", "GitHub"), names(open().apply { unseal() }))
    }

    @Test
    fun turningItOffMovesEverythingBack() {
        open().apply { add(all); enableSeal() }
        open().apply { unseal(); disableSeal() }
        assertFalse(File(dir, "sealed.bin").exists())
        assertFalse(seal.hasKey())
        seal.unlocked = false
        val v = open()
        assertEquals(Vault.Seal.Off, v.seal.value)
        assertEquals(all.map { it.issuer }.sorted(), names(v))
    }

    @Test
    fun aDeletedKeyIsReportedAndTheFileKept() {
        open().apply { add(all); enableSeal() }
        seal.invalidated = true // e.g. the screen lock was removed
        val v = open().apply { unseal() }
        assertTrue(v.seal.value is Vault.Seal.Broken)
        assertEquals("the other accounts still work", listOf("Bank", "Counter", "GitHub"), names(v))
        v.add(listOf(account("New", RemotePolicy.AUTO)))
        assertTrue("sealed file untouched while broken", File(dir, "sealed.bin").exists())

        v.disableSeal()
        assertTrue(File(dir, "sealed-unreadable.bin").exists())
        assertEquals(listOf("Bank", "Counter", "GitHub", "New"), names(open()))
    }

    @Test
    fun crashBetweenTheTwoWritesLeavesNoDuplicate() {
        open().apply { add(all); enableSeal() }
        // Simulate: sealed.bin written with Vault, then the process died before vault.bin dropped it.
        val withDuplicate = vaultWith(main, seal).apply { load(); unseal() }
        val mainCopy = FakeBox(false).also { it.key = main.key }
        vaultWith(mainCopy, FakeBox(true)).apply {
            load()
            add(listOf(vault)) // seal "Off" in this instance's view: goes to vault.bin
        }
        assertEquals(all.size, withDuplicate.accounts.size)
        val reopened = open().apply { unseal() }
        assertEquals(all.map { it.issuer }.sorted(), names(reopened))
    }
}
