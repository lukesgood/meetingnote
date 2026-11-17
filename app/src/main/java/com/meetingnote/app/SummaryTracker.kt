package com.meetingnote.app

import com.meetingnote.llama.GenerationStats

/** 회의록 작성 화면에 보여 줄 진행 상태 */
data class SummaryUi(
    /** 0 모델 불러오기 · 1 녹취 분석 · 2 회의록 작성 · 3 제목 정하기 */
    val phase: Int = 0,
    /** 전체 진행률 0..1 (모델을 불러오는 중이면 null) */
    val fraction: Float? = null,
    /** 남은 시간 추정(초) */
    val etaSec: Int? = null,
    /** 현재 단계 설명 (예: "구간 2/4 읽는 중") */
    val detail: String = "요약 모델 불러오는 중…",
    /** 지금 읽고 있는 녹취록 줄 범위와 그 안의 진행률 (읽기 연출용) */
    val readLines: IntRange? = null,
    val readFraction: Float = 0f,
    /** 글을 쓰는 중(토큰 생성 중)인지 */
    val writing: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
)

/**
 * 회의록 작성 진척 추정.
 * 단계(구간 요약·최종 작성·제목)별 작업량(입력 토큰, 예상 출력 토큰)을 기기 처리 속도로 시간 환산해
 * 전체 진행률과 남은 시간을 계산한다. 단계가 끝날 때마다 실제 속도로 보정하고,
 * 프롬프트 처리 중에는 네이티브 진행 보고 사이를 속도로 보간해 부드럽게 움직인다.
 */
class SummaryTracker(promptTps: Double, genTps: Double) {
    enum class Kind { READ, COMPRESS, FINAL, TITLE }

    class Step(
        val kind: Kind, val label: String,
        @Volatile var promptTokens: Int, @Volatile var expectGen: Int,
        val lines: IntRange? = null,
    ) {
        @Volatile var doneGen = 0
        @Volatile var finished = false
    }

    @Volatile private var pTps = promptTps
    @Volatile private var gTps = genTps
    @Volatile private var steps: List<Step> = emptyList()
    @Volatile private var cur: Step? = null
    @Volatile private var promptDone = 0
    @Volatile private var promptTotal = 0
    @Volatile private var promptAt = 0L
    @Volatile private var genCount = 0
    @Volatile private var inGen = false
    private val startedAt = System.currentTimeMillis()
    private var lastFraction = 0f

    fun plan(steps: List<Step>) { this.steps = steps }

    /** 아직 계획에 없던 단계(메모 압축 등)를 최종 작성 앞에 끼워 넣음 */
    fun insertBeforeFinal(step: Step) {
        val l = steps.toMutableList()
        val i = l.indexOfFirst { it.kind == Kind.FINAL }.let { if (it < 0) l.size else it }
        l.add(i, step); steps = l
    }

    fun step(kind: Kind, nth: Int = 0): Step? = steps.filter { it.kind == kind }.getOrNull(nth)

    fun begin(step: Step, promptTokens: Int) {
        step.promptTokens = promptTokens
        cur = step; promptDone = 0; promptTotal = promptTokens; promptAt = System.currentTimeMillis()
        genCount = 0; inGen = false
    }

    fun onPrompt(done: Int, total: Int) {
        promptDone = done; promptTotal = total; promptAt = System.currentTimeMillis()
        if (done >= total) inGen = true
    }

    fun onToken() {
        inGen = true
        val s = cur ?: return
        genCount++; s.doneGen = genCount
        // 예상보다 길게 쓰면 예상치를 늘려 진행률이 멈춰 보이지 않게
        if (genCount > s.expectGen * 0.9) s.expectGen = (genCount * 1.2).toInt() + 8
    }

    fun end(stats: GenerationStats) {
        val s = cur ?: return
        // 실제 처리 속도로 보정 (충분히 긴 구간만, 급변하지 않게 가중 평균)
        if (stats.promptTokens >= 64 && stats.promptMs > 0) pTps = 0.4 * pTps + 0.6 * stats.promptTps
        if (stats.genTokens >= 16 && stats.genMs > 0) gTps = 0.4 * gTps + 0.6 * stats.genTps
        s.promptTokens = stats.promptTokens
        s.expectGen = stats.genTokens; s.doneGen = stats.genTokens
        s.finished = true
        cur = null
    }

    /** 긴 입력일수록 토큰당 처리가 느려지는 점 반영 */
    private fun promptSec(tokens: Int) = tokens / (pTps / (1 + tokens / 6000.0))

    private fun work(s: Step) = promptSec(s.promptTokens) + s.expectGen / gTps

    fun snapshot(): SummaryUi {
        val now = System.currentTimeMillis()
        val all = steps
        val c = cur
        var done = 0.0
        var total = 0.0
        var readFrac = 0f
        for (s in all) {
            val w = work(s)
            total += w
            when {
                s.finished -> done += w
                s === c -> {
                    // 프롬프트: 마지막 보고 이후 경과 시간만큼 보간 (다음 보고 지점까지만)
                    val est = if (inGen) promptTotal.toDouble() else
                        (promptDone + (now - promptAt) / 1000.0 * pTps / (1 + promptTotal / 6000.0))
                            .coerceAtMost(minOf(promptTotal, promptDone + 256).toDouble())
                    readFrac = if (promptTotal > 0) (est / promptTotal).toFloat().coerceIn(0f, 1f) else 1f
                    val pDone = promptSec(promptTotal) * readFrac
                    done += pDone + s.doneGen / gTps
                }
            }
        }
        // 화면이 뒤로 가지 않게 (예상치 보정으로 잠깐 줄어드는 경우)
        val frac = if (total > 0) (done / total).toFloat().coerceIn(0f, 0.99f) else 0f
        lastFraction = maxOf(lastFraction, frac)
        val eta = (total - done).coerceAtLeast(0.0).toInt()
        val phase = when {
            c == null -> if (all.any { it.kind == Kind.TITLE && it.finished }) 3 else 1
            c.kind == Kind.TITLE -> 3
            c.kind == Kind.FINAL && inGen -> 2
            else -> 1
        }
        val reads = all.count { it.kind == Kind.READ }
        val detail = when (c?.kind) {
            Kind.READ -> {
                val n = all.filter { it.kind == Kind.READ }.indexOf(c) + 1
                if (inGen) "구간 $n/$reads 메모 정리 중" else "구간 $n/$reads 녹취 읽는 중"
            }
            Kind.COMPRESS -> "구간 메모 압축 중"
            Kind.FINAL -> if (inGen) "회의록 쓰는 중" else if (reads > 0) "구간 메모 모아 읽는 중" else "녹취록 읽는 중"
            Kind.TITLE -> "제목 정하는 중"
            null -> "마무리 중"
        }
        return SummaryUi(
            phase = phase, fraction = lastFraction, etaSec = eta, detail = detail,
            readLines = c?.lines, readFraction = readFrac,
            writing = inGen && c != null, startedAt = startedAt,
        )
    }
}
