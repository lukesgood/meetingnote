package com.meetingnote.app

import android.util.Log
import com.meetingnote.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 녹음과 동시에 음성인식을 수행하는 파이프라인.
 *
 * 최적화 포인트
 *  - 녹음 중에 25~30초 단위로 나눠 바로 인식 → 회의 종료 시 마지막 조각만 남음
 *  - 조각 경계는 25~30초 구간에서 가장 조용한 지점으로 잡아 단어가 잘리지 않게 함
 *  - 거의 무음인 조각은 인식 생략
 *  - Whisper 내장 Silero VAD로 조각 내부의 무음 제거
 *  - 이전 조각의 마지막 문장을 initial prompt로 넘겨 문맥 유지
 */
class StreamingTranscriber(
    private val scope: CoroutineScope,
    private val whisper: WhisperContext,
    private val vadPath: String?,
    private val threads: Int,
    vocabulary: String = "",
    private val onLines: (List<TranscriptLine>) -> Unit,
    private val onChunkDone: (audioMs: Long, procMs: Long) -> Unit,
) {
    private val sr = AudioRecorder.SAMPLE_RATE
    private val minChunk = 25 * sr
    private val maxChunk = 30 * sr
    private val frame = sr / 10 // 100ms 에너지 분석 단위

    private val buffer = ShortArray(maxChunk + sr)
    private var len = 0
    private var bufferStartSample = 0L
    private val queue = Channel<Pair<Long, FloatArray>>(Channel.UNLIMITED)
    private var lastText = ""
    /** 용어 사전은 Whisper의 initial prompt로 넣어 표기를 유도 (토큰 한도 고려해 길이 제한) */
    private val vocabPrompt = vocabulary.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(", ").take(150).let { if (it.isEmpty()) "" else "$it. " }
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    /** 대기 중인 조각 수 (파일 인식 시 메모리 사용 제한용) */
    val pendingChunks get() = pending.get()
    private val worker: Job = scope.launch {
        for ((offset, pcm) in queue) {
            try { process(offset, pcm) } finally { pending.decrementAndGet() }
        }
    }

    @Synchronized
    fun feed(samples: ShortArray) {
        System.arraycopy(samples, 0, buffer, len, samples.size)
        len += samples.size
        if (len >= maxChunk) {
            val cut = findCut()
            emit(cut)
        }
    }

    /** 녹음 종료: 남은 버퍼를 처리하고 모든 작업이 끝날 때까지 대기 */
    suspend fun finish() {
        synchronized(this) { if (len > 0) emit(len) }
        queue.close()
        worker.join()
    }

    private fun findCut(): Int {
        var bestIdx = maxChunk
        var bestEnergy = Double.MAX_VALUE
        var i = minChunk
        while (i + frame <= maxChunk) {
            var e = 0.0
            for (j in i until i + frame) {
                val v = buffer[j].toDouble(); e += v * v
            }
            if (e < bestEnergy) {
                bestEnergy = e; bestIdx = i + frame / 2
            }
            i += frame / 2
        }
        return bestIdx
    }

    private fun emit(n: Int) {
        val pcm = FloatArray(n) { buffer[it] / 32768f }
        val offset = bufferStartSample
        System.arraycopy(buffer, n, buffer, 0, len - n)
        len -= n
        bufferStartSample += n
        pending.incrementAndGet()
        queue.trySend(offset to pcm)
    }

    private fun process(offsetSamples: Long, pcm: FloatArray) {
        // 가장 큰 100ms 구간의 RMS로 무음 판단 (평균으로 판단하면 조용한 화자의 발언이 통째로 누락될 수 있음)
        var peakRms = 0.0
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + frame, pcm.size)
            var e = 0.0
            for (j in i until end) e += pcm[j] * pcm[j]
            peakRms = maxOf(peakRms, sqrt(e / (end - i)))
            i = end
        }
        val audioMs = pcm.size * 1000L / sr
        if (peakRms < 0.004 || pcm.size < sr / 2) {   // 가장 큰 구간도 -48dBFS 미만이면 무음
            Log.i("Transcriber", "chunk ${audioMs}ms skipped (peak %.4f)".format(peakRms))
            onChunkDone(audioMs, 0)
            return
        }
        // 원거리·작은 목소리 보정: 가장 큰 구간이 약 -20dBFS가 되도록 증폭 (최대 10배, 클리핑 방지)
        val gain = (0.1 / peakRms).coerceIn(1.0, 10.0).toFloat()
        if (gain > 1.05f) for (k in pcm.indices) pcm[k] = (pcm[k] * gain).coerceIn(-1f, 1f)
        val t0 = System.currentTimeMillis()
        val segs = try {
            whisper.transcribe(
                pcm, threads = threads, language = "ko", beamSize = 5,
                initialPrompt = (vocabPrompt + lastText.takeLast(100)).ifBlank { null },
                vadModelPath = vadPath,
            )
        } catch (e: Exception) {
            Log.e("Transcriber", "transcribe failed", e); emptyList()
        }
        val procMs = System.currentTimeMillis() - t0
        val offsetMs = offsetSamples * 1000 / sr
        val lines = segs.map { TranscriptLine(offsetMs + it.startMs, offsetMs + it.endMs, it.text) }
        if (lines.isNotEmpty()) {
            lastText = lines.joinToString(" ") { it.text }
            onLines(lines)
        }
        Log.i("Transcriber", "chunk ${audioMs}ms -> ${procMs}ms (RTF %.2f)".format(procMs.toDouble() / audioMs))
        onChunkDone(audioMs, procMs)
    }
}
