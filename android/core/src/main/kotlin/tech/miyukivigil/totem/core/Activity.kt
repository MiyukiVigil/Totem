package tech.miyukivigil.totem.core

import org.json.JSONArray
import org.json.JSONObject

/** What a paired PC did, as shown in Settings → Activity. */
enum class ActivityKind {
    /** The PC fetched the account list (names only). */
    LIST,
    /** A code was sent. */
    CODE,
    /** A code request was refused (see [ActivityEntry.detail]). */
    DENIED,
    /** Something looked wrong; the user got a notification. */
    ALERT,
}

data class ActivityEntry(
    /** Unix seconds. */
    val at: Long,
    val pcId: String,
    /** The PC's name when this happened. */
    val pcName: String,
    val kind: ActivityKind,
    val account: String? = null,
    val detail: String? = null,
)

object ActivityCodec {
    /** Older entries are dropped beyond this. */
    const val MAX_ENTRIES = 500

    fun encode(entries: List<ActivityEntry>): String = JSONArray().apply {
        entries.takeLast(MAX_ENTRIES).forEach { e ->
            put(
                JSONObject().put("at", e.at).put("pc", e.pcId).put("pc_name", e.pcName).put("kind", e.kind.name.lowercase())
                    .apply {
                        e.account?.let { put("account", it) }
                        e.detail?.let { put("detail", it) }
                    },
            )
        }
    }.toString()

    fun decode(text: String): List<ActivityEntry> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val kind = ActivityKind.entries.firstOrNull { it.name.equals(o.optString("kind"), ignoreCase = true) } ?: return@mapNotNull null
            ActivityEntry(
                o.getLong("at"), o.getString("pc"), o.optString("pc_name"), kind,
                o.optString("account").ifEmpty { null }, o.optString("detail").ifEmpty { null },
            )
        }
    }
}

/**
 * Spots a paired PC behaving unlike its owner, using only the activity log (so it holds across
 * app restarts): a burst of code requests pauses that PC for a while.
 */
object RequestGuard {
    /** More codes than this within [WINDOW_SECS] pauses the PC. */
    const val MAX_CODES = 10
    const val WINDOW_SECS = 5 * 60L
    const val PAUSE_SECS = 15 * 60L
    const val PAUSED = "paused"

    sealed interface Verdict {
        data object Allow : Verdict
        /** Still paused from an earlier burst, until [until]. */
        data class Paused(val until: Long) : Verdict
        /** This request is the one that tipped it over: pause now and tell the user. */
        data object PauseNow : Verdict
    }

    /** Decides on a code request from [pcId] at [now], given the log so far. */
    fun check(log: List<ActivityEntry>, pcId: String, now: Long): Verdict {
        val mine = log.filter { it.pcId == pcId }
        val lastPause = mine.lastOrNull { it.kind == ActivityKind.ALERT && it.detail == PAUSED }
        if (lastPause != null && now - lastPause.at < PAUSE_SECS) return Verdict.Paused(lastPause.at + PAUSE_SECS)
        val since = maxOf(now - WINDOW_SECS, lastPause?.let { it.at + PAUSE_SECS } ?: Long.MIN_VALUE)
        val recent = mine.count { it.kind == ActivityKind.CODE && it.at > since }
        return if (recent >= MAX_CODES) Verdict.PauseNow else Verdict.Allow
    }
}

/** When to forget a paired PC that hasn't been used. */
object PcExpiry {
    /** Choices offered in Settings; 0 = never. */
    val CHOICES_DAYS = listOf(30, 90, 365, 0)
    const val DEFAULT_DAYS = 90

    fun isExpired(pairedAt: Long, lastUsedAt: Long, forgetAfterDays: Int, now: Long): Boolean =
        forgetAfterDays > 0 && now - maxOf(pairedAt, lastUsedAt) > forgetAfterDays * 86_400L
}
