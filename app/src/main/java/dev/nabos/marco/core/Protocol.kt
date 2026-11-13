package dev.nabos.marco.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wire protocol shared by the findable side (advertiser + GATT server) and the
 * finder side (scanner + GATT client).
 *
 * Advertising: a single 128-bit service-data entry under [SERVICE_UUID] whose
 * payload is an 8-byte rotating ID:
 *     HMAC-SHA256(key, "marco/adv/v1" || be64(epochWindow))[0..8]
 * so a stranger can't follow the phone around by its advertisement, while the
 * paired phone can recompute the ID. The window is [ID_WINDOW_MS] long; the finder
 * also accepts the previous and next window to tolerate clock drift.
 *
 * Commands (GATT): the finder reads a fresh 16-byte nonce from [NONCE_CHAR_UUID],
 * then writes  opcode(1) || HMAC-SHA256(key, "marco/cmd/v1" || nonce || opcode)[0..16]
 * to [CMD_CHAR_UUID]. Each nonce is single-use, so a sniffed command can't be
 * replayed. 17 bytes fits the default ATT MTU (20-byte payload).
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("6d61726b-6f00-4c6f-8b5d-5a6f6c6f5000")
    val NONCE_CHAR_UUID: UUID = UUID.fromString("6d61726b-6f01-4c6f-8b5d-5a6f6c6f5000")
    val CMD_CHAR_UUID: UUID = UUID.fromString("6d61726b-6f02-4c6f-8b5d-5a6f6c6f5000")

    const val KEY_LEN = 32
    const val ADV_ID_LEN = 8
    const val NONCE_LEN = 16
    const val MAC_LEN = 16
    const val CMD_LEN = 1 + MAC_LEN

    const val ID_WINDOW_MS = 15 * 60 * 1000L

    const val OP_RING_START: Byte = 0x01
    const val OP_RING_STOP: Byte = 0x02

    /** How long one RING_START keeps the lost phone ringing unless renewed or stopped. */
    const val RING_DURATION_MS = 30_000L

    private val ADV_LABEL = "marco/adv/v1".toByteArray()
    private val CMD_LABEL = "marco/cmd/v1".toByteArray()

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun window(nowMs: Long = System.currentTimeMillis()): Long = nowMs / ID_WINDOW_MS

    /** Milliseconds until the advertised ID next changes. */
    fun msUntilNextWindow(nowMs: Long = System.currentTimeMillis()): Long =
        ID_WINDOW_MS - (nowMs % ID_WINDOW_MS)

    fun advId(key: ByteArray, window: Long): ByteArray {
        val w = ByteBuffer.allocate(8).putLong(window).array()
        return hmac(key, ADV_LABEL, w).copyOf(ADV_ID_LEN)
    }

    /** IDs the finder should accept right now (previous, current, next window). */
    fun acceptedAdvIds(key: ByteArray, nowMs: Long = System.currentTimeMillis()): List<ByteArray> {
        val w = window(nowMs)
        return listOf(advId(key, w - 1), advId(key, w), advId(key, w + 1))
    }

    fun buildCommand(key: ByteArray, nonce: ByteArray, opcode: Byte): ByteArray {
        val mac = hmac(key, CMD_LABEL, nonce, byteArrayOf(opcode)).copyOf(MAC_LEN)
        return byteArrayOf(opcode) + mac
    }

    /** Returns the opcode if [cmd] is authentic for [nonce], else null. */
    fun verifyCommand(key: ByteArray, nonce: ByteArray, cmd: ByteArray): Byte? {
        if (cmd.size != CMD_LEN) return null
        val opcode = cmd[0]
        val expected = hmac(key, CMD_LABEL, nonce, byteArrayOf(opcode)).copyOf(MAC_LEN)
        val got = cmd.copyOfRange(1, CMD_LEN)
        return if (MessageDigest.isEqual(expected, got)) opcode else null
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }
}
