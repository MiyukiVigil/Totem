package tech.miyukivigil.totem.device

import android.util.AtomicFile
import android.util.Log
import tech.miyukivigil.totem.core.ActivityCodec
import tech.miyukivigil.totem.core.ActivityEntry
import tech.miyukivigil.totem.vault.VaultCrypto
import java.io.File

/**
 * What paired PCs did (Settings → Activity), in `activity.bin`, encrypted with the vault's
 * Keystore key (its own associated data). Losing it loses nothing but history, so a file that
 * can't be read is simply started over.
 */
class ActivityStore(dir: File, private val crypto: VaultCrypto = VaultCrypto()) {
    private val file = AtomicFile(File(dir, "activity.bin"))
    private var cached: List<ActivityEntry>? = null

    /** Oldest first. Blocking. */
    @Synchronized
    fun entries(): List<ActivityEntry> {
        cached?.let { return it }
        val loaded = try {
            if (file.baseFile.exists()) ActivityCodec.decode(String(crypto.decrypt(file.readFully(), AAD))) else emptyList()
        } catch (e: Exception) {
            Log.w("TotemActivity", "activity log unreadable; starting a new one", e)
            emptyList()
        }
        cached = loaded
        return loaded
    }

    @Synchronized
    fun add(entry: ActivityEntry) {
        val next = (entries() + entry).takeLast(ActivityCodec.MAX_ENTRIES)
        val out = file.startWrite()
        try {
            out.write(crypto.encrypt(ActivityCodec.encode(next).toByteArray(), AAD))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        cached = next
    }

    @Synchronized
    fun clear() {
        file.delete()
        cached = emptyList()
    }

    private companion object {
        val AAD = "totem-activity-v1".toByteArray()
    }
}
