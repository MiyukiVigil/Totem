package tech.miyukivigil.totem.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Handshakes between this Kotlin code and the Rust `noise_peer` example (see its doc comment for
 * the line protocol). Skipped when the peer has not been built; `scripts/interop.sh` builds it.
 */
@Timeout(20)
class RustInteropTest {
    private val peerPath = File(System.getProperty("totem.rustPeer"))
    private var peer: Peer? = null

    @BeforeEach
    fun requirePeer() = assumeTrue(peerPath.exists(), "Rust peer not built at $peerPath")

    @AfterTest
    fun stopPeer() {
        peer?.process?.destroyForcibly()
    }

    private class Peer(val process: Process) {
        private val out = process.inputStream.bufferedReader()
        private val input = process.outputStream.bufferedWriter()

        fun sendMsg(bytes: ByteArray) {
            input.write("MSG ${bytes.hex()}\n")
            input.flush()
        }

        /** Reads the next line, which must start with [tag]; returns the rest. */
        fun expect(tag: String): String {
            val line = out.readLine() ?: fail("Rust peer closed stdout (expected $tag)")
            if (line.startsWith("ERR ")) fail("Rust peer error: ${line.substring(4)}")
            assertTrue(line.startsWith("$tag "), "expected $tag, got: $line")
            return line.substring(tag.length + 1)
        }

        fun recvMsg(): ByteArray = expect("MSG").unhex()

        /** Waits for the peer to fail and returns its error text. */
        fun expectError(): String {
            while (true) {
                val line = out.readLine() ?: fail("Rust peer exited without an error")
                if (line.startsWith("ERR ")) {
                    process.waitFor(5, TimeUnit.SECONDS)
                    assertEquals(1, process.exitValue())
                    return line.substring(4)
                }
            }
        }

        fun finish() {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Rust peer did not exit")
            assertEquals(0, process.exitValue(), "Rust peer exit code")
        }
    }

    private fun start(vararg args: String): Peer =
        Peer(ProcessBuilder(listOf(peerPath.path) + args).redirectErrorStream(true).start()).also { peer = it }

    @Test
    fun kotlinPhonePairsWithRustPc() {
        val phone = TotemNoise.generateKeyPair()
        val pc = TotemNoise.generateKeyPair()
        val secret = ByteArray(32) { it.toByte() }
        val rust = start("--mode", "pairing", "--role", "responder", "--private", pc.private.hex(), "--secret", secret.hex())

        val hs = TotemNoise.pairingInitiator(phone.private, pc.public)
        rust.sendMsg(hs.writeMessage(secret))
        hs.readMessage(rust.recvMsg())

        val rustHash = rust.expect("HASH").unhex()
        assertContentEquals(phone.public, rust.expect("REMOTE").unhex(), "Rust PC learned the phone's key")
        assertContentEquals(rustHash, hs.handshakeHash())
        assertEquals(TotemNoise.confirmationCode(rustHash), TotemNoise.confirmationCode(hs.handshakeHash()))
        assertContentEquals(pc.public, hs.remoteStatic())

        val t = hs.split()
        rust.sendMsg(t.encrypt("ping from kotlin".toByteArray()))
        assertEquals("ping from kotlin", rust.expect("PLAIN"))
        assertEquals("pong: ping from kotlin", String(t.decrypt(rust.recvMsg())))
        rust.finish()
    }

    @Test
    fun rustPhonePairsWithKotlinPc() {
        val phone = TotemNoise.generateKeyPair()
        val pc = TotemNoise.generateKeyPair()
        val secret = ByteArray(32) { (it * 3).toByte() }
        val rust = start(
            "--mode", "pairing", "--role", "initiator", "--private", phone.private.hex(),
            "--remote", pc.public.hex(), "--secret", secret.hex(),
        )

        val hs = TotemNoise.pairingResponder(pc.private)
        val payload = hs.readMessage(rust.recvMsg())
        assertTrue(TotemNoise.pairingSecretMatches(secret, payload), "Kotlin PC got the QR secret")
        rust.sendMsg(hs.writeMessage())

        assertContentEquals(hs.handshakeHash(), rust.expect("HASH").unhex())
        assertContentEquals(pc.public, rust.expect("REMOTE").unhex())
        assertContentEquals(phone.public, hs.remoteStatic())

        val t = hs.split()
        val ping = String(t.decrypt(rust.recvMsg()))
        assertEquals("ping from rust", ping)
        rust.sendMsg(t.encrypt("pong: $ping".toByteArray()))
        assertEquals("pong: ping from rust", rust.expect("PLAIN"))
        rust.finish()
    }

    @Test
    fun rustPcRejectsWrongPairingSecret() {
        val phone = TotemNoise.generateKeyPair()
        val pc = TotemNoise.generateKeyPair()
        val rust = start(
            "--mode", "pairing", "--role", "responder", "--private", pc.private.hex(),
            "--secret", ByteArray(32) { 1 }.hex(),
        )
        val hs = TotemNoise.pairingInitiator(phone.private, pc.public)
        rust.sendMsg(hs.writeMessage(ByteArray(32) { 2 }))
        assertEquals("pairing secret does not match", rust.expectError())
    }

    @Test
    fun rustPcGetsSessionWithKotlinPhone() {
        val phone = TotemNoise.generateKeyPair()
        val pc = TotemNoise.generateKeyPair()
        val rust = start("--mode", "session", "--role", "initiator", "--private", pc.private.hex(), "--remote", phone.public.hex())

        val hs = TotemNoise.sessionResponder(phone.private)
        hs.readMessage(rust.recvMsg())
        rust.sendMsg(hs.writeMessage())
        // The phone must only accept PCs it has paired with.
        assertContentEquals(pc.public, hs.remoteStatic())
        assertContentEquals(hs.handshakeHash(), rust.expect("HASH").unhex())
        rust.expect("REMOTE")

        val t = hs.split()
        val request = String(t.decrypt(rust.recvMsg()))
        assertEquals("ping from rust", request)
        rust.sendMsg(t.encrypt("pong: $request".toByteArray()))
        assertEquals("pong: ping from rust", rust.expect("PLAIN"))
        rust.finish()
    }

    @Test
    fun kotlinPcGetsSessionWithRustPhone() {
        val phone = TotemNoise.generateKeyPair()
        val pc = TotemNoise.generateKeyPair()
        val rust = start(
            "--mode", "session", "--role", "responder", "--private", phone.private.hex(),
            "--expect-remote", pc.public.hex(),
        )

        val hs = TotemNoise.sessionInitiator(pc.private, phone.public)
        rust.sendMsg(hs.writeMessage())
        hs.readMessage(rust.recvMsg())
        assertContentEquals(hs.handshakeHash(), rust.expect("HASH").unhex())
        rust.expect("REMOTE")

        val t = hs.split()
        rust.sendMsg(t.encrypt("get_code github".toByteArray()))
        assertEquals("get_code github", rust.expect("PLAIN"))
        assertEquals("pong: get_code github", String(t.decrypt(rust.recvMsg())))
        rust.finish()
    }

    @Test
    fun rustPhoneRejectsKotlinPcWithWrongPhoneKey() {
        val phone = TotemNoise.generateKeyPair()
        val pc = TotemNoise.generateKeyPair()
        val impostor = TotemNoise.generateKeyPair()
        val rust = start("--mode", "session", "--role", "responder", "--private", phone.private.hex())
        rust.sendMsg(TotemNoise.sessionInitiator(pc.private, impostor.public).writeMessage())
        assertTrue(rust.expectError().startsWith("read handshake"))
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
private fun String.unhex(): ByteArray = trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
