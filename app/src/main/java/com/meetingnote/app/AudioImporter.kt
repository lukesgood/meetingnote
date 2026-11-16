package com.meetingnote.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * 외부 음성 파일(m4a, mp3, aac, ogg, wav, 동영상의 오디오 트랙 등)을
 * 시스템 디코더(MediaCodec)로 풀어 Whisper 입력 형식(16kHz mono PCM16 WAV)으로 저장.
 * 스트리밍 처리라 파일 길이와 무관하게 메모리 사용이 일정하다.
 */
object AudioImporter {
    private const val OUT_RATE = AudioRecorder.SAMPLE_RATE

    fun decodeToWav(context: Context, uri: Uri, out: File, onProgress: (Float) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("오디오 트랙이 없는 파일입니다")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val resampler = Resampler()

        val raf = RandomAccessFile(out, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44))
        val os = BufferedOutputStream(java.io.FileOutputStream(raf.fd), 1 shl 16)
        var written = 0L

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            if (durationUs > 0) onProgress((ex.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        // 모노 다운믹스
                        val frames: FloatArray
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            val n = fb.remaining() / channels
                            frames = FloatArray(n) { i -> var s = 0f; for (c in 0 until channels) s += fb.get(i * channels + c); s / channels }
                        } else {
                            val sb = buf.asShortBuffer()
                            val n = sb.remaining() / channels
                            frames = FloatArray(n) { i -> var s = 0f; for (c in 0 until channels) s += sb.get(i * channels + c); s / channels / 32768f }
                        }
                        val res = resampler.process(frames, inRate, OUT_RATE)
                        val bytes = ByteArray(res.size * 2)
                        for (k in res.indices) {
                            val v = (res[k].coerceIn(-1f, 1f) * 32767f).toInt()
                            bytes[2 * k] = (v and 0xff).toByte(); bytes[2 * k + 1] = (v shr 8).toByte()
                        }
                        os.write(bytes)
                        written += res.size
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
            os.flush()
            val dataLen = written * 2
            val h = java.nio.ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(minOf(36 + dataLen, 0xFFFFFFFFL).toInt()).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(OUT_RATE).putInt(OUT_RATE * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(minOf(dataLen, 0xFFFFFFFFL).toInt())
            raf.seek(0); raf.write(h.array()); raf.close()
        }
        require(written > OUT_RATE / 2) { "음성 길이가 너무 짧거나 디코딩에 실패했습니다" }
        onProgress(1f)
    }

    /**
     * 스트리밍 리샘플러: 다운샘플 시 이동평균 저역통과(에일리어싱 완화) 후 선형 보간.
     * 음성인식 용도로 충분한 품질이며 블록 경계가 이어지도록 상태를 유지한다.
     */
    private class Resampler {
        private var pos = 0.0          // 입력 기준 다음 출력 위치
        private var prev = 0f          // 직전 블록 마지막 샘플
        private val hist = FloatArray(8)

        fun process(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
            if (inRate == outRate) return input
            val ratio = inRate.toDouble() / outRate
            val taps = ratio.toInt().coerceIn(1, 8)
            val x = if (taps > 1) FloatArray(input.size) { i ->
                // 이동평균 (이전 블록 이력 포함)
                var s = 0f
                for (k in 0 until taps) {
                    val j = i - k
                    s += if (j >= 0) input[j] else hist[hist.size + j]
                }
                s / taps
            } else input
            for (k in hist.indices) {
                val j = input.size - hist.size + k
                hist[k] = if (j >= 0) input[j] else 0f
            }
            val out = ArrayList<Float>((input.size / ratio).toInt() + 2)
            // pos: -1 위치는 prev (이전 블록 마지막)
            while (pos < x.size - 1) {
                val i0 = Math.floor(pos).toInt()
                val frac = (pos - i0).toFloat()
                val a = if (i0 < 0) prev else x[i0]
                val b = x[i0 + 1]
                out.add(a + (b - a) * frac)
                pos += ratio
            }
            pos -= x.size
            prev = x.last()
            return out.toFloatArray()
        }
    }
}
