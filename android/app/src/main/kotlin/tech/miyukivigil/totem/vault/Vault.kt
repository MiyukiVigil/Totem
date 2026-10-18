package tech.miyukivigil.totem.vault

import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.AccountListCodec
import java.io.File

/**
 * The account list, stored as one encrypted file (`vault.bin`): `version (1) | iv | ciphertext`.
 * Small enough (a few KB) that rewriting the whole file on every change is simplest and keeps
 * writes atomic.
 *
 * All methods block on file I/O and the Keystore; call them off the main thread.
 */
class Vault(dir: File, private val crypto: VaultCrypto = VaultCrypto()) {
    sealed interface State {
        data object Loading : State
        data class Ready(val accounts: List<Account>) : State
        /** The file exists but can't be decrypted (e.g. the Keystore key was wiped). */
        data class Broken(val reason: String) : State
    }

    private val file = AtomicFile(File(dir, "vault.bin"))
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Current accounts, or an empty list while loading / broken. */
    val accounts: List<Account> get() = (_state.value as? State.Ready)?.accounts ?: emptyList()

    @Synchronized
    fun load() {
        if (_state.value is State.Ready) return
        _state.value = try {
            if (!file.baseFile.exists()) {
                State.Ready(emptyList())
            } else {
                val bytes = file.readFully()
                require(bytes.isNotEmpty() && bytes[0] == FORMAT) { "unknown vault format" }
                val plain = crypto.decrypt(bytes.copyOfRange(1, bytes.size), AAD)
                State.Ready(AccountListCodec.decode(plain).also { plain.fill(0) })
            }
        } catch (e: Exception) {
            Log.e(TAG, "vault could not be opened", e)
            State.Broken(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    @Synchronized
    fun add(newAccounts: List<Account>) = update { it + newAccounts }

    @Synchronized
    fun replace(account: Account) = update { list -> list.map { if (it.id == account.id) account else it } }

    @Synchronized
    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    /** Wipes everything, including the key. Only for recovering from [State.Broken]. */
    @Synchronized
    fun reset() {
        file.delete()
        crypto.deleteKey()
        _state.value = State.Ready(emptyList())
    }

    private fun update(change: (List<Account>) -> List<Account>) {
        val current = (_state.value as? State.Ready)?.accounts ?: error("vault is not open")
        val next = change(current)
        val plain = AccountListCodec.encode(next)
        val blob = byteArrayOf(FORMAT) + crypto.encrypt(plain, AAD)
        plain.fill(0)
        val out = file.startWrite()
        try {
            out.write(blob)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        _state.value = State.Ready(next)
    }

    private companion object {
        const val TAG = "TotemVault"
        const val FORMAT: Byte = 1
        val AAD = "totem-vault-v1".toByteArray()
    }
}
