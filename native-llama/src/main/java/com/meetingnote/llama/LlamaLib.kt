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

/** 프롬프트 처리 진행 (done/total 토큰, 재사용분 제외) */
fun interface PromptProgress {
    fun onPrompt(done: Int, total: Int)
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
        onPrompt: PromptProgress? = null,
        onText: (String) -> Boolean,
    ): GenerationStats {
        val r = LlamaLib.nativeGenerate(handle, prompt, maxTokens, temperature, topP, repeatPenalty, {
            onText(String(it, Charsets.UTF_8))
        }, onPrompt)
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
        /** @param draftPath 추측 생성용 보조 모델(Gemma 4 MTP) 경로. 없으면 일반 생성 */
        fun load(path: String, nCtx: Int = 4096, threads: Int = 4, draftPath: String? = null): LlamaModel {
            val h = LlamaLib.nativeLoad(path, nCtx, threads, draftPath)
            require(h != 0L) { "LLM 모델을 불러오지 못했습니다: $path" }
            return LlamaModel(h)
        }
    }
}

/**
 * CPU 세대에 맞는 엔진 선택.
 * - "dp": ARMv8.2 dotprod+fp16 가속판 (llama_jni) — Cortex-A55/A75 이후 (Snapdragon 845+, Helio G70+/P90, Dimensity 전 기종 등)
 * - "base": ARMv8.0 기본판 (llama_jni_base) — Cortex-A53/A73 (Helio P22/P35/P60/P70/G35/G37, Snapdragon 66x/68x, Exynos 9611 등)
 * 가속판을 지원하지 않는 CPU에서 가속판을 쓰면 첫 연산에서 SIGILL로 앱이 종료되므로 반드시 확인 후 불러온다.
 * 테스트용으로 시스템 속성 meetingnote.cpu=base|dp 로 강제할 수 있다 (라이브러리를 불러오기 전에 설정).
 */
object LlamaNative {
    val cpuFeatures: Set<String> by lazy {
        runCatching {
            java.io.File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet()
        }.getOrNull() ?: emptySet()
    }
    val supportsAccelerated: Boolean get() = "asimddp" in cpuFeatures && "asimdhp" in cpuFeatures

    /** 실제로 불러온 엔진 ("dp" | "base"), 아직 안 불렀으면 null */
    @Volatile var loaded: String? = null
        private set

    @Synchronized internal fun load() {
        if (loaded != null) return
        val forced = System.getProperty("meetingnote.cpu")
        val want = when (forced) {
            "base" -> "base"
            "dp" -> if (supportsAccelerated) "dp" else "base"   // 지원하지 않으면 강제 설정도 무시 (종료 방지)
            else -> if (supportsAccelerated) "dp" else "base"
        }
        loaded = try {
            System.loadLibrary(if (want == "dp") "llama_jni" else "llama_jni_base"); want
        } catch (e: UnsatisfiedLinkError) {
            if (want == "base") throw e
            System.loadLibrary("llama_jni_base"); "base"
        }
    }
}

internal object LlamaLib {
    init {
        LlamaNative.load()
    }

    external fun nativeLoad(path: String, nCtx: Int, threads: Int, draftPath: String?): Long
    external fun nativeFree(handle: Long)
    external fun nativeCountTokens(handle: Long, text: String): Int
    external fun nativeFormatChat(handle: Long, system: String, user: String, assistantPrefix: String): ByteArray
    external fun nativeFormatMessages(handle: Long, roles: Array<String>, contents: Array<String>, assistantPrefix: String): ByteArray
    external fun nativeCancel(handle: Long)
    external fun nativeGenerate(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float,
        repeatPenalty: Float, callback: TokenCallback, progress: PromptProgress?,
    ): String
    external fun nativeSystemInfo(): String
}
