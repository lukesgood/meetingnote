package com.meetingnote.app

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** 녹음 화면 오실로스코프용 데이터 (100ms마다 갱신) */
class ScopeData(
    /** 최신 파형 (-1..1), 상승 영점에서 트리거해 화면이 흔들리지 않게 정렬 */
    val wave: FloatArray,
    /** 최근 10초의 100ms 단위 음량 (dBFS) */
    val history: FloatArray,
    /** 현재 100ms 음량 (dBFS) */
    val db: Float,
    /** 최근 0.5초 최대 음량. 단어 사이 짧은 쉼에 색이 깜빡이지 않도록 판정에 사용 */
    val holdDb: Float,
)

/** 음량 구간. 인식 파이프라인의 기준값과 맞춘다. */
enum class VoiceLevel { GOOD, LOW, UNCLEAR }

object VoiceThreshold {
    /**
     * StreamingTranscriber는 가장 큰 100ms 구간 RMS가 0.004(-48dBFS) 미만이면 무음으로 버린다.
     * 그 아래는 '인식 어려움', -38dB까지는 자동 증폭으로 인식되지만 작은 소리.
     */
    const val UNCLEAR_DB = -48f
    const val GOOD_DB = -38f
    const val FLOOR_DB = -80f

    fun classify(db: Float) = when {
        db >= GOOD_DB -> VoiceLevel.GOOD
        db >= UNCLEAR_DB -> VoiceLevel.LOW
        else -> VoiceLevel.UNCLEAR
    }
}

class ScopeAnalyzer {
    private val historyLen = 100       // 10초
    private val history = FloatArray(historyLen) { VoiceThreshold.FLOOR_DB }
    private var head = 0
    private val waveSamples = 800      // 50ms 표시 창
    private val wavePoints = 400

    fun push(frame: ShortArray): ScopeData {
        var sum = 0.0
        for (s in frame) sum += s * s.toDouble()
        val rms = sqrt(sum / max(1, frame.size)) / 32768.0
        val db = if (rms > 1e-8) (20 * log10(rms)).toFloat().coerceAtLeast(VoiceThreshold.FLOOR_DB) else VoiceThreshold.FLOOR_DB

        history[head] = db
        head = (head + 1) % historyLen
        val ordered = FloatArray(historyLen) { history[(head + it) % historyLen] }
        var hold = VoiceThreshold.FLOOR_DB
        for (i in historyLen - 5 until historyLen) hold = max(hold, ordered[i])

        // 트리거: 앞쪽 절반에서 첫 상승 영점(약한 히스테리시스) 찾기
        var start = 0
        val searchEnd = (frame.size - waveSamples).coerceAtLeast(0)
        for (i in 1 until searchEnd) {
            if (frame[i - 1] < -64 && frame[i] >= 0) { start = i; break }
        }
        val n = minOf(waveSamples, frame.size - start)
        val wave = FloatArray(wavePoints) { p ->
            val idx = start + (p.toLong() * n / wavePoints).toInt()
            if (idx < frame.size) frame[idx] / 32768f else 0f
        }
        return ScopeData(wave, ordered, db, hold)
    }
}
