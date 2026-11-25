package dev.sillport.matepad

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/** Sill's message kinds (Sources/StreamProtocol/StreamMessage.swift). Only the ones this client uses. */
object Kind {
    const val PARAMETER_SETS = 0
    const val FRAME = 1
    const val WINDOW_LIST = 2
    const val SELECT_SOURCE = 6
    const val INPUT = 8
    const val VIEWPORT = 9
    const val PING = 10
    const val PONG = 11
    const val TICK = 13
    const val HOST_SETTINGS = 16
    const val PAIR_REQUEST = 19
    const val PAIR_RESULT = 20
    const val GOODBYE = 22
    const val HELLO = 23
    const val KEYFRAME_REQUEST = 44
    const val MAC_MENU = 24
    const val PRESS_MENU_ITEM = 25
    const val FETCH_MENU = 27
    const val CLIPBOARD = 32
    const val GESTURE = 28
    const val THUMBNAIL = 3
    const val APP_ICON = 4
    const val APP_LIST = 5
    const val LAUNCH_APP = 7
    const val WINDOW_COMMAND = 15
    const val CHANGE_SETTINGS = 17
}

class Message(val kind: Int, val timestamp: Double, val isKeyframe: Boolean, val payload: ByteArray)

/**
 * The 14-byte big-endian header: kind(1) timestamp(8, double) isKeyframe(1) payloadLength(4),
 * then the payload. Unknown kinds are read and handed on; the caller skips them.
 */
object Wire {
    const val HEADER = 14
    /** A device's caps on what a header may announce (StreamMessage.max*). */
    const val MAX_FRAME = 32 shl 20
    const val MAX_OTHER = 4 shl 20

    fun encode(kind: Int, payload: ByteArray = ByteArray(0), keyframe: Boolean = false): ByteArray {
        val b = ByteBuffer.allocate(HEADER + payload.size)
        b.put(kind.toByte())
        b.putDouble(System.currentTimeMillis() / 1000.0)
        b.put(if (keyframe) 1 else 0)
        b.putInt(payload.size)
        b.put(payload)
        return b.array()
    }

    fun write(out: OutputStream, kind: Int, payload: ByteArray = ByteArray(0)) {
        val bytes = encode(kind, payload)
        synchronized(out) { out.write(bytes); out.flush() }
    }

    /** Reads one message; throws EOFException when the peer closed. */
    fun read(input: DataInputStream): Message {
        val h = ByteArray(HEADER)
        input.readFully(h)
        val b = ByteBuffer.wrap(h)
        val kind = b.get().toInt() and 0xFF
        val ts = b.getDouble()
        val key = b.get().toInt() == 1
        val len = b.getInt().toLong() and 0xFFFFFFFFL
        val cap = if (kind == Kind.FRAME) MAX_FRAME else MAX_OTHER
        if (len > cap) throw IOException("kind $kind announces $len bytes, more than $cap")
        val payload = ByteArray(len.toInt())
        input.readFully(payload)
        return Message(kind, ts, key, payload)
    }
}

/** HEVC VPS/SPS/PPS: nalLen(1) count(1) then per set length(2) bytes. */
class ParameterSets(val nalLength: Int, val sets: List<ByteArray>) {
    /** All sets as one Annex-B buffer, for MediaCodec's codec-config input. */
    fun annexB(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (s in sets) { out.write(START); out.write(s) }
        return out.toByteArray()
    }

    fun sameAs(other: ParameterSets?): Boolean =
        other != null && other.nalLength == nalLength && other.sets.size == sets.size &&
            sets.indices.all { sets[it].contentEquals(other.sets[it]) }

    companion object {
        val START = byteArrayOf(0, 0, 0, 1)

        fun parse(d: ByteArray): ParameterSets? {
            if (d.size < 2) return null
            val nal = d[0].toInt() and 0xFF
            val count = d[1].toInt() and 0xFF
            var off = 2
            val sets = ArrayList<ByteArray>(count)
            repeat(count) {
                if (d.size < off + 2) return null
                val len = ((d[off].toInt() and 0xFF) shl 8) or (d[off + 1].toInt() and 0xFF)
                off += 2
                if (d.size < off + len) return null
                sets.add(d.copyOfRange(off, off + len))
                off += len
            }
            return ParameterSets(nal, sets)
        }
    }
}

/**
 * One access unit, length-prefixed NALs (hvcC style), as Annex-B start codes. With 4-byte prefixes
 * (the normal case) the array is rewritten in place. Returns null for a malformed unit.
 */
fun lengthPrefixedToAnnexB(au: ByteArray, nalLength: Int): ByteArray? {
    if (nalLength == 4) {
        var off = 0
        while (off + 4 <= au.size) {
            val len = ((au[off].toInt() and 0xFF) shl 24) or ((au[off + 1].toInt() and 0xFF) shl 16) or
                ((au[off + 2].toInt() and 0xFF) shl 8) or (au[off + 3].toInt() and 0xFF)
            if (len < 0 || off + 4 + len > au.size) return null
            au[off] = 0; au[off + 1] = 0; au[off + 2] = 0; au[off + 3] = 1
            off += 4 + len
        }
        return if (off == au.size) au else null
    }
    val out = java.io.ByteArrayOutputStream(au.size + 16)
    var off = 0
    while (off + nalLength <= au.size) {
        var len = 0
        for (i in 0 until nalLength) len = (len shl 8) or (au[off + i].toInt() and 0xFF)
        off += nalLength
        if (off + len > au.size) return null
        out.write(ParameterSets.START); out.write(au, off, len)
        off += len
    }
    return if (off == au.size) out.toByteArray() else null
}
