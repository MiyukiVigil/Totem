package tech.miyukivigil.totem.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.AccountListCodec
import tech.miyukivigil.totem.core.RemotePolicy
import java.io.File

/**
 * The account list, stored as one encrypted file (`vault.bin`): `version (1) | iv | ciphertext`.
 * Small enough (a few KB) that rewriting the whole file on every change is simplest and keeps
 * writes atomic.
 *
 * Optional extra lock ("seal"): phone-only ([RemotePolicy.NEVER]) accounts move to `sealed.bin`,
 * under a second Keystore key that only works within [SEAL_UNLOCK_SECS] of the user unlocking.
 * The phone answers PCs in the background with the main key alone, so it never needs these.
 * Until [unseal] runs after the app lock, sealed accounts are simply absent from [accounts].
 *
 * All methods block on file I/O and the Keystore; call them off the main thread.
 */
class Vault(
    private val dir: File,
    private val crypto: SecretBox = VaultCrypto(),
    private val sealCrypto: SecretBox = VaultCrypto(SEAL_ALIAS, requireUnlockSecs = SEAL_UNLOCK_SECS),
    private val openFile: (File) -> BlobFile = ::AtomicBlobFile,
) {
    /** Whole-file reads and atomic replacement. */
    interface BlobFile {
        fun exists(): Boolean
        fun read(): ByteArray
        fun write(bytes: ByteArray)
        fun delete()
    }

    class AtomicBlobFile(f: File) : BlobFile {
        private val atomic = AtomicFile(f)
        override fun exists() = atomic.baseFile.exists()
        override fun read(): ByteArray = atomic.readFully()
        override fun delete() = atomic.delete()
        override fun write(bytes: ByteArray) {
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
        }
    }

    sealed interface State {
        data object Loading : State
        data class Ready(val accounts: List<Account>) : State
        /** The file exists but can't be decrypted (e.g. the Keystore key was wiped). */
        data class Broken(val reason: String) : State
    }

    sealed interface Seal {
        /** Extra lock not in use. */
        data object Off : Seal
        /** In use; sealed accounts not loaded yet (needs a recent unlock). */
        data object Locked : Seal
        data object Open : Seal
        /** In use, but its key is gone (e.g. the screen lock was removed). Restore from a backup. */
        data class Broken(val reason: String) : Seal
    }

    private val file = openFile(File(dir, "vault.bin"))
    private val sealedFile = openFile(File(dir, "sealed.bin"))
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _seal = MutableStateFlow<Seal>(Seal.Off)
    val seal: StateFlow<Seal> = _seal.asStateFlow()

    /** Current accounts, or an empty list while loading / broken. */
    val accounts: List<Account> get() = (_state.value as? State.Ready)?.accounts ?: emptyList()

    private val sealOn get() = sealCrypto.hasKey()

    @Synchronized
    fun load() {
        if (_state.value is State.Ready) return
        _state.value = try {
            State.Ready(read(file, crypto, AAD))
        } catch (e: Exception) {
            Log.e(TAG, "vault could not be opened", e)
            State.Broken(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
        if (_seal.value !is Seal.Broken) _seal.value = if (sealOn) Seal.Locked else Seal.Off
    }

    /**
     * Call right after the user unlocks Totem: loads sealed accounts, and seals any phone-only
     * account that couldn't be sealed earlier (e.g. edited after the unlock window ran out).
     */
    @Synchronized
    fun unseal() {
        load()
        if (_state.value !is State.Ready || !sealOn) return
        if (_seal.value == Seal.Locked) {
            try {
                val sealed = read(sealedFile, sealCrypto, SEALED_AAD)
                // After a crash mid-save an account can be in both files; the main copy wins.
                val main = accounts
                _state.value = State.Ready(main + sealed.filter { s -> main.none { it.id == s.id } })
                _seal.value = Seal.Open
            } catch (e: UserNotAuthenticatedException) {
                Log.i(TAG, "sealed accounts need a fresh unlock")
                return
            } catch (e: Exception) {
                Log.e(TAG, "sealed accounts could not be opened", e)
                _seal.value = Seal.Broken(
                    if (e is KeyPermanentlyInvalidatedException) "Android deleted the key (was the screen lock removed?)"
                    else e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""),
                )
                return
            }
        }
        if (_seal.value == Seal.Open && accounts.any { it.policy == RemotePolicy.NEVER && it.id in mainIds }) update { it }
    }

    /** Turns the extra lock on. Needs a screen lock and a recent unlock. */
    @Synchronized
    fun enableSeal() {
        check(_state.value is State.Ready) { "vault is not open" }
        sealCrypto.createKeyNow()
        _seal.value = Seal.Open
        update { it }
    }

    /** Moves everything back into the main file and deletes the extra key. Needs a recent unlock. */
    @Synchronized
    fun disableSeal() {
        if (_seal.value == Seal.Locked) unseal()
        check(_seal.value == Seal.Open || _seal.value is Seal.Broken) { "unlock Totem first" }
        val wasBroken = _seal.value is Seal.Broken
        _seal.value = Seal.Off
        update { it } // everything in memory goes to vault.bin
        // An unreadable sealed file is kept aside rather than deleted, in case it can be recovered.
        if (wasBroken) File(dir, "sealed.bin").renameTo(File(dir, "sealed-unreadable.bin")) else sealedFile.delete()
        sealCrypto.deleteKey()
    }

    @Synchronized
    fun add(newAccounts: List<Account>) = update { it + newAccounts }

    @Synchronized
    fun replace(account: Account) = update { list -> list.map { if (it.id == account.id) account else it } }

    @Synchronized
    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    /** Wipes everything, including the keys. Only for recovering from [State.Broken]. */
    @Synchronized
    fun reset() {
        file.delete()
        sealedFile.delete()
        crypto.deleteKey()
        sealCrypto.deleteKey()
        _state.value = State.Ready(emptyList())
        _seal.value = Seal.Off
    }

    /** Ids currently stored in vault.bin (rather than sealed.bin). */
    private var mainIds: Set<String> = emptySet()

    private fun update(change: (List<Account>) -> List<Account>) {
        val current = (_state.value as? State.Ready)?.accounts ?: error("vault is not open")
        val next = change(current)
        var toMain = next
        if (_seal.value == Seal.Open) {
            val toSeal = next.filter { it.policy == RemotePolicy.NEVER }
            try {
                // Sealed file first: a crash between the two writes leaves a duplicate, never a loss.
                write(sealedFile, sealCrypto, SEALED_AAD, toSeal)
                toMain = next - toSeal.toSet()
            } catch (e: UserNotAuthenticatedException) {
                // Unlock window over: keep them in the main file until the next unlock.
                Log.i(TAG, "couldn't seal now; will retry after the next unlock")
            }
        }
        // Locked or Broken: sealed accounts aren't in memory, so sealed.bin is left untouched.
        write(file, crypto, AAD, toMain)
        mainIds = toMain.map { it.id }.toSet()
        _state.value = State.Ready(next)
    }

    private fun read(f: BlobFile, c: SecretBox, aad: ByteArray): List<Account> {
        if (!f.exists()) return emptyList()
        val bytes = f.read()
        require(bytes.isNotEmpty() && bytes[0] == FORMAT) { "unknown vault format" }
        val plain = c.decrypt(bytes.copyOfRange(1, bytes.size), aad)
        return AccountListCodec.decode(plain).also {
            plain.fill(0)
            if (f === file) mainIds = it.map { a -> a.id }.toSet()
        }
    }

    private fun write(f: BlobFile, c: SecretBox, aad: ByteArray, list: List<Account>) {
        val plain = AccountListCodec.encode(list)
        val blob = try {
            byteArrayOf(FORMAT) + c.encrypt(plain, aad)
        } finally {
            plain.fill(0)
        }
        f.write(blob)
    }

    companion object {
        private const val TAG = "TotemVault"
        private const val FORMAT: Byte = 1
        private val AAD = "totem-vault-v1".toByteArray()
        private val SEALED_AAD = "totem-sealed-v1".toByteArray()
        private const val SEAL_ALIAS = "totem_sealed_v1"
        /** How long after an unlock sealed accounts can be read or saved. */
        const val SEAL_UNLOCK_SECS = 300
    }
}
