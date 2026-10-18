package tech.miyukivigil.totem.core

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RelayProtocolTest {
    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun deviceIdMatchesRust() {
        // Same value as relay::tests::device_id_is_32_hex_chars in Rust.
        assertEquals("66687aadf862bd776c8fc18b8e9f8e20", RelayProtocol.deviceId(ByteArray(32)))
    }

    @Test
    fun ed25519MatchesRfc8032TestVector1() {
        val id = RelayIdentity("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hex())
        assertContentEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hex(), id.publicKey)
        assertContentEquals(
            ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b").hex(),
            id.sign(ByteArray(0)),
        )
    }

    @Test
    fun parsesServerMessages() {
        assertIs<RelayProtocol.ServerMsg.Challenge>(RelayProtocol.parse("""{"t":"challenge","nonce":"AAAA"}"""))
        assertEquals(RelayProtocol.ServerMsg.Ready("x"), RelayProtocol.parse("""{"t":"ready","id":"x"}"""))
        val msg = RelayProtocol.parse("""{"t":"msg","from":"a","sid":"s","data":"aGk="}""")
        assertIs<RelayProtocol.ServerMsg.Msg>(msg)
        assertEquals("hi", String(msg.data))
    }

    @Test
    fun clientMessagesMatchRustWireFormat() {
        val send = JSONObject(RelayProtocol.send("ab", "s1", "hi".toByteArray()))
        assertEquals("send", send.getString("t"))
        assertEquals("aGk=", send.getString("data"))
    }

    @Test
    fun parsesPairingQrFromRust() {
        // Same literal as app::tests::pairing_qr_round_trips in Rust.
        val qr = PairingQr.parse(
            "totem://pair?v=1&relay=ws%3A%2F%2F192.168.0.102%3A8080%2Fv1%2Fws&id=0123456789abcdef0123456789abcdef" +
                "&k=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE&s=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI&exp=1800000000",
        )
        assertEquals("ws://192.168.0.102:8080/v1/ws", qr.relay)
        assertEquals("0123456789abcdef0123456789abcdef", qr.pcId)
        assertContentEquals(ByteArray(32) { 1 }, qr.pcKey)
        assertContentEquals(ByteArray(32) { 2 }, qr.secret)
        assertEquals(1_800_000_000, qr.expires)
        assertFailsWith<IllegalArgumentException> { PairingQr.parse("totem://pair?v=2&relay=x") }
        assertFailsWith<IllegalArgumentException> { PairingQr.parse("otpauth://totp/x?secret=AAAA") }
    }

    @Test
    fun appMessagesMatchRustWireFormat() {
        val req = AppMessages.parseRequest("""{"type":"get_code","req":"r","ts":5,"account":"a"}""".toByteArray())
        assertEquals(AppMessages.Request.GetCode("r", 5, "a"), req)
        val code = JSONObject(String(AppMessages.code("r", "123456", 30, null)))
        assertEquals("code", code.getString("type"))
        assertEquals(30, code.getLong("valid_until"))
        assertEquals(false, code.has("next_code"))
    }
}
