package com.vslot.app.game

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SlotMathIdentity {
    const val VERSION = ReleasedSlotMathV5.VERSION

    fun supports(version: Int): Boolean = version == VERSION

    fun fingerprint(config: SlotConfig): String = ReleasedSlotMathV5.fingerprint(config)
}

internal class CanonicalPayloadWriter {
    private val bytes = ByteArrayOutputStream()
    private val output = DataOutputStream(bytes)

    fun writeBoolean(value: Boolean) {
        output.writeByte(if (value) 1 else 0)
    }

    fun writeInt(value: Int) {
        output.writeInt(value)
    }

    fun writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(encoded.size)
        output.write(encoded)
    }

    fun writeInts(values: List<Int>) {
        writeInt(values.size)
        values.forEach(::writeInt)
    }

    fun writeStrings(values: List<String>) {
        writeInt(values.size)
        values.forEach(::writeString)
    }

    fun toByteArray(): ByteArray {
        output.flush()
        return bytes.toByteArray()
    }
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(LOWER_HEX[value ushr 4])
            append(LOWER_HEX[value and 0x0f])
        }
    }
}

private const val LOWER_HEX = "0123456789abcdef"
