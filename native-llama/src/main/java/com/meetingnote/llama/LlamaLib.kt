package com.meetingnote.llama

data class GenerationStats(
    /** 실제로 계산한 프롬프트 토큰 수 (재사용분 제외) */
    val promptTokens: Int, val promptMs: Long, val genTokens: Int, val genMs: Long,
    /** KV 캐시에서 재사용한 앞부분 토큰 수 */
    val reusedTokens: Int = 0,
) {
    val promptTps get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
    val genTps get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
}

fun interface TokenCallback {
    /** false 반환 시 생성 중단 */
    fun onToken(bytes: ByteArray): Boolean
}

/** llama.cpp 래퍼. 하나의 인스턴스는 한 스레드에서만 사용해야 한다(cancel 제외). */
class LlamaModel private constructor(private var handle: Long) : AutoCloseable {

    fun countTokens(text: String): Int = LlamaLib.nativeCountTokens(handle, text)

    fun formatChat(system: String?, user: String, assistantPrefix: String = ""): String =
        String(LlamaLib.nativeFormatChat(handle, system ?: "", user, assistantPrefix), Charsets.UTF_8)

    /** 여러 턴 대화 (role: system/user/assistant) */
    fun formatMessages(messages: List<Pair<String, String>>, assistantPrefix: String = ""): String =
        String(LlamaLib.nativeFormatMessages(handle, messages.map { it.first }.toTypedArray(),
            messages.map { it.second }.toTypedArray(), assistantPrefix), Charsets.UTF_8)

    /** 앞 호출과 겹치는 프롬프트 앞부분은 KV 캐시를 재사용한다(자동). */
    fun generate(
        prompt: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.2f,
        topP: Float = 0.9f,
        repeatPenalty: Float = 1.05f,
        onText: (String) -> Boolean,
    ): GenerationStats {
        val r = LlamaLib.nativeGenerate(handle, prompt, maxTokens, temperature, topP, repeatPenalty) {
            onText(String(it, Charsets.UTF_8))
        }
        if (r.startsWith("error:")) throw IllegalStateException(r.removePrefix("error:"))
        val p = r.split(',').map { it.toLong() }
        return GenerationStats(p[0].toInt(), p[1], p[2].toInt(), p[3], p.getOrNull(4)?.toInt() ?: 0)
    }

    fun cancel() {
        if (handle != 0L) LlamaLib.nativeCancel(handle)
    }

    override fun close() {
        if (handle != 0L) {
            LlamaLib.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        fun load(path: String, nCtx: Int = 4096, threads: Int = 4): LlamaModel {
            val h = LlamaLib.nativeLoad(path, nCtx, threads)
            require(h != 0L) { "LLM 모델을 불러오지 못했습니다: $path" }
            return LlamaModel(h)
        }
    }
}

internal object LlamaLib {
    init {
        System.loadLibrary("llama_jni")
    }

    external fun nativeLoad(path: String, nCtx: Int, threads: Int): Long
    external fun nativeFree(handle: Long)
    external fun nativeCountTokens(handle: Long, text: String): Int
    external fun nativeFormatChat(handle: Long, system: String, user: String, assistantPrefix: String): ByteArray
    external fun nativeFormatMessages(handle: Long, roles: Array<String>, contents: Array<String>, assistantPrefix: String): ByteArray
    external fun nativeCancel(handle: Long)
    external fun nativeGenerate(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float,
        repeatPenalty: Float, callback: TokenCallback,
    ): String
    external fun nativeSystemInfo(): String
}
