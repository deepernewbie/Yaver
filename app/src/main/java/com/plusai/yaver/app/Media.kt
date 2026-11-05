package com.plusai.yaver.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder

/**
 * Audio and video files on the phone without ffmpeg: the phone's own decoders and encoders (MediaExtractor /
 * MediaCodec / MediaMuxer) read m4a, mp3, aac, ogg, opus, flac, wav, amr, 3gp, mp4/webm/mkv sound tracks and write
 * WAV or m4a (AAC). With [Dsp] it also cleans up speech recordings (noise, rumble, uneven level) in one step.
 * Python on the phone (Pyodide) cannot decode compressed audio; it can work on the WAV files this makes.
 */
object Media {
    private const val TIMEOUT_US = 10_000L
    /** Floats one working copy of the sound may have: about three copies exist at the peak, within ~45 % of the app's memory. */
    private fun maxSamples(): Long = maxOf(4_000_000L, (Runtime.getRuntime().maxMemory() * 0.45 / 3 / 4).toLong())

    class Info(val mime: String, val rate: Int, val channels: Int, val durationUs: Long, val tracks: List<String>, val bitrate: Int)

    private fun audioTrack(ex: MediaExtractor): Int =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IllegalArgumentException("the file has no sound track")

    private fun extractor(f: File): MediaExtractor {
        if (!f.isFile) throw IllegalArgumentException("no such file")
        val ex = MediaExtractor()
        try { ex.setDataSource(f.path) } catch (e: Exception) { ex.release(); throw IllegalArgumentException("the phone cannot read this file as audio or video (${e.message ?: e.javaClass.simpleName})") }
        return ex
    }

    fun info(f: File): Info {
        val ex = extractor(f)
        try {
            val tracks = (0 until ex.trackCount).map { i ->
                val t = ex.getTrackFormat(i); val m = t.getString(MediaFormat.KEY_MIME) ?: "?"
                when {
                    m.startsWith("audio/") -> "$m · ${t.int(MediaFormat.KEY_SAMPLE_RATE)} Hz · ${t.int(MediaFormat.KEY_CHANNEL_COUNT)} ch"
                    m.startsWith("video/") -> "$m · ${t.int(MediaFormat.KEY_WIDTH)}×${t.int(MediaFormat.KEY_HEIGHT)}"
                    else -> m
                }
            }
            val ai = audioTrack(ex); val fmt = ex.getTrackFormat(ai)
            return Info(fmt.getString(MediaFormat.KEY_MIME) ?: "?", fmt.int(MediaFormat.KEY_SAMPLE_RATE), fmt.int(MediaFormat.KEY_CHANNEL_COUNT),
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L, tracks, fmt.int(MediaFormat.KEY_BIT_RATE))
        } finally { ex.release() }
    }

    private fun MediaFormat.int(k: String) = try { if (containsKey(k)) getInteger(k) else 0 } catch (e: Exception) { 0 }

    /**
     * Decodes the sound track of [f]; [sink] gets interleaved float samples (count, sample rate, channels).
     * [startS]/[durS] cut a part (seconds; 0 = from the start / to the end).
     */
    fun decode(f: File, startS: Double = 0.0, durS: Double = 0.0, cancel: Cancel? = null, onPct: ((Int) -> Unit)? = null, sink: (FloatArray, Int, Int, Int) -> Unit) {
        val ex = extractor(f)
        var dec: MediaCodec? = null
        try {
            val ti = audioTrack(ex); ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val total = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val startUs = (startS * 1e6).toLong().coerceAtLeast(0)
            val endUs = if (durS > 0) startUs + (durS * 1e6).toLong() else Long.MAX_VALUE
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val dc = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw IllegalArgumentException("this phone has no decoder for $mime") }
            dec = dc
            dc.configure(fmt, null, null, 0); dc.start()
            var rate = fmt.int(MediaFormat.KEY_SAMPLE_RATE); var ch = maxOf(1, fmt.int(MediaFormat.KEY_CHANNEL_COUNT))
            var pcmFloat = false
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false; var lastPct = -1
            var buf = FloatArray(16384)
            var idle = 0
            while (!outDone) {
                cancel?.check()
                if (!inDone) {
                    val i = dc.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val b = dc.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0 || ex.sampleTime > endUs) { dc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else {
                            dc.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            if (total > 0 && onPct != null) { val p = ((ex.sampleTime - startUs) * 100 / maxOf(1L, minOf(total, endUs) - startUs)).toInt().coerceIn(0, 100); if (p != lastPct && p % 5 == 0) { lastPct = p; onPct(p) } }
                            ex.advance()
                        }
                    }
                }
                val o = dc.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o >= 0 -> {
                        idle = 0
                        val ob = dc.getOutputBuffer(o)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val bb = ob.slice().order(ByteOrder.nativeOrder())
                            val count = if (pcmFloat) info.size / 4 else info.size / 2
                            if (buf.size < count) buf = FloatArray(count)
                            if (pcmFloat) bb.asFloatBuffer().get(buf, 0, count) else { val sb = bb.asShortBuffer(); for (k in 0 until count) buf[k] = sb.get(k) / 32768f }
                            // the part before the requested start (decoding begins at the sync frame before it), and after the end
                            val pts = info.presentationTimeUs
                            var from = 0; var to = count
                            if (pts < startUs) from = minOf(count, (((startUs - pts) * rate / 1_000_000L).toInt()) * ch)
                            val frames = count / ch
                            val bufEnd = pts + frames * 1_000_000L / maxOf(1, rate)
                            if (bufEnd > endUs) to = maxOf(from, count - (((bufEnd - endUs) * rate / 1_000_000L).toInt()) * ch)
                            if (to > from) sink(if (from == 0) buf else buf.copyOfRange(from, to), to - from, rate, ch)
                        }
                        dc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = dc.outputFormat
                        of.int(MediaFormat.KEY_SAMPLE_RATE).let { if (it > 0) rate = it }
                        of.int(MediaFormat.KEY_CHANNEL_COUNT).let { if (it > 0) ch = it }
                        pcmFloat = of.containsKey("pcm-encoding") && of.getInteger("pcm-encoding") == 4   // AudioFormat.ENCODING_PCM_FLOAT
                    }
                    else -> if (inDone && ++idle > 500) outDone = true   // a decoder that never signals the end
                }
            }
        } finally {
            try { dec?.stop() } catch (_: Exception) {}
            try { dec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    /** Decodes to a mono float array (for the clean-up); long recordings are taken at a lower sample rate to fit in memory. */
    fun decodeMono(f: File, startS: Double, durS: Double, cancel: Cancel?, onPct: ((Int) -> Unit)?): Pair<FloatArray, Int> {
        val inf = info(f)
        val secs = (if (durS > 0) durS else (inf.durationUs / 1e6 - startS)).coerceAtLeast(1.0)
        val cap = if (inf.rate > 0) minOf(inf.rate.toLong(), 48000L, maxOf(8000L, (maxSamples() / secs).toLong())) else 48000L
        val step = if (inf.rate > 0 && cap < inf.rate) Math.ceil(inf.rate.toDouble() / cap).toInt() else 1   // crude decimation while decoding keeps memory low
        val out = Dsp.FloatBuf(minOf(maxSamples(), (secs * (inf.rate.takeIf { it > 0 } ?: 48000) / step).toLong() + 4096).toInt())
        var rate = 0; var phase = 0; var acc = 0f
        var lp1: Dsp.Biquad? = null; var lp2: Dsp.Biquad? = null; val one = FloatArray(1)
        decode(f, startS, durS, cancel, onPct) { b, n, r, ch ->
            if (rate != r && step > 1) { lp1 = Dsp.Biquad.lowpass(r, 0.45 * r / step); lp2 = Dsp.Biquad.lowpass(r, 0.45 * r / step) }   // against aliasing
            rate = r
            var i = 0
            while (i + ch <= n) {
                var s = 0f; for (c in 0 until ch) s += b[i + c]
                one[0] = s / ch; lp1?.run(one); lp2?.run(one)
                acc += one[0]; phase++
                if (phase == step) { out.add(acc / step); acc = 0f; phase = 0 }
                i += ch
            }
        }
        if (out.n == 0) throw IllegalArgumentException("no sound came out of the file")
        return Pair(out.toArray(), maxOf(1, rate / step))
    }

    /** Sound track → 16-bit WAV (streamed: any length). */
    fun toWav(src: File, dst: File, startS: Double = 0.0, durS: Double = 0.0, mono: Boolean = false, cancel: Cancel? = null, onPct: ((Int) -> Unit)? = null): Pair<Int, Int> {
        var w: Dsp.WavWriter? = null
        var tmp = FloatArray(0)
        try {
            decode(src, startS, durS, cancel, onPct) { b, n, r, ch ->
                val wr = w ?: Dsp.WavWriter(dst, r, if (mono) 1 else ch).also { w = it }
                if (mono && ch > 1) {
                    val fr = n / ch; if (tmp.size < fr) tmp = FloatArray(fr)
                    for (k in 0 until fr) { var s = 0f; for (c in 0 until ch) s += b[k * ch + c]; tmp[k] = s / ch }
                    wr.write(tmp, 0, fr)
                } else wr.write(b, 0, n)
            }
        } catch (e: Throwable) { try { w?.close() } catch (_: Exception) {}; w = null; dst.delete(); throw e
        } finally { w?.close() }
        val wr = w ?: throw IllegalArgumentException("no sound came out of the file")
        return Pair(wr.rate, wr.channels)
    }

    /** A source of interleaved 16-bit samples for the encoder. */
    interface PcmSource { val rate: Int; val channels: Int; fun read(out: ShortArray, max: Int): Int }

    class ArraySource(private val x: FloatArray, override val rate: Int) : PcmSource {
        override val channels = 1
        private var p = 0
        override fun read(out: ShortArray, max: Int): Int {
            if (p >= x.size) return -1
            val n = minOf(max, x.size - p)
            for (i in 0 until n) out[i] = (x[p + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            p += n; return n
        }
    }

    class WavSource(f: File) : PcmSource, AutoCloseable {
        private val r = Dsp.WavReader(f)
        override val rate = r.info.rate
        override val channels = r.info.channels
        private var fb = FloatArray(0)
        override fun read(out: ShortArray, max: Int): Int {
            if (fb.size < max) fb = FloatArray(max)
            val n = r.read(fb, max - max % channels)
            if (n <= 0) return -1
            for (i in 0 until n) out[i] = (fb[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            return n
        }
        override fun close() = r.close()
    }

    /** AAC sample rates the encoders accept. */
    private val AAC_RATES = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    /** 16-bit PCM → m4a (AAC-LC). */
    fun encodeM4a(src: PcmSource, dst: File, kbps: Int = 0, cancel: Cancel? = null) {
        if (src.rate !in AAC_RATES) throw IllegalArgumentException("AAC needs one of these sample rates: ${AAC_RATES.joinToString()} Hz (this sound is ${src.rate} Hz)")
        if (src.channels !in 1..2) throw IllegalArgumentException("m4a output supports mono or stereo (this sound has ${src.channels} channels)")
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, src.rate, src.channels)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, (if (kbps > 0) kbps else if (src.channels == 1) 96 else 160) * 1000)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 15)
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".part")
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var mux: MediaMuxer? = null
        var ok = false
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
            mux = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1; var started = false
            val info = MediaCodec.BufferInfo()
            var frames = 0L; var inDone = false; var outDone = false
            var sbuf = ShortArray(8192)
            var idle = 0
            while (!outDone) {
                cancel?.check()
                if (!inDone) {
                    val i = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val b = enc.getInputBuffer(i)!!; b.clear()
                        var max = b.capacity() / 2; max -= max % src.channels
                        if (sbuf.size < max) sbuf = ShortArray(max)
                        val n = src.read(sbuf, max)
                        val pts = frames * 1_000_000L / src.rate
                        if (n <= 0) { enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { b.order(ByteOrder.nativeOrder()).asShortBuffer().put(sbuf, 0, n); enc.queueInputBuffer(i, 0, n * 2, pts, 0); frames += n / src.channels }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(enc.outputFormat); mux.start(); started = true }
                else if (o < 0) { if (inDone && ++idle > 500) outDone = true }   // an encoder that never signals the end
                else {
                    idle = 0
                    val ob = enc.getOutputBuffer(o)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (ob != null && info.size > 0 && started) { ob.position(info.offset); ob.limit(info.offset + info.size); mux.writeSampleData(track, ob, info) }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (started) mux.stop()
            ok = started
        } finally {
            try { enc.stop() } catch (_: Exception) {}
            try { enc.release() } catch (_: Exception) {}
            try { mux?.release() } catch (_: Exception) {}
            if (!ok) tmp.delete()
        }
        if (!ok) throw IllegalStateException("the phone's AAC encoder produced no output")
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) { tmp.delete(); throw IllegalStateException("could not save ${dst.name}") }
    }

    private fun nearestAac(r: Int) = AAC_RATES.minByOrNull { Math.abs(it - r) } ?: 48000

    // ------------------------------------------------------------------ the tool

    private fun fmtTime(us: Long): String { val s = us / 1_000_000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun size(f: File): String { val n = f.length(); return if (n < 1024) "$n B" else if (n < 1048576) "%.1f KB".format(n / 1024.0) else "%.1f MB".format(n / 1048576.0) }

    private fun outPath(src: String, out: String, suffix: String, ext: String): String {
        if (out.isNotEmpty()) return VPath.normTool(out)
        val base = VPath.base(src).substringBeforeLast('.', VPath.base(src)).trim().trimEnd('.')
        val dir = if (VPath.under(src, VPath.WS) && !VPath.under(src, "${VPath.WS}/uploads")) VPath.parent(src) else VPath.WS
        var p = VPath.join(dir, "$base$suffix.$ext"); var k = 2
        while (VPath.exists(p)) p = VPath.join(dir, "$base$suffix-${k++}.$ext")
        return p
    }

    private fun cutMono(x: FloatArray, r: Int, start: Double, dur: Double): FloatArray {
        val s0 = (start * r).toInt().coerceIn(0, x.size); val e = if (dur > 0) minOf(x.size, s0 + (dur * r).toInt()) else x.size
        if (s0 >= x.size && x.isNotEmpty()) throw IllegalArgumentException("start is past the end of the recording (${"%.1f".format(x.size.toDouble() / r)} s)")
        return if (s0 == 0 && e == x.size) x else x.copyOfRange(s0, e)
    }

    fun run(a: JSONObject, cancel: Cancel?, progress: (String) -> Unit): String {
        val action = a.str("action").ifEmpty { "info" }.lowercase()
        val src = VPath.normTool(a.str("path").ifEmpty { throw IllegalArgumentException("path is required") })
        val sf = VPath.file(src)
        if (!sf.isFile) throw IllegalArgumentException("no such file $src")
        val start = a.optDouble("start", 0.0).let { if (it.isNaN()) 0.0 else it }
        val dur = a.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it }
        fun pct(label: String): (Int) -> Unit = { p -> progress("$label %$p") }
        val isWav = sf.name.lowercase().endsWith(".wav")
        return when (action) {
            "info" -> {
                if (isWav) { val i = Dsp.WavReader(sf).use { it.info }; "$src: WAV · ${i.rate} Hz · ${i.channels} ch · ${i.bits}-bit${if (i.float) " float" else ""} · ${fmtTime((i.seconds * 1e6).toLong())} · ${size(sf)}" }
                else { val i = info(sf); "$src: ${fmtTime(i.durationUs)} · ${size(sf)}\nsound: ${i.mime} · ${i.rate} Hz · ${i.channels} ch${if (i.bitrate > 0) " · ${i.bitrate / 1000} kbps" else ""}\ntracks: ${i.tracks.joinToString("; ")}" }
            }
            "to_wav", "wav", "decode" -> {
                val out = outPath(src, a.str("out"), "", "wav").let { if (it == src) outPath(src, "", "-copy", "wav") else it }
                val of = VPath.fileW(out)
                progress(Y.t("Decoding ", "Çözülüyor: ") + VPath.base(src))
                val (r, ch) = if (isWav) try { Dsp.WavReader(sf).use { rd -> Dsp.WavWriter(of, rd.info.rate, if (a.optBoolean("mono")) 1 else rd.info.channels).use { w ->
                        val c = rd.info.channels
                        val b = FloatArray(16384 * c); val m = FloatArray(16384)
                        var skip = (start * rd.info.rate).toLong() * c                       // samples to drop before the cut
                        var left = if (dur > 0) (dur * rd.info.rate).toLong() * c else Long.MAX_VALUE
                        while (left > 0) { cancel?.check(); val n0 = rd.read(b, b.size - b.size % c); if (n0 < 0) break
                            var from = 0; if (skip > 0) { val k = minOf(skip, n0.toLong()).toInt(); skip -= k; from = k }
                            val n = minOf((n0 - from).toLong(), left).toInt(); if (n <= 0) continue; left -= n
                            if (a.optBoolean("mono") && c > 1) { val fr = n / c; for (k in 0 until fr) { var s = 0f; for (j in 0 until c) s += b[from + k * c + j]; m[k] = s / c }; w.write(m, 0, fr) } else w.write(b, from, n) }
                        if (skip > 0) throw IllegalArgumentException("start is past the end of the recording (${"%.1f".format(rd.info.seconds)} s)")
                        Pair(w.rate, w.channels) } } } catch (e: Throwable) { of.delete(); throw e }
                    else toWav(sf, of, start, dur, a.optBoolean("mono"), cancel, pct(Y.t("Decoding", "Çözülüyor")))
                VPath.commit(out)
                val secs = Dsp.WavReader(of).use { it.info.seconds }
                "Wrote $out (16-bit WAV · $r Hz · $ch ch · ${fmtTime((secs * 1e6).toLong())} · ${size(of)}). Python can read it: soundfile.read(\"$out\") or scipy.io.wavfile.read(\"$out\"); write the result as WAV and convert it back with media action=to_m4a."
            }
            "to_m4a", "m4a", "encode" -> {
                val out = outPath(src, a.str("out"), "", "m4a").let { if (it == src) outPath(src, "", "-copy", "m4a") else it }
                val of = VPath.fileW(out)
                progress(Y.t("Encoding ", "Kodlanıyor: ") + VPath.base(out))
                // through a WAV (streamed): channels and sample rate are kept
                val t = if (isWav && start <= 0 && dur <= 0) sf else File(of.path + ".tmp.wav").also { tw ->
                    if (isWav) Dsp.WavReader(sf).use { rd -> Dsp.WavWriter(tw, rd.info.rate, rd.info.channels).use { w ->
                        val c = rd.info.channels; val b = FloatArray(16384 * c)
                        var skip = (start * rd.info.rate).toLong() * c; var left = if (dur > 0) (dur * rd.info.rate).toLong() * c else Long.MAX_VALUE
                        while (left > 0) { cancel?.check(); val n0 = rd.read(b, b.size - b.size % c); if (n0 < 0) break
                            var from = 0; if (skip > 0) { val k = minOf(skip, n0.toLong()).toInt(); skip -= k; from = k }
                            val n = minOf((n0 - from).toLong(), left).toInt(); if (n <= 0) continue; left -= n; w.write(b, from, n) }
                        if (skip > 0) throw IllegalArgumentException("start is past the end of the recording")
                    } }
                    else toWav(sf, tw, start, dur, false, cancel, pct(Y.t("Decoding", "Çözülüyor")))
                }
                try {
                    WavSource(t).use { ws ->
                        if (ws.rate in AAC_RATES && ws.channels <= 2) encodeM4a(ws, of, a.int("kbps", 0), cancel)
                        else { val (x, r) = Dsp.readWavMono(t); val rr = nearestAac(minOf(r, 48000)); encodeM4a(ArraySource(Dsp.resample(x, r, rr), rr), of, a.int("kbps", 0), cancel) }
                    }
                } finally { if (t !== sf) t.delete() }
                VPath.commit(out)
                "Wrote $out (m4a / AAC · ${size(of)})."
            }
            "enhance", "clean", "denoise" -> {
                val strength = a.optDouble("strength", 0.6).let { if (it.isNaN()) 0.6 else it.coerceIn(0.0, 1.0) }
                val ext = a.str("out").substringAfterLast('.', "").lowercase().ifEmpty { "m4a" }.let { if (it == "wav") "wav" else "m4a" }
                val out = outPath(src, a.str("out").let { o -> if (o.isNotEmpty() && !Regex("\\.(wav|m4a)$", RegexOption.IGNORE_CASE).containsMatchIn(o)) o.substringBeforeLast('.', o) + ".m4a" else o }, Y.t("-clean", "-temiz"), ext)
                val of = VPath.fileW(out)
                progress(Y.t("Decoding ", "Çözülüyor: ") + VPath.base(src))
                val (x, r0) = if (isWav) Dsp.readWavMono(sf).let { (x, r) -> Pair(cutMono(x, r, start, dur), r) } else decodeMono(sf, start, dur, cancel, pct(Y.t("Decoding", "Çözülüyor")))
                // work at a rate the encoder takes (and at most 48 kHz)
                val r = if (ext == "m4a") nearestAac(minOf(r0, 48000)) else minOf(r0, 48000)
                val y = Dsp.resample(x, r0, r)
                progress(Y.t("Cleaning up the sound…", "Ses temizleniyor…"))
                val rep = Dsp.enhance(y, r, strength, clarity = a.optBoolean("clarity", true), levelOn = a.optBoolean("level", true), cancel = { cancel?.check() })
                progress(Y.t("Saving ", "Kaydediliyor: ") + VPath.base(out))
                if (ext == "wav") Dsp.WavWriter(of, r, 1).use { it.write(y) } else encodeM4a(ArraySource(y, r), of, a.int("kbps", 0), cancel)
                VPath.commit(out)
                val secs = y.size.toDouble() / r
                "Cleaned up the sound of $src → $out (${if (ext == "wav") "WAV" else "m4a/AAC"} · mono · $r Hz · ${fmtTime((secs * 1e6).toLong())} · ${size(of)}).\n" +
                    "Done: rumble below 80 Hz cut; background noise turned down by about ${"%.0f".format(rep.noiseDb)} dB (noise learnt from the quietest parts; strength ${"%.1f".format(strength)})" +
                    (if (a.optBoolean("clarity", true)) "; speech clarity lifted (+3 dB around 3 kHz, less boom)" else "") +
                    (if (a.optBoolean("level", true)) "; level evened out (quiet speech lifted, loud parts lowered, peaks limited to −1 dBFS)" else "") +
                    ". Average level ${"%.1f".format(rep.beforeDb)} → ${"%.1f".format(rep.afterDb)} dBFS.\n" +
                    "Tell the user they can tap $out to listen. If speech sounds thin or watery, run again with a lower strength (e.g. 0.4); if noise is still audible, a higher one (0.8)."
            }
            else -> throw IllegalArgumentException("unknown action \"$action\": use info, to_wav, to_m4a or enhance")
        }
    }

    fun register() {
        Tools.add(Tool(
            "media",
            "Audio and video files with the phone's own codecs (there is no ffmpeg, and Python here cannot decode m4a/mp3/aac/ogg/opus/amr). " +
                "action=info: duration, codec, sample rate, channels, tracks. " +
                "action=enhance: clean up a speech recording in one step — background noise reduction, rumble cut, clearer speech, even level — and save it as m4a (or .wav with out=…wav); use this when the user asks to improve, clean or denoise a recording, or make speech clearer. " +
                "action=to_wav: decode the sound (of any audio or video file) to 16-bit WAV so Python (numpy/scipy/soundfile) can process it. " +
                "action=to_m4a: encode a WAV (or any audio) to m4a/AAC. start/duration (seconds) cut a part.",
            P.props(
                "action" to P.s("What to do", "info", "enhance", "to_wav", "to_m4a"),
                "path" to P.s("Input audio or video file"),
                "out" to P.s("Output path (optional; by default next to the input, e.g. name-temiz.m4a)"),
                "strength" to P.n("enhance: noise reduction strength 0–1 (default 0.6)"),
                "clarity" to P.b("enhance: lift speech clarity (default true)"),
                "level" to P.b("enhance: even out the level (default true)"),
                "mono" to P.b("to_wav: mix down to one channel"),
                "start" to P.n("Start at this second (optional)"),
                "duration" to P.n("Only this many seconds (optional)"),
                "kbps" to toolInt("m4a bit rate (default 96 mono / 160 stereo)")
            ),
            listOf("action", "path")
        ) { a, ctx -> run(a, ctx.cancel) { ctx.progress(it) } })
        ShellHooks.media = { args, cwd, cancel -> shell(args, cwd, cancel) }
    }

    /** ffmpeg / ffprobe / media in the virtual terminal: the common shapes mapped onto the tool. */
    fun shell(args: List<String>, cwd: String, cancel: Cancel?): String {
        val cmd = args.firstOrNull() ?: return ""
        val rest = args.drop(1)
        fun P(x: String) = VPath.norm(x, cwd)
        fun opt(k: String) = rest.indexOf(k).let { if (it >= 0) rest.getOrNull(it + 1) else null }
        fun secs(s: String?): Double = s?.let { t -> if (t.contains(':')) t.split(':').fold(0.0) { acc, p -> acc * 60 + (p.toDoubleOrNull() ?: 0.0) } else t.toDoubleOrNull() } ?: 0.0
        return when (cmd) {
            "ffprobe" -> run(JSONObject().put("action", "info").put("path", P(rest.lastOrNull { !it.startsWith("-") } ?: throw ShErr("usage: ffprobe FILE"))), cancel) {} + "\n"
            "ffmpeg" -> {
                val inp = opt("-i") ?: throw ShErr("usage: ffmpeg [-ss START] -i IN [-t DUR] [-ac 1] OUT.wav|OUT.m4a  (the phone's codecs; for noise clean-up use: media enhance IN)")
                val out = rest.lastOrNull()?.takeIf { it != inp && !it.startsWith("-") } ?: throw ShErr("ffmpeg: give an output file (.wav or .m4a)")
                val ext = out.substringAfterLast('.').lowercase()
                val action = when (ext) { "wav" -> "to_wav"; "m4a", "aac", "mp4" -> "to_m4a"; else -> throw ShErr("ffmpeg here writes .wav or .m4a only (got .$ext)") }
                val j = JSONObject().put("action", action).put("path", P(inp)).put("out", P(out)).put("mono", opt("-ac") == "1")
                opt("-ss")?.let { j.put("start", secs(it)) }; opt("-t")?.let { j.put("duration", secs(it)) }
                if (rest.contains("-af") || rest.contains("-filter:a")) return "ffmpeg filters are not available here; for noise reduction and clearer speech use: media enhance $inp\n" + run(j, cancel) {} + "\n"
                run(j, cancel) {} + "\n"
            }
            else -> { // media ACTION FILE [OUT] [--strength 0.6]
                if (rest.size < 2) throw ShErr("usage: media info|enhance|to_wav|to_m4a FILE [OUT] [--strength 0..1] [--start S] [--duration S]")
                val j = JSONObject().put("action", rest[0]).put("path", P(rest[1]))
                rest.getOrNull(2)?.takeIf { !it.startsWith("--") }?.let { j.put("out", P(it)) }
                opt("--strength")?.let { j.put("strength", it.toDoubleOrNull() ?: 0.6) }
                opt("--start")?.let { j.put("start", secs(it)) }; opt("--duration")?.let { j.put("duration", secs(it)) }
                run(j, cancel) {} + "\n"
            }
        }
    }
}
