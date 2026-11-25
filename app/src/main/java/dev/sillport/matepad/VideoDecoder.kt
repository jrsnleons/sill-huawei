package dev.sillport.matepad

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.ArrayDeque

/**
 * HEVC → Surface on the tablet's hardware decoder, latest frame wins (Sill's rule: drop, never queue).
 *
 * MediaCodec runs in asynchronous mode on its own thread: a frame goes in the moment the decoder has
 * a free input buffer, and each decoded picture is released to the SurfaceView's surface the moment
 * it comes out, with no polling and no copy.
 */
class VideoDecoder(
    private val onSize: (width: Int, height: Int) -> Unit,
    private val onNeedKeyframe: () -> Unit,
) {
    private val tag = "SillDecoder"
    private val thread = HandlerThread("sill-decode", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    // Owned by the decoder thread.
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var params: ParameterSets? = null
    private var waitingForKeyframe = true
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<Pair<ByteArray, Boolean>>()

    @Volatile private var nalLength = 4
    @Volatile private var released = false

    var framesDecoded = 0L; private set
    var framesDropped = 0L; private set

    fun setSurface(s: Surface?) = handler.post {
        surface = s
        teardown()
        if (s != null) { waitingForKeyframe = true; params?.let { start(it) }; onNeedKeyframe() }
    }

    fun onParameterSets(p: ParameterSets) {
        nalLength = p.nalLength
        handler.post {
            if (p.sameAs(params) && codec != null) return@post
            params = p
            teardown()
            start(p)
        }
    }

    /** A new session (a reconnect): drop what's queued and wait for its keyframe. */
    fun newSession() = handler.post {
        pending.clear()
        waitingForKeyframe = true
        runCatching { codec?.flush(); codec?.start() } // async mode needs start() after flush
        freeInputs.clear()
    }

    /** Called on the network thread. */
    fun onFrame(au: ByteArray, keyframe: Boolean) {
        if (released) return
        val annexB = lengthPrefixedToAnnexB(au, nalLength) ?: return
        handler.post { enqueue(annexB, keyframe) }
    }

    fun release() {
        released = true
        handler.post { teardown(); thread.quitSafely() }
    }

    private fun enqueue(frame: ByteArray, keyframe: Boolean) {
        if (waitingForKeyframe && !keyframe) { framesDropped++; return }
        if (keyframe) {
            // A keyframe makes everything before it useless.
            framesDropped += pending.size
            pending.clear()
        } else if (pending.size >= 3) {
            // Behind by three frames: skip ahead to the next keyframe rather than show old pictures.
            framesDropped += pending.size + 1
            pending.clear()
            waitingForKeyframe = true
            onNeedKeyframe()
            return
        }
        pending.addLast(frame to keyframe)
        feed()
    }

    private fun feed() {
        val c = codec ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val (frame, key) = pending.removeFirst()
            val index = freeInputs.removeFirst()
            try {
                val buf = c.getInputBuffer(index) ?: continue
                if (frame.size > buf.capacity()) { c.queueInputBuffer(index, 0, 0, 0, 0); continue }
                buf.clear(); buf.put(frame)
                c.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000, 0)
                if (key) waitingForKeyframe = false
            } catch (e: IllegalStateException) { return }
        }
    }

    private fun hardwareHevcDecoder(): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                (if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google")) &&
                !info.name.endsWith(".secure")
        }?.name

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c !== codec) return
            freeInputs.addLast(index); feed()
        }
        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            runCatching { c.releaseOutputBuffer(index, true) } // straight to the screen, now
            framesDecoded++
        }
        override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
            val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
            val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
            onSize(w, h)
        }
        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(tag, "decoder error ${e.diagnosticInfo}; restarting at the next keyframe")
            if (c !== codec) return
            val p = params
            teardown()
            if (p != null) start(p)
            onNeedKeyframe()
        }
    }

    private fun start(p: ParameterSets) {
        val s = surface ?: return
        // Kirin firmware refuses some low-latency hints (0x80001001 with KEY_LOW_LATENCY), so step down
        // until one configures: the standard low-latency key, then HiSilicon's own, then real-time priority only, then none, then any decoder.
        val hw = hardwareHevcDecoder()
        val attempts = listOfNotNull(hw?.let { it to 3 }, hw?.let { it to 2 }, hw?.let { it to 1 }, hw?.let { it to 0 }, null to 0)
        for ((name, level) in attempts) {
            var c: MediaCodec? = null
            try {
                c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080)
                if (level == 3 && Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                if (level >= 2) f.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
                if (level == 2) {
                    // HiSilicon's own low-delay switch, for firmware that refuses KEY_LOW_LATENCY.
                    f.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
                    f.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1)
                }
                if (level >= 1) f.setInteger(MediaFormat.KEY_PRIORITY, 0)
                f.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(p.annexB()))
                freeInputs.clear()
                codec = c
                c.setCallback(callback, handler)
                c.configure(f, s, null, 0)
                c.start()
                waitingForKeyframe = true
                Log.i(tag, "decoder ${c.name} started (hints level $level)")
                return
            } catch (e: Exception) {
                Log.w(tag, "decoder ${name ?: "default"} level $level refused: ${e.message}")
                codec = null
                runCatching { c?.release() }
            }
        }
        Log.e(tag, "no decoder would start")
    }

    private fun teardown() {
        val c = codec
        codec = null
        c?.let { runCatching { it.stop() }; runCatching { it.release() } }
        freeInputs.clear()
        pending.clear()
    }
}
