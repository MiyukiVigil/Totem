package tech.miyukivigil.totem.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backup files (`.totem`).
 *
 * ```
 * "TOTEMBK" | version (1 byte) | argon2id memory KiB, iterations, parallelism (3 × int32 BE)
 *           | salt (16) | nonce (12) | AES-256-GCM ciphertext + tag
 * ```
 * The whole header is the GCM associated data, so changing any parameter breaks decryption.
 * The plaintext is [AccountListCodec] JSON. A fresh salt means a fresh key for every backup, so a
 * random 96-bit nonce is safe.
 */
object Backup {
    private val MAGIC = "TOTEMBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val HEADER_LEN = 7 + 1 + 12 + SALT_LEN + NONCE_LEN
    const val MIN_PASSWORD_LENGTH = 8

    /** 64 MiB, 3 passes: a few seconds on a phone, expensive for anyone guessing passwords. */
    data class KdfParams(val memoryKiB: Int = 64 * 1024, val iterations: Int = 3, val parallelism: Int = 1)

    class WrongPasswordOrCorrupted : Exception("wrong password, or the backup file is damaged")
    class NotABackup(message: String) : Exception(message)

    private val random = SecureRandom()

    fun encrypt(accounts: List<Account>, password: CharArray, params: KdfParams = KdfParams()): ByteArray {
        require(password.size >= MIN_PASSWORD_LENGTH) { "password must be at least $MIN_PASSWORD_LENGTH characters" }
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC).put(VERSION)
            .putInt(params.memoryKiB).putInt(params.iterations).putInt(params.parallelism)
            .put(salt).put(nonce)
            .array()
        val key = deriveKey(password, salt, params)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(AccountListCodec.encode(accounts))
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(file: ByteArray, password: CharArray): List<Account> {
        if (file.size < HEADER_LEN + 16 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw NotABackup("this is not a Totem backup file")
        }
        val buf = ByteBuffer.wrap(file, MAGIC.size, HEADER_LEN - MAGIC.size)
        val version = buf.get()
        if (version != VERSION) throw NotABackup("made by a newer Totem (backup format $version)")
        val params = KdfParams(buf.int, buf.int, buf.int)
        // Refuse parameters that would make us allocate gigabytes or spin for minutes.
        if (params.memoryKiB !in 8..(1024 * 1024) || params.iterations !in 1..20 || params.parallelism !in 1..8) {
            throw NotABackup("backup has unsupported key settings")
        }
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val nonce = ByteArray(NONCE_LEN).also { buf.get(it) }

        val key = deriveKey(password, salt, params)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(file, 0, HEADER_LEN)
            cipher.doFinal(file, HEADER_LEN, file.size - HEADER_LEN)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordOrCorrupted()
        } finally {
            key.fill(0)
        }
        try {
            return AccountListCodec.decode(plain)
        } finally {
            plain.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, p: KdfParams): ByteArray {
        val pw = String(password).toByteArray(Charsets.UTF_8)
        try {
            val gen = Argon2BytesGenerator()
            gen.init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withMemoryAsKB(p.memoryKiB)
                    .withIterations(p.iterations)
                    .withParallelism(p.parallelism)
                    .build(),
            )
            return ByteArray(32).also { gen.generateBytes(pw, it) }
        } finally {
            pw.fill(0)
        }
    }
}
