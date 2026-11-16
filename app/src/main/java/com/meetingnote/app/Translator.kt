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
        val toKorean = line.lang == "en"
        val target = if (toKorean) "Korean" else "English"
        val system = "You are a professional Korean-English meeting interpreter. " +
            "Translate the utterance into natural, concise $target as spoken in a business meeting. " +
            "Keep names, numbers, dates and technical terms accurate. " +
            "Output only the translation — no quotes, notes, or explanations."
        val user = buildString {
            if (context.isNotEmpty()) {
                append("Previous lines (context only, do not translate):\n")
                context.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append("Utterance:\n").append(line.text)
        }
        val prompt = llm.formatChat(system, user, spec.assistantPrefix)
        val maxTokens = (llm.countTokens(line.text) * 3 + 24).coerceIn(32, 256)
        val sb = StringBuilder()
        llm.generate(prompt, maxTokens = maxTokens, temperature = 0.2f, repeatPenalty = 1.05f) {
            sb.append(it); true
        }
        context.addLast(line.text); while (context.size > 2) context.removeFirst()
        return Summarizer.stripThink(sb.toString())
            .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            .removePrefix("Translation:").removePrefix("번역:").trim()
            .trim('"', '“', '”', '\'')
    }
}
