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

/** TIME/DIV 단계 (가로 10칸). 화면 전체 폭 = 10 × ms/DIV */
object TimeDiv {
    val msPerDiv = intArrayOf(2, 5, 10, 25)
    const val DEFAULT = 1
    fun label(i: Int) = "${msPerDiv[i.coerceIn(0, msPerDiv.lastIndex)]} ms/DIV"
}

class ScopeAnalyzer {
    private val historyLen = 100       // 10초
    private val history = FloatArray(historyLen) { VoiceThreshold.FLOOR_DB }
    private var head = 0
    private val wavePoints = 400

    // 최근 샘플 링버퍼: 가장 넓은 창(25ms/DIV = 250ms) + 트리거 탐색 여유
    private val ringLen = 16_000 * 400 / 1000
    private val ring = ShortArray(ringLen)
    private var ringHead = 0
    private var ringFilled = 0

    /** UI에서 바꾸는 TIME/DIV 단계 */
    @Volatile var timeDivIndex = TimeDiv.DEFAULT

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

        for (v in frame) { ring[ringHead] = v; ringHead = (ringHead + 1) % ringLen }
        ringFilled = minOf(ringLen, ringFilled + frame.size)

        val window = 16_000 * TimeDiv.msPerDiv[timeDivIndex.coerceIn(0, TimeDiv.msPerDiv.lastIndex)] * 10 / 1000
        val avail = ringFilled
        // i번째(오래된 것부터) 샘플
        fun at(i: Int): Int = ring[((ringHead - avail + i) % ringLen + ringLen) % ringLen].toInt()

        // 트리거: 최신 창이 끝나기 직전까지 범위에서 마지막 상승 영점을 찾아 화면이 흔들리지 않게 정렬
        val latestStart = (avail - window).coerceAtLeast(0)
        val searchFrom = (latestStart - 1600).coerceAtLeast(1)
        var start = latestStart
        for (i in latestStart downTo searchFrom) {
            if (at(i - 1) < -64 && at(i) >= 0) { start = i; break }
        }
        val n = minOf(window, avail - start)
        // 창이 넓으면 칸마다 절대값이 가장 큰 샘플을 골라 포락선을 보존 (단순 추출은 파형이 뭉개짐)
        val wave = FloatArray(wavePoints) { p ->
            val from = start + (p.toLong() * n / wavePoints).toInt()
            val to = maxOf(from + 1, start + ((p + 1).toLong() * n / wavePoints).toInt())
            var best = 0
            for (i in from until minOf(to, avail)) { val v = at(i); if (kotlin.math.abs(v) > kotlin.math.abs(best)) best = v }
            best / 32768f
        }
        return ScopeData(wave, ordered, db, hold)
    }
}
