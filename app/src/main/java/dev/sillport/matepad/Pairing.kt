package dev.sillport.matepad

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Sill's pairing maths (Sources/StreamProtocol/Pairing.swift), pure JVM so the unit tests run it. */
object Pairing {
    private const val LABEL = "sill-pair-v1"
    const val ROUNDS = 600_000
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val DAMM = arrayOf(
        intArrayOf(0, 3, 1, 7, 5, 9, 8, 6, 4, 2), intArrayOf(7, 0, 9, 2, 1, 5, 4, 8, 6, 3),
        intArrayOf(4, 2, 0, 6, 8, 7, 1, 3, 5, 9), intArrayOf(1, 7, 5, 0, 9, 8, 3, 4, 2, 6),
        intArrayOf(6, 1, 2, 3, 0, 4, 5, 9, 7, 8), intArrayOf(3, 6, 7, 4, 2, 0, 9, 5, 8, 1),
        intArrayOf(5, 8, 6, 9, 7, 2, 0, 1, 3, 4), intArrayOf(8, 9, 4, 5, 3, 6, 2, 0, 1, 7),
        intArrayOf(9, 4, 3, 8, 6, 1, 7, 2, 0, 5), intArrayOf(2, 5, 8, 1, 4, 3, 6, 7, 9, 0),
    )

    sealed class CodeCheck {
        data class Ok(val code: String) : CodeCheck()
        object Length : CodeCheck()
        object Typo : CodeCheck()
    }

    /** Typed input as a code: spaces and hyphens dropped, 12 ASCII digits, Damm check digit valid. */
    fun checkCode(input: String): CodeCheck {
        val kept = input.filter { it != ' ' && it != '-' && it != ' ' }
        if (kept.length != 12 || !kept.all { it in '0'..'9' }) return CodeCheck.Length
        var i = 0
        for (c in kept) i = DAMM[i][c - '0']
        return if (i == 0) CodeCheck.Ok(kept) else CodeCheck.Typo
    }

    /** K = PBKDF2-HMAC-SHA256(code, "sill-pair-v1" ‖ fp_mac, 600,000, 32 bytes). Slow: off the main thread. */
    fun codeKey(code: String, macFingerprint: ByteArray): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), LABEL.toByteArray() + macFingerprint, ROUNDS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(msg) }

    fun deviceProof(key: ByteArray, deviceFp: ByteArray, macFp: ByteArray): ByteArray =
        hmac(key, "$LABEL device".toByteArray() + byteArrayOf(0) + deviceFp + macFp)

    fun macProof(key: ByteArray, macFp: ByteArray, deviceFp: ByteArray): ByteArray =
        hmac(key, "$LABEL mac".toByteArray() + byteArrayOf(0) + macFp + deviceFp)

    fun isValidMacProof(proof: ByteArray, key: ByteArray, macFp: ByteArray, deviceFp: ByteArray): Boolean =
        MessageDigest.isEqual(proof, macProof(key, macFp, deviceFp))

    /** Crockford base32 of fp[0..<10], 16 characters. */
    fun macID(fp: ByteArray): String {
        var n = BigInteger(1, fp.copyOfRange(0, 10))
        val out = CharArray(16)
        val mask = BigInteger.valueOf(31)
        for (i in 15 downTo 0) {
            out[i] = CROCKFORD[n.and(mask).toInt()]
            n = n.shiftRight(5)
        }
        return String(out)
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun b64uDecode(s: String): ByteArray? =
        try { Base64.getUrlDecoder().decode(s) } catch (e: IllegalArgumentException) { null }

    fun grouped(code: String): String = code.chunked(4).joinToString(" ")
}
