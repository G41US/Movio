package com.g41us.movio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

/** One subtitle line, in the subtitle file's own clock. */
data class SubCue(val start: Long, val end: Long, val text: String)

/** Reads WebVTT (or SRT already turned into VTT) into plain cues. */
object SubParse {
    private val stamp = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})""")
    private val tag = Regex("<[^>]+>|\\{\\\\[^}]*\\}")

    private fun ms(m: MatchResult): Long {
        val (h, mi, s, f) = m.destructured
        return (h.toLongOrNull() ?: 0) * 3_600_000 + mi.toLong() * 60_000 + s.toLong() * 1000 + f.toLong()
    }

    fun parse(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        val lines = text.replace("\r", "").split("\n")
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if ("-->" in l) {
                val t = stamp.findAll(l).toList()
                i++
                val body = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) { if (body.isNotEmpty()) body.append('\n'); body.append(lines[i]); i++ }
                if (t.size >= 2) {
                    val clean = body.toString().replace(tag, "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()
                    if (clean.isNotEmpty()) out.add(SubCue(ms(t[0]), ms(t[1]), clean))
                }
            } else i++
        }
        return out.sortedBy { it.start }
    }
}

/**
 * Listens to the film's audio as it plays and keeps a coarse "someone is talking" track
 * (speech-band loudness every 100 ms, by position in the film). Used to line subtitles up.
 */
@OptIn(UnstableApi::class)
class SpeechTrack : TeeAudioProcessor.AudioBufferSink {
    companion object { const val STEP = 100L; private const val MAX = 6 * 36_000 }

    /** Loudness per 100 ms of the film; NaN where we haven't heard it yet. */
    val energy = FloatArray(MAX) { Float.NaN }
    @Volatile var heard = 0; private set

    /** Film position (ms) where the next audio after a flush starts; set when seeking. */
    @Volatile var anchor = 0L
    /** Best guess of the current film position, kept fresh from the main thread. */
    @Volatile var clock = 0L
    @Volatile private var seekPending = true

    private var rate = 0; private var channels = 0; private var pcm16 = false
    private var startMs = 0L; private var samples = 0L
    private var acc = 0.0; private var n = 0; private var prev = 0f

    fun seeked(toMs: Long) { anchor = toMs; seekPending = true }

    fun reset() { energy.fill(Float.NaN); heard = 0 }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz; channels = channelCount; pcm16 = encoding == C.ENCODING_PCM_16BIT
        startMs = if (seekPending) anchor else clock
        seekPending = false
        samples = 0; acc = 0.0; n = 0; prev = 0f
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!pcm16 || rate <= 0 || channels <= 0) return
        val sb = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val per = (rate * STEP / 1000).toInt().coerceAtLeast(1)
        while (sb.remaining() >= channels) {
            var m = 0f
            for (c in 0 until channels) m += sb.get().toFloat()
            m /= channels
            // Differencing keeps voices and drops rumble/bass-heavy music.
            val d = m - prev; prev = m
            acc += (d * d).toDouble(); n++
            samples++
            if (n >= per) {
                val pos = startMs + (samples - n / 2) * 1000 / rate
                val idx = (pos / STEP).toInt()
                if (idx in energy.indices) {
                    if (energy[idx].isNaN()) heard++
                    energy[idx] = ln(acc / n + 1.0).toFloat()
                }
                acc = 0.0; n = 0
            }
        }
    }
}

/**
 * Finds how a subtitle file's clock maps onto the film: film = sub × scale + offset.
 * Tries the usual frame-rate mismatches (23.976 / 24 / 25 fps) and offsets of ±20 s,
 * and only answers when one fit is clearly better than the rest.
 */
object SubAligner {
    data class Fit(val scale: Double, val offset: Long)

    private val scales = doubleArrayOf(1.0, 25 / 23.976, 23.976 / 25, 24 / 23.976, 23.976 / 24, 25.0 / 24, 24.0 / 25)

    fun align(cues: List<SubCue>, track: SpeechTrack, current: Fit): Fit? {
        val e = track.energy
        if (track.heard < 1500 || cues.size < 20) return null
        val known = e.filter { !it.isNaN() }.sorted()
        val thr = known[known.size * 55 / 100]
        // +1 where there's speech-like sound, −1 where it's quiet, 0 where we haven't listened.
        val pre = DoubleArray(e.size + 1)
        for (i in e.indices) pre[i + 1] = pre[i] + if (e[i].isNaN()) 0.0 else if (e[i] > thr) 1.0 else -1.0
        val step = SpeechTrack.STEP
        fun score(s: Double, o: Long): Double {
            var t = 0.0
            for (c in cues) {
                val a = ((c.start * s + o) / step).toInt().coerceIn(0, e.size)
                val b = ((c.end * s + o) / step).toInt().coerceIn(0, e.size)
                if (b > a) t += pre[b] - pre[a]
            }
            return t
        }
        var best = Fit(1.0, 0); var bestScore = Double.NEGATIVE_INFINITY
        val all = HashMap<Fit, Double>()
        for (s in scales) {
            var o = -20_000L
            while (o <= 20_000L) { val f = Fit(s, o); val v = score(s, o); all[f] = v; if (v > bestScore) { bestScore = v; best = f }; o += 100 }
        }
        val base = score(current.scale, current.offset)
        // Must beat what's showing now by a clear margin, and stand out from nearby offsets.
        val runnerUp = all.filter { (f, _) -> abs(f.scale - best.scale) > 0.01 || abs(f.offset - best.offset) >= 2_000 }.values.maxOrNull() ?: return null
        val gain = bestScore - base
        if (gain < 25 || bestScore - runnerUp < 12) return null
        if (best.scale == current.scale && abs(best.offset - current.offset) < 400) return null
        return best
    }
}

/** Downloads a subtitle once and returns its cues (cached on disk by SubtitleSync). */
object SubLoad {
    suspend fun cues(dir: File, sub: Subtitle, headers: Map<String, String>): List<SubCue> {
        val path = SubtitleSync.shifted(dir, sub, headers, 0) ?: return emptyList()
        return try { SubParse.parse(File(path).readText()) } catch (_: Exception) { emptyList() }
    }
}
