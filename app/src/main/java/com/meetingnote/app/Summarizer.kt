package com.meetingnote.app

import com.meetingnote.llama.GenerationStats
import com.meetingnote.llama.LlamaModel

/**
 * 온디바이스 LLM 회의록 생성.
 *
 * - 녹취록이 짧으면 한 번에 회의록 생성
 * - 길면 Map-Reduce: 구간별 요약 메모 → 메모들을 합쳐 최종 회의록
 *   (n_ctx 4096 안에서 동작하도록 실제 토큰 수로 분할)
 * - 지시문은 user 턴에 포함 (소형 모델에서 system 프롬프트보다 형식 준수가 좋았음)
 */
class Summarizer(
    private val llm: LlamaModel,
    private val spec: ModelSpec,
    private val nCtx: Int = 4096,
    vocabulary: String = "",
    /** 회의록 언어: "ko" 한국어, "en" 영어 (녹취록이 한·영 혼합이어도 이 언어로 작성) */
    private val lang: String = "ko",
    /** 진행률·남은 시간 추정 (화면 표시용) */
    val tracker: SummaryTracker = SummaryTracker(30.0, 6.0),
) {
    private val en get() = lang == "en"
    data class Progress(val stage: String, val step: Int, val total: Int)

    private val terms = vocabulary.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    /** 음성인식 오류 교정용 용어 안내 */
    private val termsNote get() = when {
        terms.isEmpty() -> ""
        en -> "Reference terms (correct spelling): ${terms.joinToString(", ")}\n" +
            "If the transcript contains words that sound like these terms but were misrecognized, use the spelling above.\n"
        else -> "참고 용어(정확한 표기): ${terms.joinToString(", ")}\n" +
            "녹취록에서 이 용어와 발음이 비슷하게 잘못 인식된 단어는 위 표기로 고쳐 쓰세요.\n"
    }

    private val minutesFormatEn = """
        ## Summary
        (Up to 3 sentences in your own words. Do not copy transcript sentences.)

        ## Key Discussion Points
        - One bullet per topic

        ## Decisions
        - Only what participants explicitly agreed on (write 'None' if there are none)

        ## Action Items (Owner / Due)
        - Owner: task (due) — only tasks someone committed to (write 'None' if there are none)

        ## Next Meeting
        - Date/time ('Not mentioned' if not discussed)
    """.trimIndent()

    private val minutesFormat = """
        ## 회의 요약
        (자신의 말로 3문장 이내. 녹취록 문장을 그대로 옮기지 말 것)

        ## 주요 논의 사항
        - 항목별로 정리

        ## 결정 사항
        - 참석자가 명시적으로 합의·확정한 내용만 (없으면 '없음')

        ## 할 일 (담당자 / 기한)
        - 담당자: 할 일 (기한) — 누군가 하기로 한 일만 (없으면 '없음')

        ## 다음 회의
        - 일시 (언급이 없으면 '언급 없음')
    """.trimIndent()

    private fun finalPrompt(body: String, isNotes: Boolean): String {
        if (en) return buildString {
            val what = if (isNotes) "section notes of a meeting" else "meeting transcript"
            append("You are an expert minute-taker. Read the $what below and write meeting minutes in English using the format below.\n")
            append("The transcript may mix Korean and English; write everything in English.\n")
            append("Never invent anything not in the transcript; keep numbers, dates and owners exactly as stated.\n")
            append("The transcript comes from speech recognition, so some words may be wrong — interpret them from context.\n")
            append("Ignore sentences unrelated to the meeting flow (broadcast phrases, out-of-context lines) as recognition errors.\n")
            append("Do not turn simple questions, confirmations or remarks into decisions or action items.\n")
            append("If there is almost no meeting content (mic test, brief chat), say so in one sentence in the Summary and write 'None' for the other sections.\n")
            append(termsNote)
            append("\n").append(minutesFormatEn).append("\n\n")
            append("Here is the $what.\n")
            append(body)
        }
        val what = if (isNotes) "회의를 구간별로 정리한 메모" else "회의 녹취록"
        // 들여쓰기 공백이 프롬프트에 섞이지 않도록 줄 단위로 조립 (불필요한 토큰 절약)
        return buildString {
            append("당신은 회의록 작성 전문가입니다. 아래 $what 를 읽고 다음 형식의 한국어 회의록을 작성하세요.\n")
            append("녹취록에 없는 내용은 절대 만들지 말고, 숫자·날짜·담당자는 원문 그대로 쓰세요.\n")
            append("녹취록은 음성인식 결과라 일부 단어가 틀릴 수 있으니 문맥에 맞게 해석하세요.\n")
            append("회의 흐름과 무관한 문장(방송 멘트, 앞뒤 맥락 없이 튀는 문장)은 음성인식 오류로 보고 무시하세요.\n")
            append("단순한 질문·확인·혼잣말을 결정 사항이나 할 일로 바꾸지 마세요.\n")
            append("회의다운 내용이 거의 없으면(마이크 테스트, 짧은 잡담 등) 회의 요약에 그 사실을 한 문장으로 쓰고 나머지 항목은 '없음'으로 쓰세요.\n")
            append(termsNote)
            append("\n").append(minutesFormat).append("\n\n")
            append("다음은 $what 입니다.\n")
            append(body)
        }
    }

    private fun mapPrompt(part: String, idx: Int, total: Int) = if (en) buildString {
        append("Below is part ($idx/$total) of a meeting transcript (may mix Korean and English). In English, list only the key discussion points, ")
        append("decisions, action items (owner/due) and schedule from this part as bullets (-), at most 10 lines. Mention each item once. ")
        append("Do not invent anything; keep numbers and dates as stated. Ignore out-of-context lines (broadcast phrases) as recognition errors.\n")
        append(termsNote)
        append("\n").append(part)
    } else buildString {
        append("다음은 회의 녹취록의 일부($idx/$total)입니다. 이 부분에서 나온 핵심 논의, 결정 사항, 할 일(담당자/기한), 일정만 ")
        append("글머리표(-)로 최대 10줄 이내로 간결하게 정리하세요. 같은 내용은 한 번만 쓰세요. ")
        append("녹취록에 없는 내용은 만들지 마세요. 숫자와 날짜는 원문 그대로 쓰세요. ")
        append("맥락과 무관한 문장(방송 멘트 등)은 음성인식 오류이므로 무시하세요.\n")
        append(termsNote)
        append("\n").append(part)
    }

    private val maxOut = 900
    private val singlePassBudget get() = nCtx - maxOut - 400
    private val chunkBudget get() = nCtx - 600 - 300

    fun summarize(
        transcript: List<TranscriptLine>,
        onProgress: (Progress) -> Unit,
        onText: (String) -> Unit,
    ): Pair<String, List<GenerationStats>> {
        val stats = mutableListOf<GenerationStats>()
        val full = transcript.joinToString("\n") { it.text }
        require(full.isNotBlank()) { "녹취록이 비어 있습니다" }

        val allLines = transcript.indices
        if (llm.countTokens(full) <= singlePassBudget) {
            val final = SummaryTracker.Step(SummaryTracker.Kind.FINAL, "회의록 작성", llm.countTokens(finalPrompt(full, false)), 380, allLines)
            tracker.plan(listOf(final, SummaryTracker.Step(SummaryTracker.Kind.TITLE, "제목", 150, 16)))
            onProgress(Progress("회의록 작성", 1, 1))
            val out = run(finalPrompt(full, false), maxOut, stats, onText, final)
            return out to stats
        }

        // Map: 토큰 예산에 맞춰 녹취록 분할 (문장 단위)
        val ranges = splitRanges(transcript.map { it.text }, chunkBudget)
        val chunks = ranges.map { r -> r.joinToString("") { transcript[it].text + "\n" } }
        val readSteps = chunks.mapIndexed { i, part ->
            SummaryTracker.Step(SummaryTracker.Kind.READ, "구간 ${i + 1}", llm.countTokens(mapPrompt(part, i + 1, chunks.size)), 200, ranges[i])
        }
        val final = SummaryTracker.Step(SummaryTracker.Kind.FINAL, "회의록 작성", chunks.size * 220 + 700, 380)
        tracker.plan(readSteps + final + SummaryTracker.Step(SummaryTracker.Kind.TITLE, "제목", 150, 16))
        val notes = chunks.mapIndexed { i, part ->
            onProgress(Progress("구간 요약", i + 1, chunks.size + 1))
            onText("") // 화면 초기화 신호
            run(mapPrompt(part, i + 1, chunks.size), 300, stats, onText, readSteps[i])
        }

        // Reduce: 메모가 길면 한 번 더 압축
        var merged = notes.mapIndexed { i, n -> "[구간 ${i + 1}]\n$n" }.joinToString("\n\n")
        if (llm.countTokens(merged) > singlePassBudget) {
            val groups = splitByTokens(notes, chunkBudget)
            val steps = groups.map { g -> SummaryTracker.Step(SummaryTracker.Kind.COMPRESS, "메모 압축", llm.countTokens(g), 200).also { tracker.insertBeforeFinal(it) } }
            merged = groups.mapIndexed { i, g ->
                onProgress(Progress("메모 압축", i + 1, groups.size))
                onText("")
                run(mapPrompt(g, i + 1, groups.size), 300, stats, onText, steps[i])
            }.joinToString("\n\n")
        }
        onProgress(Progress("회의록 작성", chunks.size + 1, chunks.size + 1))
        onText("")
        val out = run(finalPrompt(merged, true), maxOut, stats, onText, final)
        return out to stats
    }

    fun cancel() = llm.cancel()

    /** 회의록의 '회의 요약' 부분으로 짧은 제목 생성 (프롬프트를 짧게 유지해 수 초 내 완료) */
    fun makeTitle(minutes: String): String? {
        val header = if (en) "## Summary" else "## 회의 요약"
        val summary = minutes.substringAfter(header, minutes).substringBefore("\n## ").trim().take(400)
        if (summary.isBlank()) return null
        val prompt = if (en)
            "Based on this meeting summary, reply with a single short English title (max 8 words). " +
                "No quotes or explanation, only the title.\n\n$summary"
        else "다음 회의 요약을 보고 회의 제목을 20자 이내의 한국어 명사구 하나로만 답하세요. " +
                "따옴표나 설명 없이 제목만 쓰세요.\n\n$summary"
        val t = run(prompt, 32, mutableListOf(), {}, tracker.step(SummaryTracker.Kind.TITLE))
            .lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.trim('"', '\'', '“', '”', '*', '#', ' ')?.removePrefix("제목:")?.removePrefix("Title:")?.trim()
        return t?.takeIf { it.length in 2..60 }
    }

    private fun run(
        user: String, maxTokens: Int, stats: MutableList<GenerationStats>, onText: (String) -> Unit,
        step: SummaryTracker.Step? = null,
    ): String {
        val prompt = llm.formatChat(null, user, spec.assistantPrefix)
        step?.let { tracker.begin(it, llm.countTokens(prompt)) }
        val sb = StringBuilder()
        val st = llm.generate(
            prompt, maxTokens = maxTokens, temperature = 0.2f, repeatPenalty = 1.05f,
            onPrompt = if (step != null) { d, t -> tracker.onPrompt(d, t) } else null,
        ) {
            if (step != null) tracker.onToken()
            sb.append(it)
            onText(stripThink(sb.toString()))
            true
        }
        if (step != null) tracker.end(st)
        stats += st
        return stripThink(sb.toString()).trim()
    }

    /**
     * 균등 분할: 예산을 넘지 않는 최소 조각 수를 구한 뒤 조각 크기를 고르게 맞춘다.
     * (예: 3600토큰 → 3200+400 대신 1800+1800. 긴 프롬프트일수록 토큰당 처리 속도가 떨어지므로 유리)
     */
    /** splitByTokens와 같은 분할을 줄 번호 범위로 (진행 화면에서 지금 읽는 구간 표시용) */
    private fun splitRanges(lines: List<String>, budget: Int): List<IntRange> {
        val counts = lines.map { llm.countTokens(it) + 1 }
        val total = counts.sum()
        val n = (total + budget - 1) / budget
        val target = if (n <= 1) budget else (total + n - 1) / n
        val out = mutableListOf<IntRange>()
        var start = 0; var curTok = 0
        for (i in lines.indices) {
            if (curTok + counts[i] > target && i > start && out.size < n - 1) {
                out += start until i; start = i; curTok = 0
            }
            curTok += counts[i]
        }
        if (start < lines.size) out += start until lines.size
        return out
    }

    private fun splitByTokens(lines: List<String>, budget: Int): List<String> {
        val counts = lines.map { llm.countTokens(it) + 1 }
        val total = counts.sum()
        val n = (total + budget - 1) / budget
        val target = if (n <= 1) budget else (total + n - 1) / n
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var curTok = 0
        for ((line, t) in lines.zip(counts)) {
            if (curTok + t > target && cur.isNotEmpty() && out.size < n - 1) {
                out += cur.toString(); cur.clear(); curTok = 0
            }
            cur.append(line).append('\n'); curTok += t
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    companion object {
        private val thinkRe = Regex("(?s)<think>.*?(</think>|$)")
        fun stripThink(s: String) = s.replace(thinkRe, "").trimStart()
    }
}
