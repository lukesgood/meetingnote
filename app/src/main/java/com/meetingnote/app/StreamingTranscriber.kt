package com.meetingnote.app

import android.util.Log
import com.meetingnote.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 실시간 인식의 온도 폴백 간격. whisper.cpp 기본값 0.2는 품질 기준 미달 시 최대 6번 다시 디코딩해
 * 잡음 섞인 조각에서 지연이 크게 튄다. 0.6이면 재시도 1회(온도 0 → 0.6). 놓친 부분은 회의 후 정밀 인식이 보완.
 */
const val LIVE_TEMPERATURE_INC = 0.6f

/** 정밀 인식 VAD: 기본값(0.5 / 30ms)보다 느슨하게 잡아 작은 목소리와 문장 첫머리를 덜 자름 */
const val REFINE_VAD_THRESHOLD = 0.35f
const val REFINE_VAD_PAD_MS = 150

/**
 * 녹음과 동시에 음성인식을 수행하는 파이프라인.
 *
 * 최적화 포인트
 *  - 녹음 중에 발화 단위(4~15초)로 나눠 바로 인식 → 회의 종료 시 마지막 조각만 남음
 *  - 조각 경계는 0.5초 이상 쉬는 지점(없으면 10~15초 구간에서 가장 조용한 지점)으로 잡아 단어가 잘리지 않게 함
 *  - 거의 무음인 조각은 인식 생략
 *  - Whisper 내장 Silero VAD로 조각 내부의 무음 제거
 *  - 이전 조각의 마지막 문장을 initial prompt로 넘겨 문맥 유지
 */
class StreamingTranscriber(
    private val scope: CoroutineScope,
    initialWhisper: WhisperContext,
    private val vadPath: String?,
    private val threads: Int,
    vocabulary: String = "",
    /** 인식 언어: "ko" 고정, 빈 문자열이면 한국어/영어 자동 감지 (통역 모드) */
    private val language: String = "ko",
    /** 짧은 조각의 인코더 창을 실제 길이에 맞게 줄임 (속도 향상, 정확도는 실측으로 판단) */
    private val trimEncoder: Boolean = false,
    private val onLines: (List<TranscriptLine>) -> Unit,
    private val onChunkDone: (audioMs: Long, procMs: Long) -> Unit,
) {
    private val sr = AudioRecorder.SAMPLE_RATE
    private val whisper: WhisperContext = initialWhisper
    /** 실시간 인식 중단 (인식이 계속 밀릴 때). 이후 들어오는 음성과 대기 조각은 버린다 — 회의 후 녹음 전체를 다시 인식 */
    @Volatile var stopped = false
        private set
    @Volatile private var onStopped: (() -> Unit)? = null

    /** 지금 처리 중인 조각이 끝나면 멈추고 onIdle 호출 (추론 스레드에서. 모델 해제는 호출한 쪽에서) */
    fun stop(onIdle: () -> Unit) { onStopped = onIdle; stopped = true }

    /**
     * 발화 단위 분할: 말소리가 나온 뒤 0.5초 이상 쉬면 그 쉼의 가운데에서 자른다.
     * 최소 4초(너무 짧으면 문맥 부족), 최대 15초(그 안에서 가장 조용한 곳). 기존 25~30초 고정 분할 대비 지연 단축.
     */
    private val minChunk = 4 * sr
    private val maxChunk = 15 * sr
    private val pauseFrames = 5
    private val frame = sr / 10 // 100ms 에너지 분석 단위

    // 실시간 분할 판단용 상태
    private var frameAcc = 0              // 현재 100ms 프레임에 모은 샘플 수
    private var frameEnergy = 0.0
    private var quietRun = 0              // 버퍼 끝에서 연속된 조용한 프레임 수
    private var hasSpeech = false         // 현재 버퍼에 말소리가 있었는지
    /** 최근 30초 프레임 RMS. 배경 소음은 조각 안이 아니라 이 긴 구간의 하위 10%로 추정
     *  (짧은 조각이 말소리로만 채워지면 조각 안 기준으로는 말소리를 소음으로 오인해 통째로 버리는 문제) */
    private val recentRms = ArrayDeque<Double>()
    @Volatile private var noiseFloor = 0.0

    private val buffer = ShortArray(maxChunk + sr)
    private var len = 0
    private var bufferStartSample = 0L
    private val queue = Channel<Triple<Long, FloatArray, Double>>(Channel.UNLIMITED)
    private var lastText = ""
    private var lastLang: String? = null
    /** 최근 채택한 문장 (정규화). 같은 문장이 반복되면 버림 */
    private val recent = ArrayDeque<String>()
    /** 용어 사전은 Whisper의 initial prompt로 넣어 표기를 유도 (토큰 한도 고려해 길이 제한) */
    private val vocabPrompt = vocabulary.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(", ").take(150).let { if (it.isEmpty()) "" else "$it. " }
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    /** 대기 중인 조각 수 (파일 인식 시 메모리 사용 제한용) */
    val pendingChunks get() = pending.get()
    private val worker: Job = scope.launch {
        for ((offset, pcm, floor) in queue) {
            try { if (!stopped) process(offset, pcm, floor) } finally { pending.decrementAndGet() }
            if (stopped) onStopped?.let { onStopped = null; it() }
            kotlinx.coroutines.yield()   // 같은 추론 스레드의 번역 작업이 끼어들 수 있게
        }
    }

    @Synchronized
    fun feed(samples: ShortArray) {
        if (stopped) return
        System.arraycopy(samples, 0, buffer, len, samples.size)
        len += samples.size
        // 100ms 프레임 단위로 말소리/쉼 판단
        for (v in samples) {
            frameEnergy += v.toDouble() * v; frameAcc++
            if (frameAcc == frame) {
                onFrame(sqrt(frameEnergy / frame) / 32768.0)
                frameAcc = 0; frameEnergy = 0.0
            }
        }
        when {
            // 말소리 뒤 0.5초 쉼 → 쉼의 가운데에서 자름
            len >= minChunk && hasSpeech && quietRun >= pauseFrames -> emit(len - quietRun * frame / 2)
            len >= maxChunk -> emit(findCut())
        }
    }

    private fun onFrame(rms: Double) {
        recentRms.addLast(rms); while (recentRms.size > 300) recentRms.removeFirst()
        val floor = recentRms.sorted()[recentRms.size / 10]
        noiseFloor = floor
        val speech = rms >= 0.004 && rms >= floor * 4
        val quiet = rms < maxOf(0.003, floor * 2.5)
        if (speech) hasSpeech = true
        quietRun = if (quiet) quietRun + 1 else 0
    }

    /** 녹음 종료: 남은 버퍼를 처리하고 모든 작업이 끝날 때까지 대기 */
    suspend fun finish() {
        synchronized(this) { if (len > 0 && !stopped) emit(len) }
        queue.close()
        worker.join()
        onStopped?.let { onStopped = null; it() }
    }

    private fun findCut(): Int {
        var bestIdx = maxChunk
        var bestEnergy = Double.MAX_VALUE
        var i = maxChunk * 2 / 3
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
        // 잘린 뒤 남은 꼬리(쉼 절반)는 다음 조각의 시작이므로 말소리 상태 초기화
        hasSpeech = false; quietRun = 0
        val pcm = FloatArray(n) { buffer[it] / 32768f }
        val offset = bufferStartSample
        System.arraycopy(buffer, n, buffer, 0, len - n)
        len -= n
        bufferStartSample += n
        pending.incrementAndGet()
        queue.trySend(Triple(offset, pcm, if (recentRms.size >= 20) noiseFloor else -1.0))
    }

    private fun process(offsetSamples: Long, pcm: FloatArray, historyFloor: Double) {
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
        // 배경 소음: 최근 30초 기록이 있으면 그것(조각보다 긴 구간), 없으면 조각 안 하위 20%
        val chunkFloor = sorted.getOrElse(sorted.size / 5) { 0.0 }
        val noiseFloor = if (historyFloor >= 0) minOf(historyFloor, chunkFloor) else chunkFloor
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
        val segsResult = try {
            whisper.transcribe(
                pcm, threads = threads, language = language, beamSize = 5,
                initialPrompt = (vocabPrompt + lastText.takeLast(100)).ifBlank { null },
                vadModelPath = vadPath,
                // 인코더 창: 50프레임/초 + 여유, 64 배수 (30초 = 1500)
                audioCtx = if (trimEncoder) ((pcm.size * 50L / sr + 64 + 63) / 64 * 64).toInt().coerceAtMost(1500) else 0,
                koEnOnly = language.isEmpty(),
                temperatureInc = LIVE_TEMPERATURE_INC,
            )
        } catch (e: Exception) {
            Log.e("Transcriber", "transcribe failed", e); null
        }
        val segLang = segsResult?.language?.takeIf { it == "ko" || it == "en" }
        // 언어가 바뀌면 이전 문맥(다른 언어)을 프롬프트로 넘기지 않음
        if (segLang != null && segLang != lastLang) { lastText = ""; lastLang = segLang }
        val segs = segsResult?.segments.orEmpty()
        val procMs = System.currentTimeMillis() - t0
        val offsetMs = offsetSamples * 1000 / sr
        val lines = ArrayList<TranscriptLine>()
        var dropped = 0
        for (seg in segs) {
            val text = Repetition.collapse(seg.text)
            val key = Repetition.normalize(text)
            when {
                Hallucination.isLikely(text, seg.noSpeechProb, seg.avgProb) -> dropped++
                // 최근 5문장과 같은 문장: 앞 조각의 문장을 프롬프트로 이어받아 베끼는 반복 (환각 연쇄)
                key.length >= 2 && key in recent -> dropped++
                else -> {
                    lines += TranscriptLine(offsetMs + seg.startMs, offsetMs + seg.endMs, text, lang = segLang)
                    if (key.length >= 2) { recent.addLast(key); while (recent.size > 5) recent.removeFirst() }
                }
            }
        }
        if (dropped > 0) Log.i("Transcriber", "dropped $dropped hallucinated/repeated segment(s)")
        // 다음 조각의 문맥(initial prompt)은 검증된 문장만 사용. 이번 조각이 전부 버려졌으면 문맥 초기화
        lastText = if (lines.isNotEmpty()) lines.joinToString(" ") { it.text } else ""
        if (lines.isNotEmpty()) onLines(lines)
        Log.i("Transcriber", "chunk ${audioMs}ms -> ${procMs}ms (RTF %.2f)".format(procMs.toDouble() / audioMs))
        onChunkDone(audioMs, procMs)
    }
}

/**
 * Whisper가 무음·잡음에서 지어내는 대표 문구 (방송·유튜브 자막 학습의 부작용).
 *
 * - 강한 패턴: 회의에서 나올 일이 거의 없는 방송 멘트. 짧은 문장에 들어 있으면 항상 제거.
 * - 약한 패턴: 실제 회의에서도 나오는 표현("알림 설정", 방송사 이름 등). 문장이 사실상 그 문구뿐이거나
 *   모델의 신뢰도가 낮을 때(무음 확률이 높거나 글자 확률이 낮음)만 제거해 실제 발언을 지우지 않는다.
 */
object Hallucination {
    private val strong = listOf(
        "시청해 ?주셔서 감사",
        "시청해 ?주신 여러분",
        "구독.{0,6}좋아요",
        "좋아요.{0,6}구독",
        "구독.{0,12}알림 ?설정",
        "다음 (영상|시간|주)에서 (만나|뵙)",
        "(지금까지|이상) .{0,12}(뉴스|스토리|기자)",
        "뉴스 스토리",
        "(MBC|KBS|SBS|YTN|JTBC) ?뉴스",
        "자막 (제공|제작)|한글자막|자막 by",
        "^[(\\[].*[)\\]]$",                       // 문장 전체가 괄호: (박수), [음악], (두 번째 요리는)
        "^(음악|박수|웃음)$",
        "이 영상(입니다|이었습니다|을 (보|시청))",
        "영상(을|이) (도움|재미)",
    ).map { Regex(it) }

    private val weak = listOf(
        "시청자 여러분",
        "알림 ?설정",
        "MBC|KBS|SBS|YTN|JTBC",
        "오늘의 주인공",
    ).map { Regex(it) }

    /** whisper.cpp의 무음 판정 기준과 같은 값 */
    private const val NO_SPEECH_THOLD = 0.6f
    /** 글자 토큰 평균 확률이 이보다 낮으면 확신 없이 쓴 문장으로 봄 (실측으로 조정 필요) */
    private const val LOW_PROB_THOLD = 0.5f

    fun isLikely(text: String, noSpeechProb: Float = 0f, avgProb: Float = 1f): Boolean {
        val t = text.trim().trimEnd('.', '!', '?', '~', ' ')
        if (t.isEmpty()) return true
        if (t.length > 40) return false
        if (strong.any { it.containsMatchIn(t) }) return true
        val m = weak.firstNotNullOfOrNull { it.find(t) } ?: return false
        // 문구를 뺀 나머지가 거의 없으면 그 문구만 말한 것 → 환각으로 봄
        val rest = Repetition.normalize(t.removeRange(m.range)).length
        return rest <= 2 || noSpeechProb > NO_SPEECH_THOLD || avgProb < LOW_PROB_THOLD
    }
}

/** 작은 목소리 보정용 음량 계산 */
object Loudness {
    /**
     * 블록의 말소리 수준(100ms 구간 RMS의 상위 5% 지점)을 약 -20dBFS로 올리는 증폭 배수 (1~10).
     * 가장 큰 구간이 -6dBFS를 넘지 않고, 배경 소음이 -46dBFS를 넘지 않도록 제한한다.
     */
    fun blockGain(pcm: FloatArray, sr: Int): Float {
        val frame = sr / 10
        if (pcm.size < frame) return 1f
        val rms = DoubleArray(pcm.size / frame) { f ->
            var e = 0.0
            val s = f * frame
            for (j in s until s + frame) { val v = pcm[j].toDouble(); e += v * v }
            sqrt(e / frame)
        }
        rms.sort()
        val speech = rms[(rms.size * 95 / 100).coerceAtMost(rms.size - 1)]
        val peak = rms[rms.size - 1]
        val floor = rms[rms.size / 10]
        if (speech < 0.004) return 1f   // 말소리가 없으면(-48dBFS 미만) 잡음만 키우지 않음
        val noiseCap = if (floor > 1e-6) 0.005 / floor else 10.0
        return minOf(0.1 / speech, 0.5 / peak, 10.0, noiseCap).coerceAtLeast(1.0).toFloat()
    }
}

/** Whisper 반복 출력 정리 */
object Repetition {
    /** 비교용: 공백·문장부호 제거 */
    fun normalize(t: String) = t.replace(Regex("[^0-9A-Za-z가-힣]"), "")

    private val shortToken = Regex("""(\S{1,10}?[.,!?…]*)(?:\s+\1){2,}""")          // 참. 참. 참. → 참.
    private val longPhrase = Regex("""(.{6,60}?)(?:\s*\1){1,}""")                    // 같은 구절 2회 이상 연속 → 1회

    /** 문장 안의 연속 반복을 한 번으로 줄임 */
    fun collapse(t: String): String {
        var r = shortToken.replace(t.trim()) { it.groupValues[1] }
        r = longPhrase.replace(r) { m -> if (m.groupValues[1].isNotBlank()) m.groupValues[1] else m.value }
        return r.trim()
    }
}
