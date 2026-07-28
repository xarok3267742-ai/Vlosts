package com.vslot.app.game

import com.vslot.app.SlotRules
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SlotMathIdentity {
    const val VERSION = 4

    fun supports(version: Int): Boolean = version == VERSION

    fun fingerprint(config: SlotConfig): String {
        val canonical = CanonicalPayloadWriter().apply {
            writeString(CONFIG_DOMAIN)
            writeInt(CONFIG_FINGERPRINT_VERSION)
            writeString(RULES_DOMAIN)
            writeInt(VERSION)
            writeInt(SlotRules.FREE_SPINS_BONUS_AWARD)
            writeInt(SlotEngine.PAYLINE_ROWS.size)
            SlotEngine.PAYLINE_ROWS.forEach(::writeInts)
            writeString(config.id)
            writeInt(config.reels)
            writeInt(config.rows)
            writeInt(config.paylines)
            writeString(config.wild)
            writeString(config.scatter)
            writeStrings(config.symbols)
            writeInts(config.bets)
            writeInt(config.payouts.size)
            config.payouts.toSortedMap().forEach { (symbol, payouts) ->
                writeString(symbol)
                writeInt(payouts.size)
                payouts.toSortedMap().forEach { (count, multiplier) ->
                    writeInt(count)
                    writeInt(multiplier)
                }
            }
            writeInt(config.scatterBonus.size)
            config.scatterBonus.toSortedMap().forEach { (count, multiplier) ->
                writeInt(count)
                writeInt(multiplier)
            }
            writeInt(config.reelStrips.size)
            config.reelStrips.forEach(::writeStrings)
            writeInt(config.freeSpinReelStrips.size)
            config.freeSpinReelStrips.forEach(::writeStrings)
        }.toByteArray()
        return sha256Hex(canonical)
    }

    private const val CONFIG_DOMAIN = "vslot.slot-config"
    private const val RULES_DOMAIN = "vslot.slot-rules"
    private const val CONFIG_FINGERPRINT_VERSION = 4
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
