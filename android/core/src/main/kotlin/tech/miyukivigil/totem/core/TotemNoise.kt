package tech.miyukivigil.totem.core

import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import java.security.MessageDigest

/**
 * Noise handshakes. Mirrors `protocol/src/noise.rs`; see there for the design.
 *
 * Everything uses `Noise_IK_25519_ChaChaPoly_SHA256`. Pairing and sessions use different
 * prologues so a message from one can never be accepted as the other.
 */
object TotemNoise {
    const val PROTOCOL = "Noise_IK_25519_ChaChaPoly_SHA256"
    val PAIRING_PROLOGUE = "Totem/1 pairing".toByteArray()
    val SESSION_PROLOGUE = "Totem/1 session".toByteArray()
    const val PAIRING_SECRET_LEN = 32
    const val KEY_LEN = 32
    const val MAX_MESSAGE_LEN = 65535

    class KeyPair(val private: ByteArray, val public: ByteArray)

    fun generateKeyPair(): KeyPair {
        val dh = Noise.createDH("25519")
        try {
            dh.generateKeyPair()
            val private = ByteArray(KEY_LEN).also { dh.getPrivateKey(it, 0) }
            val public = ByteArray(KEY_LEN).also { dh.getPublicKey(it, 0) }
            return KeyPair(private, public)
        } finally {
            dh.destroy()
        }
    }

    /** Phone side of pairing. [pcPublic] comes from the QR code. */
    fun pairingInitiator(phonePrivate: ByteArray, pcPublic: ByteArray) =
        Handshake(HandshakeState.INITIATOR, PAIRING_PROLOGUE, phonePrivate, pcPublic)

    /** PC side of pairing. */
    fun pairingResponder(pcPrivate: ByteArray) =
        Handshake(HandshakeState.RESPONDER, PAIRING_PROLOGUE, pcPrivate, null)

    /** PC side of a session. [phonePublic] was learned during pairing. */
    fun sessionInitiator(pcPrivate: ByteArray, phonePublic: ByteArray) =
        Handshake(HandshakeState.INITIATOR, SESSION_PROLOGUE, pcPrivate, phonePublic)

    /** Phone side of a session. */
    fun sessionResponder(phonePrivate: ByteArray) =
        Handshake(HandshakeState.RESPONDER, SESSION_PROLOGUE, phonePrivate, null)

    /** Constant-time check of the pairing secret the phone sent against the one in the QR code. */
    fun pairingSecretMatches(expected: ByteArray, received: ByteArray): Boolean =
        expected.size == PAIRING_SECRET_LEN &&
            received.size == PAIRING_SECRET_LEN &&
            MessageDigest.isEqual(expected, received)

    /** Six-digit code both screens show after pairing, from the first 4 bytes of the handshake hash. */
    fun confirmationCode(handshakeHash: ByteArray): String {
        val n = ((handshakeHash[0].toLong() and 0xff) shl 24) or
            ((handshakeHash[1].toLong() and 0xff) shl 16) or
            ((handshakeHash[2].toLong() and 0xff) shl 8) or
            (handshakeHash[3].toLong() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }
}

/** A Noise handshake in progress. Throws on any failure; a failed handshake cannot be reused. */
class Handshake internal constructor(
    role: Int,
    prologue: ByteArray,
    localPrivate: ByteArray,
    remotePublic: ByteArray?,
) {
    private val state = HandshakeState(TotemNoise.PROTOCOL, role).apply {
        setPrologue(prologue, 0, prologue.size)
        localKeyPair.setPrivateKey(localPrivate, 0)
        if (remotePublic != null) remotePublicKey.setPublicKey(remotePublic, 0)
        start()
    }

    val isMyTurn: Boolean get() = state.action == HandshakeState.WRITE_MESSAGE
    val isFinished: Boolean get() = state.action == HandshakeState.SPLIT

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        check(isMyTurn) { "not our turn to write (action ${state.action})" }
        val buf = ByteArray(TotemNoise.MAX_MESSAGE_LEN)
        val n = state.writeMessage(buf, 0, payload, 0, payload.size)
        return buf.copyOf(n)
    }

    /** Reads a handshake message and returns its payload. */
    fun readMessage(message: ByteArray): ByteArray {
        check(state.action == HandshakeState.READ_MESSAGE) { "not our turn to read (action ${state.action})" }
        val buf = ByteArray(TotemNoise.MAX_MESSAGE_LEN)
        val n = state.readMessage(message, 0, message.size, buf, 0)
        return buf.copyOf(n)
    }

    fun handshakeHash(): ByteArray {
        check(isFinished)
        return state.handshakeHash
    }

    fun remoteStatic(): ByteArray {
        check(isFinished)
        return ByteArray(TotemNoise.KEY_LEN).also { state.remotePublicKey.getPublicKey(it, 0) }
    }

    /** Ends the handshake. Read [handshakeHash] and [remoteStatic] first. */
    fun split(): Transport {
        check(isFinished)
        val pair = state.split()
        state.destroy()
        return Transport(pair.sender, pair.receiver)
    }
}

/** An established session. Each message can be decrypted exactly once, in order. */
class Transport internal constructor(private val sender: CipherState, private val receiver: CipherState) {
    fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + sender.macLength)
        val n = sender.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size)
        return out.copyOf(n)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val out = ByteArray(ciphertext.size)
        val n = receiver.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size)
        return out.copyOf(n)
    }

    fun close() {
        sender.destroy()
        receiver.destroy()
    }
}
