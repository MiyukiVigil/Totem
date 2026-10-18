package tech.miyukivigil.totem.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Kotlin ↔ Kotlin handshakes; the same cases as the Rust unit tests in `protocol/src/noise.rs`. */
class TotemNoiseTest {
    private class Done(val initiator: Handshake, val responder: Handshake, val firstPayload: ByteArray)

    private fun run(i: Handshake, r: Handshake, firstPayload: ByteArray = ByteArray(0)): Done {
        val received = r.readMessage(i.writeMessage(firstPayload))
        i.readMessage(r.writeMessage())
        assertTrue(i.isFinished && r.isFinished)
        return Done(i, r, received)
    }

    @Test
    fun pairingDeliversSecretAndCodesMatch() {
        val phone = TotemNoise.generateKeyPair()
        val pc = TotemNoise.generateKeyPair()
        val secret = ByteArray(32) { 7 }
        val done = run(TotemNoise.pairingInitiator(phone.private, pc.public), TotemNoise.pairingResponder(pc.private), secret)
        assertTrue(TotemNoise.pairingSecretMatches(secret, done.firstPayload))
        assertContentEquals(phone.public, done.responder.remoteStatic())
        assertEquals(
            TotemNoise.confirmationCode(done.initiator.handshakeHash()),
            TotemNoise.confirmationCode(done.responder.handshakeHash()),
        )
    }

    @Test
    fun pairingSecretCheckRejectsWrongOrShortSecret() {
        val secret = ByteArray(32) { 7 }
        val wrong = secret.copyOf().also { it[31] = 6 }
        assertFalse(TotemNoise.pairingSecretMatches(secret, wrong))
        assertFalse(TotemNoise.pairingSecretMatches(secret, secret.copyOf(31)))
        assertFalse(TotemNoise.pairingSecretMatches(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun pairingFailsIfQrKeyIsNotThePcs() {
        val phone = TotemNoise.generateKeyPair()
        val pc = TotemNoise.generateKeyPair()
        val other = TotemNoise.generateKeyPair()
        assertFailsWith<Exception> {
            run(TotemNoise.pairingInitiator(phone.private, other.public), TotemNoise.pairingResponder(pc.private))
        }
    }

    @Test
    fun sessionFailsIfPcHasWrongPhoneKey() {
        val pc = TotemNoise.generateKeyPair()
        val phone = TotemNoise.generateKeyPair()
        val impostor = TotemNoise.generateKeyPair()
        assertFailsWith<Exception> {
            run(TotemNoise.sessionInitiator(pc.private, impostor.public), TotemNoise.sessionResponder(phone.private))
        }
    }

    @Test
    fun pairingMessageIsNotAcceptedAsSession() {
        val phone = TotemNoise.generateKeyPair()
        val pc = TotemNoise.generateKeyPair()
        assertFailsWith<Exception> {
            run(TotemNoise.pairingInitiator(phone.private, pc.public), TotemNoise.sessionResponder(pc.private))
        }
    }

    @Test
    fun transportRejectsReplayedMessage() {
        val pc = TotemNoise.generateKeyPair()
        val phone = TotemNoise.generateKeyPair()
        val done = run(TotemNoise.sessionInitiator(pc.private, phone.public), TotemNoise.sessionResponder(phone.private))
        val i = done.initiator.split()
        val r = done.responder.split()
        val ct = i.encrypt("get_code".toByteArray())
        assertEquals("get_code", String(r.decrypt(ct)))
        assertFailsWith<Exception>("replay must fail") { r.decrypt(ct) }
    }
}
