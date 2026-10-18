package tech.miyukivigil.totem.core

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Base64

/**
 * Parses Google Authenticator's "Transfer accounts" QR codes:
 * `otpauth-migration://offline?data=<base64 protobuf>`.
 *
 * The payload is a small protobuf (`MigrationPayload`), decoded by hand here so we don't need a
 * protobuf library. Field numbers follow Google Authenticator's published `.proto`:
 *
 * ```
 * MigrationPayload { repeated OtpParameters otp_parameters = 1; int32 version = 2;
 *                    int32 batch_size = 3; int32 batch_index = 4; int32 batch_id = 5; }
 * OtpParameters    { bytes secret = 1; string name = 2; string issuer = 3; Algorithm algorithm = 4;
 *                    DigitCount digits = 5; OtpType type = 6; int64 counter = 7; }
 * Algorithm  { UNSPECIFIED = 0; SHA1 = 1; SHA256 = 2; SHA512 = 3; MD5 = 4; }
 * DigitCount { UNSPECIFIED = 0; SIX = 1; EIGHT = 2; }
 * OtpType    { UNSPECIFIED = 0; HOTP = 1; TOTP = 2; }
 * ```
 */
object GoogleAuthMigration {
    class InvalidExport(message: String) : IllegalArgumentException(message)

    /** One QR code's worth of accounts. A large export is split across [batchSize] codes. */
    class Batch(val accounts: List<Account>, val skipped: List<String>, val batchIndex: Int, val batchSize: Int)

    fun isMigrationUri(text: String) = text.trim().startsWith("otpauth-migration://", ignoreCase = true)

    fun parse(text: String): Batch {
        if (!isMigrationUri(text)) throw InvalidExport("not a Google Authenticator export")
        val data = text.trim().substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("data=") }
            ?.let { URLDecoder.decode(it.removePrefix("data="), "UTF-8") }
            ?: throw InvalidExport("export has no data")
        val bytes = try {
            Base64.getDecoder().decode(data.replace(' ', '+'))
        } catch (e: IllegalArgumentException) {
            throw InvalidExport("export data is not valid base64")
        }
        return parsePayload(bytes)
    }

    internal fun parsePayload(bytes: ByteArray): Batch {
        val accounts = mutableListOf<Account>()
        val skipped = mutableListOf<String>()
        var batchSize = 1
        var batchIndex = 0
        val r = ProtoReader(bytes)
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> parseOtp(r.bytes())?.let { result ->
                    result.fold({ accounts += it }, { skipped += it.message ?: "unknown account" })
                }
                field == 3 && wire == 0 -> batchSize = r.varint().toInt()
                field == 4 && wire == 0 -> batchIndex = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        return Batch(accounts, skipped, batchIndex, batchSize)
    }

    private fun parseOtp(bytes: ByteArray): Result<Account>? {
        var secret = ByteArray(0)
        var name = ""
        var issuer = ""
        var algorithm = 1
        var digits = 1
        var type = 2
        var counter = 0L
        val r = ProtoReader(bytes)
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> secret = r.bytes()
                field == 2 && wire == 2 -> name = String(r.bytes(), Charsets.UTF_8)
                field == 3 && wire == 2 -> issuer = String(r.bytes(), Charsets.UTF_8)
                field == 4 && wire == 0 -> algorithm = r.varint().toInt()
                field == 5 && wire == 0 -> digits = r.varint().toInt()
                field == 6 && wire == 0 -> type = r.varint().toInt()
                field == 7 && wire == 0 -> counter = r.varint()
                else -> r.skip(wire)
            }
        }
        // Google stores "Issuer:label" in name when an issuer exists.
        val label = if (issuer.isNotEmpty() && name.startsWith("$issuer:")) name.substring(issuer.length + 1).trim() else name
        val shown = listOf(issuer, label).filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { "unnamed account" }
        val alg = when (algorithm) {
            0, 1 -> OtpAlgorithm.SHA1
            2 -> OtpAlgorithm.SHA256
            3 -> OtpAlgorithm.SHA512
            else -> return Result.failure(InvalidExport("$shown: MD5 is not supported"))
        }
        val digitCount = when (digits) {
            0, 1 -> 6
            2 -> 8
            else -> return Result.failure(InvalidExport("$shown: unknown digit count"))
        }
        val otpType = when (type) {
            1 -> OtpType.HOTP
            0, 2 -> OtpType.TOTP
            else -> return Result.failure(InvalidExport("$shown: unknown type"))
        }
        if (secret.isEmpty()) return Result.failure(InvalidExport("$shown: empty secret"))
        return Result.success(
            Account(
                issuer = issuer, label = label, secret = secret, type = otpType, algorithm = alg,
                digits = digitCount, counter = if (otpType == OtpType.HOTP) counter else 0,
            ),
        )
    }

    /** Builds a payload; used by tests to make realistic exports. */
    internal fun encodePayload(entries: List<Map<Int, Any>>, batchSize: Int = 1, batchIndex: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in entries) {
            val otp = ByteArrayOutputStream()
            for ((field, value) in e.toSortedMap()) when (value) {
                is ByteArray -> { writeVarint(otp, (field shl 3 or 2).toLong()); writeVarint(otp, value.size.toLong()); otp.write(value) }
                is String -> value.toByteArray().let { writeVarint(otp, (field shl 3 or 2).toLong()); writeVarint(otp, it.size.toLong()); otp.write(it) }
                is Number -> { writeVarint(otp, (field shl 3).toLong()); writeVarint(otp, value.toLong()) }
            }
            writeVarint(out, (1 shl 3 or 2).toLong()); writeVarint(out, otp.size().toLong()); otp.writeTo(out)
        }
        writeVarint(out, (2 shl 3).toLong()); writeVarint(out, 1)
        writeVarint(out, (3 shl 3).toLong()); writeVarint(out, batchSize.toLong())
        writeVarint(out, (4 shl 3).toLong()); writeVarint(out, batchIndex.toLong())
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private class ProtoReader(private val buf: ByteArray) {
        private var pos = 0

        fun hasMore() = pos < buf.size

        fun tag(): Pair<Int, Int> = varint().let { (it ushr 3).toInt() to (it and 7).toInt() }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= buf.size || shift > 63) throw InvalidExport("export data is corrupted")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun bytes(): ByteArray {
            val len = varint()
            if (len < 0 || len > buf.size - pos) throw InvalidExport("export data is corrupted")
            return buf.copyOfRange(pos, pos + len.toInt()).also { pos += len.toInt() }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> throw InvalidExport("export data is corrupted")
            }
            if (pos > buf.size) throw InvalidExport("export data is corrupted")
        }
    }
}
