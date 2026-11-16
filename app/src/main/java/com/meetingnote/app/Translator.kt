package com.meetingnote.app

import android.util.Log
import com.meetingnote.llama.LlamaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 한국어 ↔ 영어 실시간 통역: 인식된 문장을 기기 안의 LLM으로 번역.
 * 음성인식과 같은 추론 스레드에서 번갈아 실행되며(빅코어 경합 방지), 문장 단위로 결과를 돌려준다.
 */
class Translator(
    private val llm: LlamaModel,
    private val spec: ModelSpec,
    scope: CoroutineScope,
    private val onTranslated: (TranscriptLine) -> Unit,
) {
    private val queue = Channel<TranscriptLine>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    /** 번역 대기 문장 수 (화면 표시용) */
    val pendingCount get() = pending.get()
    /** 앞 문장 문맥 (대명사·생략 해석용, 번역 대상 아님) */
    private val context = ArrayDeque<String>()
    private var reusedTotal = 0L
    private var computedTotal = 0L

    private val worker = scope.launch {
        for (line in queue) {
            try {
                val tr = translate(line)
                if (tr.isNotBlank()) onTranslated(line.copy(translation = tr))
            } catch (e: Exception) {
                Log.e("Translator", "translate failed", e)
            } finally {
                pending.decrementAndGet()
            }
            kotlinx.coroutines.yield()   // 음성인식 조각이 끼어들 수 있게
        }
    }

    fun submit(lines: List<TranscriptLine>) {
        lines.forEach { pending.incrementAndGet(); queue.trySend(it) }
    }

    /** 남은 번역을 모두 끝낼 때까지 대기 */
    suspend fun finish() {
        queue.close()
        worker.join()
    }

    private fun translate(line: TranscriptLine): String {
        val target = if (line.lang == "en") "Korean" else "English"
        // 시스템 프롬프트는 방향과 무관하게 고정 → 매 요청의 공통 앞부분으로 KV 캐시 재사용.
        // (이전 번역을 대화 턴으로 넣는 방식은 더 빠르지만 앞 문장 내용이 섞여 들어가 요일이 바뀌는 오류가 있었음)
        val user = buildString {
            if (context.isNotEmpty()) {
                append("Previous lines (context only, do not translate):\n")
                context.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append("Translate to ").append(target).append(":\n").append(line.text)
        }
        val prompt = llm.formatChat(SYSTEM, user, spec.assistantPrefix)
        val maxTokens = (llm.countTokens(line.text) * 3 + 24).coerceIn(32, 256)
        val sb = StringBuilder()
        val st = llm.generate(prompt, maxTokens = maxTokens, temperature = 0.2f, repeatPenalty = 1.05f) {
            sb.append(it); true
        }
        context.addLast(line.text); while (context.size > 2) context.removeFirst()
        reusedTotal += st.reusedTokens; computedTotal += st.promptTokens
        Log.i("Translator", "prompt reused=${st.reusedTokens} computed=${st.promptTokens} (${st.promptMs}ms) " +
            "gen=${st.genTokens} (${st.genMs}ms) | total reuse ${reusedTotal * 100 / (reusedTotal + computedTotal).coerceAtLeast(1)}%")
        return Summarizer.stripThink(sb.toString())
            .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            .removePrefix("Translation:").removePrefix("번역:").trim()
            .trim('"', '“', '”', '\'')
    }

    private companion object {
        /** 방향과 무관하게 고정 — 매 요청의 공통 앞부분이 되어 캐시로 재사용된다 */
        const val SYSTEM = "You are a professional Korean-English meeting interpreter. " +
            "Each request gives one utterance from a business meeting and the target language. " +
            "Translate it into natural, concise spoken language. " +
            "Previous lines, if given, are context only — never mix their content into the translation. " +
            "Keep names, numbers, dates, times and technical terms accurate. " +
            "Output only the translation — no quotes, notes, or explanations."
    }
}
