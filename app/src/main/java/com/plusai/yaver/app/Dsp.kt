package com.plusai.yaver.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio signal processing for the media tool, in plain Kotlin (no Android classes, so it runs in tests too):
 * WAV reading/writing, resampling, filters, and the speech clean-up chain — high-pass, spectral noise reduction,
 * a clarity lift, level evening and a peak limiter.
 */
object Dsp {
    /** A growable float array (decoders do not say in advance how many samples come). */
    class FloatBuf(cap: Int = 1 shl 16) {
        var a = FloatArray(maxOf(16, cap)); var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(if (a.size < (1 shl 26)) a.size * 2 else a.size + (1 shl 24)); a[n++] = v }
        fun toArray(): FloatArray = if (a.size == n) a else a.copyOf(n)
    }

    // ------------------------------------------------------------------ WAV

    class WavInfo(val rate: Int, val channels: Int, val bits: Int, val float: Boolean, val dataBytes: Long) {
        val frames get() = if (bits > 0 && channels > 0) dataBytes / (bits / 8 * channels) else 0L
        val seconds get() = if (rate > 0) frames.toDouble() / rate else 0.0
    }

    /** Streams samples out of a WAV file as floats in [-1, 1], interleaved. */
    class WavReader(f: File) : AutoCloseable {
        private val inp = DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16))
        val info: WavInfo
        private var left: Long
        init {
            fun id(): String { val b = ByteArray(4); inp.readFully(b); return String(b, Charsets.ISO_8859_1) }
            fun u32(): Long { val b = ByteArray(4); inp.readFully(b); return (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24) }
            fun u16(): Int { val b = ByteArray(2); inp.readFully(b); return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) }
            val riff = id(); u32(); val wave = id()
            if ((riff != "RIFF" && riff != "RF64") || wave != "WAVE") throw IllegalArgumentException("not a WAV file")
            var rate = 0; var ch = 0; var bits = 0; var fl = false; var data = -1L
            while (data < 0) {
                val cid = try { id() } catch (e: EOFException) { break }
                val size = u32()
                when (cid) {
                    "fmt " -> {
                        var fmt = u16(); ch = u16(); rate = u32().toInt(); u32(); u16(); bits = u16()
                        var used = 16L
                        if (fmt == 0xFFFE && size >= 40) { u16(); u16(); u32(); fmt = u16(); inp.skipBytes(14); used = 40 }
                        fl = fmt == 3
                        if (fmt != 1 && fmt != 3) throw IllegalArgumentException("WAV encoding $fmt is not supported (PCM or float only)")
                        skip(size - used + (size and 1))
                    }
                    "data" -> data = if (size == 0xFFFFFFFFL || size == 0L) Long.MAX_VALUE else size
                    else -> skip(size + (size and 1))
                }
            }
            if (data < 0 || rate <= 0 || ch <= 0) throw IllegalArgumentException("the WAV file has no audio data")
            info = WavInfo(rate, ch, bits, fl, if (data == Long.MAX_VALUE) maxOf(0L, f.length() - 44) else data)
            left = info.dataBytes
        }
        private fun skip(n: Long) { var k = n; while (k > 0) { val s = inp.skip(k); if (s <= 0) { if (inp.read() < 0) return; k-- } else k -= s } }
        private val bps = info.bits / 8
        private val raw = ByteArray(8192 * maxOf(1, bps))
        /** Reads up to out.size samples; returns how many, or -1 at the end. */
        fun read(out: FloatArray, max: Int = out.size): Int {
            if (left <= 0) return -1
            val want = min(max.toLong() * bps, min(left, raw.size.toLong() - raw.size % bps)).toInt()
            var got = 0
            while (got < want) { val r = inp.read(raw, got, want - got); if (r < 0) break; got += r }
            got -= got % bps
            if (got <= 0) { left = 0; return -1 }
            left -= got
            val n = got / bps
            var i = 0; var p = 0
            while (i < n) {
                out[i] = when {
                    info.float && bps == 4 -> java.lang.Float.intBitsToFloat((raw[p].toInt() and 0xff) or ((raw[p + 1].toInt() and 0xff) shl 8) or ((raw[p + 2].toInt() and 0xff) shl 16) or (raw[p + 3].toInt() shl 24))
                    info.float && bps == 8 -> { var l = 0L; for (k in 7 downTo 0) l = (l shl 8) or (raw[p + k].toLong() and 0xff); java.lang.Double.longBitsToDouble(l).toFloat() }
                    bps == 1 -> ((raw[p].toInt() and 0xff) - 128) / 128f
                    bps == 2 -> ((raw[p].toInt() and 0xff) or (raw[p + 1].toInt() shl 8)) / 32768f
                    bps == 3 -> ((raw[p].toInt() and 0xff) or ((raw[p + 1].toInt() and 0xff) shl 8) or (raw[p + 2].toInt() shl 16)) / 8388608f
                    else -> ((raw[p].toInt() and 0xff) or ((raw[p + 1].toInt() and 0xff) shl 8) or ((raw[p + 2].toInt() and 0xff) shl 16) or (raw[p + 3].toInt() shl 24)) / 2147483648f
                }
                i++; p += bps
            }
            return n
        }
        override fun close() { try { inp.close() } catch (_: Exception) {} }
    }

    /** Writes 16-bit PCM WAV as samples arrive; the header is completed on close. */
    class WavWriter(private val f: File, val rate: Int, val channels: Int) : AutoCloseable {
        private val out = BufferedOutputStream(FileOutputStream(f.also { it.parentFile?.mkdirs() }), 1 shl 16)
        private var bytes = 0L
        private val buf = ByteArray(16384)
        init { out.write(header(0)) }
        private fun header(data: Long): ByteArray {
            val h = ByteArray(44)
            fun s(o: Int, t: String) { for (i in t.indices) h[o + i] = t[i].code.toByte() }
            fun i32(o: Int, v: Long) { for (k in 0..3) h[o + k] = (v shr (8 * k)).toByte() }
            fun i16(o: Int, v: Int) { h[o] = v.toByte(); h[o + 1] = (v shr 8).toByte() }
            val d = min(data, 0xFFFFFFFFL - 36)
            s(0, "RIFF"); i32(4, 36 + d); s(8, "WAVE"); s(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, channels)
            i32(24, rate.toLong()); i32(28, rate.toLong() * channels * 2); i16(32, channels * 2); i16(34, 16); s(36, "data"); i32(40, d)
            return h
        }
        fun write(x: FloatArray, from: Int = 0, n: Int = x.size - from) {
            var i = from; val end = from + n
            while (i < end) {
                var p = 0
                while (i < end && p < buf.size) {
                    val v = (x[i].coerceIn(-1f, 1f) * 32767f).let { if (it >= 0) it + 0.5f else it - 0.5f }.toInt()
                    buf[p++] = v.toByte(); buf[p++] = (v shr 8).toByte(); i++
                }
                out.write(buf, 0, p); bytes += p
            }
        }
        fun writeShorts(x: ShortArray, n: Int) {
            var i = 0
            while (i < n) { var p = 0; while (i < n && p < buf.size) { val v = x[i].toInt(); buf[p++] = v.toByte(); buf[p++] = (v shr 8).toByte(); i++ }; out.write(buf, 0, p); bytes += p }
        }
        override fun close() {
            out.flush(); out.close()
            RandomAccessFile(f, "rw").use { it.seek(0); it.write(header(bytes)) }
        }
    }

    fun readWavMono(f: File): Pair<FloatArray, Int> = WavReader(f).use { r ->
        val ch = r.info.channels; val out = FloatBuf((r.info.frames.coerceAtMost(Int.MAX_VALUE.toLong() / 2)).toInt() + 16)
        val tmp = FloatArray(8192 * ch)
        while (true) { val n = r.read(tmp, tmp.size - tmp.size % ch); if (n < 0) break; var i = 0; while (i + ch <= n) { var s = 0f; for (c in 0 until ch) s += tmp[i + c]; out.add(s / ch); i += ch } }
        Pair(out.toArray(), r.info.rate)
    }

    // ------------------------------------------------------------------ basic tools

    /** Linear-interpolation resampling with a light anti-alias pre-filter when going down. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val src = if (to < from) lowpass(x, from, to * 0.45) else x
        val n = ((src.size.toLong() * to) / from).toInt()
        val out = FloatArray(n); val step = from.toDouble() / to
        for (i in 0 until n) { val p = i * step; val k = p.toInt(); val f = (p - k).toFloat(); val a = src[min(k, src.size - 1)]; val b = src[min(k + 1, src.size - 1)]; out[i] = a + (b - a) * f }
        return out
    }

    class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var z1 = 0.0; private var z2 = 0.0
        fun run(x: FloatArray) { for (i in x.indices) { val v = x[i].toDouble(); val y = b0 * v + z1; z1 = b1 * v - a1 * y + z2; z2 = b2 * v - a2 * y; x[i] = y.toFloat() } }
        companion object {
            fun highpass(rate: Int, f: Double, q: Double = 0.7071): Biquad { val w = 2 * PI * f / rate; val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al; return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0) }
            fun lowpass(rate: Int, f: Double, q: Double = 0.7071): Biquad { val w = 2 * PI * f / rate; val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al; return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0) }
            fun peak(rate: Int, f: Double, db: Double, q: Double = 1.0): Biquad { val A = 10.0.pow(db / 40); val w = 2 * PI * f / rate; val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al / A; return Biquad((1 + al * A) / a0, -2 * c / a0, (1 - al * A) / a0, -2 * c / a0, (1 - al / A) / a0) }
        }
    }

    fun lowpass(x: FloatArray, rate: Int, f: Double): FloatArray { val y = x.copyOf(); Biquad.lowpass(rate, f).run(y); Biquad.lowpass(rate, f).run(y); return y }

    fun rmsDb(x: FloatArray, from: Int = 0, to: Int = x.size): Double { var s = 0.0; for (i in from until to) s += x[i].toDouble() * x[i]; val n = max(1, to - from); return 10 * log10(s / n + 1e-12) }
    fun peakDb(x: FloatArray): Double { var p = 0f; for (v in x) p = max(p, abs(v)); return 20 * log10(p + 1e-9) }

    // ------------------------------------------------------------------ FFT

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) { var bit = n shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit; if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1); val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat(); val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until half) {
                    val a = i + k; val b = a + half
                    val tr = re[b] * cr - im[b] * ci; val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { val s = 1f / n; for (i in 0 until n) { re[i] *= s; im[i] *= s } }
    }

    // ------------------------------------------------------------------ noise reduction

    /**
     * Spectral noise reduction: the noise spectrum is learnt from the quietest parts of the recording, then every
     * short frame keeps what stands above it (a smoothed Wiener-style gain, never below [floorDb]).
     * [strength] 0..1 sets how hard noise is pushed down. Returns the estimated noise reduction in dB.
     */
    fun denoise(x: FloatArray, rate: Int, strength: Double = 0.6, cancel: (() -> Unit)? = null): Double {
        if (x.size < 4096) return 0.0
        val n = when { rate <= 12000 -> 256; rate <= 24000 -> 512; else -> 1024 }.let { if (rate > 32000) 2048 else it }
        val hop = n / 4; val bins = n / 2 + 1
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val frames = (x.size - n) / hop + 1
        if (frames < 8) return 0.0
        // 1. frame energies → the quietest 12 % of frames (that are not digital silence) define the noise
        val en = DoubleArray(frames) { f -> var s = 0.0; val o = f * hop; for (i in 0 until n) { val v = x[o + i].toDouble(); s += v * v }; s / n }
        val sorted = en.filter { it > 1e-11 }.sorted()
        if (sorted.isEmpty()) return 0.0
        val cut = sorted[(sorted.size * 0.12).toInt().coerceIn(0, sorted.size - 1)]
        val noise = DoubleArray(bins); var nf = 0
        val re = FloatArray(n); val im = FloatArray(n)
        for (f in 0 until frames) {
            if (en[f] > cut || en[f] <= 1e-11) continue
            val o = f * hop
            for (i in 0 until n) { re[i] = x[o + i] * win[i]; im[i] = 0f }
            fft(re, im, false)
            for (k in 0 until bins) noise[k] += (re[k] * re[k] + im[k] * im[k]).toDouble()
            nf++
        }
        if (nf == 0) return 0.0
        for (k in 0 until bins) noise[k] = noise[k] / nf
        // 2. per-frame gains, smoothed over time and frequency against "musical noise"
        val s = strength.coerceIn(0.0, 1.0)
        val over = 1.0 + 2.0 * s                         // how far above the noise a bin must be to pass untouched
        val floor = 10.0.pow(-(6.0 + 16.0 * s) / 20.0)  // the most a bin is turned down: −6 … −22 dB
        // overlap-add into a ring of n samples: once a frame is done, its first hop samples are final and go back
        // into x (no later frame reads them), so the whole recording is never copied
        val out = FloatArray(n)
        val norm = FloatArray(n)
        fun flush(from: Int, to: Int) { for (i in from until to) { val k = i % n; if (norm[k] > 0.05f) x[i] = out[k] / norm[k]; out[k] = 0f; norm[k] = 0f } }
        val prev = DoubleArray(bins) { 1.0 }
        val g = DoubleArray(bins); val gs = DoubleArray(bins)
        var inE = 0.0; var outE = 0.0
        for (f in 0 until frames) {
            if (f and 255 == 0) cancel?.invoke()
            val o = f * hop
            for (i in 0 until n) { re[i] = x[o + i] * win[i]; im[i] = 0f }
            fft(re, im, false)
            for (k in 0 until bins) {
                val p = (re[k] * re[k] + im[k] * im[k]).toDouble() + 1e-20
                val w = max(0.0, 1.0 - over * noise[k] / p)
                g[k] = max(floor, sqrt(w))
            }
            for (k in 0 until bins) {
                val a = g[max(0, k - 1)]; val b = g[k]; val c = g[min(bins - 1, k + 1)]
                val sm = 0.25 * a + 0.5 * b + 0.25 * c
                // quick to open (speech onsets), slower to close (tails, less flutter)
                gs[k] = if (sm > prev[k]) 0.3 * prev[k] + 0.7 * sm else 0.65 * prev[k] + 0.35 * sm
                prev[k] = gs[k]
            }
            for (k in 0 until bins) {
                val gk = gs[k].toFloat()
                if (en[f] <= cut) { inE += (re[k] * re[k] + im[k] * im[k]).toDouble(); outE += (re[k] * re[k] + im[k] * im[k]).toDouble() * gk * gk }
                re[k] *= gk; im[k] *= gk
                if (k in 1 until n / 2) { re[n - k] = re[k]; im[n - k] = -im[k] }
            }
            fft(re, im, true)
            for (i in 0 until n) { val k = (o + i) % n; out[k] += re[i] * win[i]; norm[k] += win[i] * win[i] }
            flush(o, o + hop)
        }
        flush((frames - 1) * hop + hop, (frames - 1) * hop + n)   // the first and last few ms keep their original samples where the window is too thin
        return if (inE > 0 && outE > 0) 10 * log10(inE / outE) else 0.0
    }

    // ------------------------------------------------------------------ level

    /**
     * Evens out speech level: quiet passages are lifted and loud ones lowered toward [targetDb] RMS (at most
     * [maxBoostDb] up), pauses are left alone (no "breathing"), then peaks are limited to −1 dBFS.
     */
    fun level(x: FloatArray, rate: Int, targetDb: Double = -20.0, maxBoostDb: Double = 15.0) {
        if (x.isEmpty()) return
        val blk = max(1, rate / 100)                 // 10 ms blocks
        val nb = (x.size + blk - 1) / blk
        val bdb = DoubleArray(nb) { b -> rmsDb(x, b * blk, min(x.size, (b + 1) * blk)) }
        // a pause is anything 25 dB under the loud parts, or under the 20th percentile + 6 dB
        val srt = bdb.sorted()
        val loud = srt[(srt.size * 0.95).toInt().coerceIn(0, srt.size - 1)]
        val quiet = srt[(srt.size * 0.2).toInt().coerceIn(0, srt.size - 1)]
        val gate = max(loud - 25.0, quiet + 6.0)
        val win = 30                                  // 300 ms of speech decides the level
        var gainDb = 0.0; var applied = 0.0
        val gains = DoubleArray(nb)
        var acc = 0.0; var cnt = 0; val ring = DoubleArray(win) { Double.NaN }
        for (b in 0 until nb) {
            val v = bdb[b]
            val old = ring[b % win]; if (!old.isNaN()) { acc -= 10.0.pow(old / 10); cnt-- }
            if (v > gate) { ring[b % win] = v; acc += 10.0.pow(v / 10); cnt++ } else ring[b % win] = Double.NaN
            if (cnt >= 5) {
                val lvl = 10 * log10(acc / cnt + 1e-12)
                val want = (targetDb - lvl).coerceIn(-12.0, maxBoostDb)
                gainDb += (want - gainDb) * (if (want < gainDb) 0.25 else 0.05)   // fast down, slow up
            }
            // in pauses the gain eases back toward +3 dB at most, so the leftover noise is not pulled up with the speech
            val tgt = if (v > gate) gainDb else min(gainDb, 3.0)
            applied += (tgt - applied) * (if (tgt < applied) 0.12 else 0.3)
            gains[b] = applied
        }
        for (i in x.indices) {
            val b = i / blk; val f = (i % blk).toDouble() / blk
            val gdb = gains[b] + (gains[min(nb - 1, b + 1)] - gains[b]) * f
            x[i] = (x[i] * 10.0.pow(gdb / 20)).toFloat()
        }
        limit(x, rate)
    }

    /** Peak limiter at [ceilDb] with a short look-ahead (no clipping). */
    fun limit(x: FloatArray, rate: Int, ceilDb: Double = -1.0) {
        val ceil = 10.0.pow(ceilDb / 20).toFloat()
        val look = max(1, rate / 400)                 // 2.5 ms
        val rel = exp(-1.0 / (0.08 * rate)).toFloat()
        // required gain per sample (kept for the look-ahead window only), its minimum over the window, release smoothing;
        // x[j] is written look samples behind the reading position, so this works in place
        val rs = look + 2
        val need = FloatArray(rs)
        fun nd(i: Int): Float { val a = abs(x[i]); return if (a > ceil) ceil / a else 1f }
        val dq = ArrayDeque<Int>()
        var g = 1f
        for (i in 0 until x.size + look) {
            if (i < x.size) { val v = nd(i); need[i % rs] = v; while (dq.isNotEmpty() && need[dq.last() % rs] >= v) dq.removeLast(); dq.addLast(i) }
            val j = i - look
            if (j < 0) continue
            while (dq.isNotEmpty() && dq.first() < j) dq.removeFirst()
            val m = if (dq.isEmpty()) 1f else need[dq.first() % rs]
            g = if (m < g) m else 1f - (1f - g) * rel
            x[j] = (x[j] * min(g, m)).coerceIn(-ceil, ceil)
        }
    }

    class Report(val noiseDb: Double, val beforeDb: Double, val afterDb: Double, val peakDb: Double)

    /** The speech clean-up chain, in place: rumble cut, noise reduction, clarity lift, level evening, limiter. */
    fun enhance(x: FloatArray, rate: Int, strength: Double = 0.6, clarity: Boolean = true, levelOn: Boolean = true, cancel: (() -> Unit)? = null): Report {
        val before = rmsDb(x)
        Biquad.highpass(rate, 80.0).run(x); Biquad.highpass(rate, 80.0).run(x)
        val nr = if (strength > 0) denoise(x, rate, strength, cancel) else 0.0
        if (clarity && rate >= 8000) { Biquad.peak(rate, min(3000.0, rate * 0.35), 3.0, 0.9).run(x); Biquad.peak(rate, 200.0, -2.0, 0.8).run(x) }
        cancel?.invoke()
        if (levelOn) level(x, rate) else limit(x, rate)
        return Report(nr, before, rmsDb(x), peakDb(x))
    }
}
