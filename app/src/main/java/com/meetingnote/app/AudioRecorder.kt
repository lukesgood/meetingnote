package com.meetingnote.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 16kHz mono PCM16 녹음. WAV 파일로 저장하면서 프레임을 콜백으로 전달한다.
 * Whisper 입력 형식(16kHz mono)과 동일하게 녹음해 리샘플링 비용을 없앤다.
 */
class AudioRecorder(
    private val outFile: File,
    private val onFrames: (ShortArray) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
    }

    @Volatile private var running = false
    @Volatile var paused = false
    private var worker: Thread? = null
    var samplesWritten = 0L
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // VOICE_RECOGNITION: AGC/노이즈 처리를 최소화한 음성인식용 소스
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, SAMPLE_RATE)
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "마이크를 초기화할 수 없습니다" }

        running = true
        worker = thread(name = "audio-rec", priority = Thread.MAX_PRIORITY) {
            val raf = RandomAccessFile(outFile, "rw")
            raf.setLength(0)
            raf.write(ByteArray(44)) // 헤더 자리
            val buf = ShortArray(SAMPLE_RATE / 10) // 100ms
            val bytes = ByteArray(buf.size * 2)
            rec.startRecording()
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0 || paused) continue
                    for (i in 0 until n) {
                        bytes[2 * i] = (buf[i].toInt() and 0xff).toByte()
                        bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte()
                    }
                    raf.write(bytes, 0, n * 2)
                    samplesWritten += n
                    val frame = buf.copyOf(n)
                    onFrames(frame)
                    var sum = 0.0
                    for (s in frame) sum += s * s.toDouble()
                    onLevel((sqrt(sum / n) / 32768.0).toFloat())
                }
            } finally {
                rec.stop()
                rec.release()
                writeWavHeader(raf, samplesWritten)
                raf.close()
            }
        }
    }

    fun stop() {
        running = false
        worker?.join()
        worker = null
    }

    private fun writeWavHeader(raf: RandomAccessFile, samples: Long) {
        val dataLen = samples * 2
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val riff = minOf(36 + dataLen, 0xFFFFFFFFL)
        h.put("RIFF".toByteArray()).putInt(riff.toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(minOf(dataLen, 0xFFFFFFFFL).toInt())
        raf.seek(0)
        raf.write(h.array())
    }
}

/** WAV(16kHz mono PCM16) 파일 길이(샘플 수) */
fun wavSampleCount(file: File): Long = (file.length() - 44).coerceAtLeast(0) / 2

/**
 * WAV(16kHz mono PCM16)를 블록 단위로 읽어 전달. 파일 전체를 메모리에 올리지 않으므로 길이 제한이 없다.
 * (헤더의 길이 필드는 무시하고 실제 파일 크기 기준으로 읽음 → 2GB 초과 파일도 처리)
 */
inline fun readWavBlocks(file: File, blockSamples: Int, onBlock: (ShortArray) -> Unit) {
    java.io.BufferedInputStream(file.inputStream(), 1 shl 16).use { input ->
        input.skip(44)
        val bytes = ByteArray(blockSamples * 2)
        while (true) {
            var read = 0
            while (read < bytes.size) {
                val r = input.read(bytes, read, bytes.size - read)
                if (r < 0) break
                read += r
            }
            val n = read / 2
            if (n == 0) break
            onBlock(ShortArray(n) { i ->
                ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort()
            })
            if (read < bytes.size) break
        }
    }
}
