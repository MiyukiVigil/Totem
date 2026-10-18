package tech.miyukivigil.totem.relay

import android.util.Log
import tech.miyukivigil.totem.TotemApp
import tech.miyukivigil.totem.core.Account
import tech.miyukivigil.totem.core.AppMessages
import tech.miyukivigil.totem.core.AppMessages.Request
import tech.miyukivigil.totem.core.Otp
import tech.miyukivigil.totem.core.OtpType
import tech.miyukivigil.totem.core.RelayProtocol.ServerMsg
import tech.miyukivigil.totem.core.RemotePolicy
import tech.miyukivigil.totem.core.TotemNoise
import tech.miyukivigil.totem.core.Transport
import tech.miyukivigil.totem.device.PairedPc
import tech.miyukivigil.totem.push.Approvals
import tech.miyukivigil.totem.push.Notifications
import kotlin.math.abs

/**
 * Answers paired PCs on one relay connection until nothing arrives for [idleMs].
 *
 * Each PC session starts with a Noise IK handshake; the PC's static key must match the one saved
 * at pairing. After that, every request is one encrypted message and gets one encrypted reply.
 */
class RequestHandler(private val app: TotemApp, private val conn: RelayConnection) {
    private class Session(val pc: PairedPc, val transport: Transport)

    private val sessions = HashMap<String, Session>()

    fun run(idleMs: Long = 8_000, maxMs: Long = 150_000) {
        val stopAt = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < stopAt) {
            when (val m = conn.next(idleMs) ?: break) {
                is ServerMsg.Msg -> try {
                    handle(m)
                } catch (e: Exception) {
                    Log.w(TAG, "dropping message in session ${m.sid}", e)
                    sessions.remove(m.sid)
                }
                is ServerMsg.Error -> Log.w(TAG, "relay error: ${m.code} ${m.message}")
                else -> Unit
            }
        }
    }

    private fun handle(m: ServerMsg.Msg) {
        val pc = app.devices.get().pcs.firstOrNull { it.id == m.from } ?: return // not paired: ignore
        val session = sessions[m.sid]
        if (session == null) {
            val hs = TotemNoise.sessionResponder(app.devices.get().noisePrivate)
            hs.readMessage(m.data)
            if (!hs.remoteStatic().contentEquals(pc.noisePublic)) {
                Log.w(TAG, "session from ${pc.name}'s relay id but a different key; ignoring")
                return
            }
            conn.sendTo(pc.id, m.sid, hs.writeMessage())
            sessions[m.sid] = Session(pc, hs.split())
            return
        }
        val request = AppMessages.parseRequest(session.transport.decrypt(m.data))
        val reply = respond(request, pc)
        conn.sendTo(pc.id, m.sid, session.transport.encrypt(reply))
    }

    private fun respond(request: Request, pc: PairedPc): ByteArray {
        val now = System.currentTimeMillis() / 1000
        if (abs(now - request.ts) > AppMessages.MAX_CLOCK_SKEW_SECS) return AppMessages.denied(request.req, "clock_skew")
        if (!rememberRequest(request.req)) return AppMessages.denied(request.req, "replayed")
        app.vault.load()
        return when (request) {
            is Request.ListAccounts -> AppMessages.accounts(request.req, app.vault.accounts)
            is Request.GetCode -> {
                val account = app.vault.accounts.firstOrNull { it.id == request.account }
                    ?: return AppMessages.denied(request.req, "unknown_account")
                when (account.policy) {
                    RemotePolicy.NEVER -> return AppMessages.denied(request.req, "policy_never")
                    RemotePolicy.ASK -> when (Approvals.ask(app, request.req, pc.name, account.displayName)) {
                        true -> Unit
                        false -> return AppMessages.denied(request.req, "user_denied")
                        null -> return AppMessages.denied(request.req, "approval_timeout")
                    }
                    RemotePolicy.AUTO -> Unit
                }
                codeReply(request.req, account, now).also { Notifications.codeSent(app, pc.name, account.displayName) }
            }
        }
    }

    private fun codeReply(req: String, account: Account, now: Long): ByteArray = when (account.type) {
        OtpType.TOTP -> {
            val remaining = Otp.secondsRemaining(now, account.period.toLong())
            val next = if (remaining < 5) account.code(now + remaining) else null
            AppMessages.code(req, account.code(now), now + remaining, next)
        }
        OtpType.HOTP -> {
            // Hand out the current counter's code and move on, so it is never reused.
            val code = account.code(now)
            app.vault.replace(account.copy(counter = account.counter + 1))
            AppMessages.code(req, code, now + 300, null)
        }
    }

    private companion object {
        const val TAG = "TotemRequests"
        private val seen = LinkedHashSet<String>()

        /** False if this request id was already answered recently. */
        @Synchronized
        fun rememberRequest(req: String): Boolean {
            if (!seen.add(req)) return false
            if (seen.size > 500) seen.remove(seen.first())
            return true
        }
    }
}
