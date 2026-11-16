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
        // 100ms 구간별 RMS
        val frames = ArrayList<Double>(pcm.size / frame + 1)
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + frame, pcm.size)
            var e = 0.0
            for (j in i until end) e += pcm[j] * pcm[j]
            frames += sqrt(e / (end - i))
            i = end
        }
        val sorted = frames.sorted()
        val peakRms = sorted.lastOrNull() ?: 0.0
        val noiseFloor = sorted.getOrElse(sorted.size / 5) { 0.0 }          // 하위 20% = 배경 소음
        // 말소리 구간: -48dBFS 이상이면서 배경 소음보다 12dB 이상 큰 100ms 구간
        val speechFrames = frames.count { it >= 0.004 && it >= noiseFloor * 4 }
        val audioMs = pcm.size * 1000L / sr
        // 말소리가 0.3초 미만이면 무음/잡음으로 보고 건너뜀 (클릭·기침 한 번에 증폭+환각이 생기는 것 방지)
        if (speechFrames < 3 || pcm.size < sr / 2) {
            Log.i("Transcriber", "chunk ${audioMs}ms skipped (peak %.4f, floor %.4f, speech %d)".format(peakRms, noiseFloor, speechFrames))
            onChunkDone(audioMs, 0)
            return
        }
        // 원거리·작은 목소리 보정: 가장 큰 구간이 약 -20dBFS가 되도록 증폭 (최대 10배).
        // 단, 배경 소음이 -46dBFS를 넘지 않게 제한해 잡음을 말소리로 착각하지 않도록 함.
        val noiseCap = if (noiseFloor > 1e-6) 0.005 / noiseFloor else 10.0
        val gain = minOf(0.1 / peakRms, 10.0, noiseCap).coerceAtLeast(1.0).toFloat()
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
        val lines = segs.filterNot { Hallucination.isLikely(it.text) }
            .map { TranscriptLine(offsetMs + it.startMs, offsetMs + it.endMs, it.text) }
        if (lines.size < segs.size) Log.i("Transcriber", "dropped ${segs.size - lines.size} hallucinated segment(s)")
        if (lines.isNotEmpty()) {
            lastText = lines.joinToString(" ") { it.text }
            onLines(lines)
        }
        Log.i("Transcriber", "chunk ${audioMs}ms -> ${procMs}ms (RTF %.2f)".format(procMs.toDouble() / audioMs))
        onChunkDone(audioMs, procMs)
    }
}

/**
 * Whisper가 무음·잡음에서 지어내는 대표 문구 (방송·유튜브 자막 학습의 부작용).
 * 문장 전체가 이런 문구일 때만 제거하고, 실제 발언 안에 섞인 경우는 건드리지 않는다.
 */
object Hallucination {
    private val patterns = listOf(
        "시청해 ?주셔서 감사",
        "시청해 ?주신 여러분",
        "시청자 여러분",
        "구독(과|과 좋아요| 좋아요|,? ?좋아요)",
        "좋아요(와|,)? ?구독",
        "알림 ?설정",
        "다음 (영상|시간|주)에서 (만나|뵙)",
        "(지금까지|이상) .{0,12}(뉴스|스토리|기자)",
        "뉴스 스토리",
        "MBC|KBS|SBS|YTN|JTBC",
        "자막 (제공|제작)|한글자막|자막 by",
        "^[(\\[].*[)\\]]$",                       // 문장 전체가 괄호: (박수), [음악], (두 번째 요리는)
        "^(음악|박수|웃음)$",
    ).map { Regex(it) }

    fun isLikely(text: String): Boolean {
        val t = text.trim().trimEnd('.', '!', '?', '~', ' ')
        if (t.isEmpty()) return true
        return t.length <= 40 && patterns.any { it.containsMatchIn(t) }
    }
}
