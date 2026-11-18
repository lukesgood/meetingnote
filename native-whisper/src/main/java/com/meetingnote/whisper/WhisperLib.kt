package com.meetingnote.whisper

fun interface ProgressCallback {
    fun onProgress(percent: Int)
}

data class Segment(val startMs: Long, val endMs: Long, val text: String)

/** 인식 결과: 감지된(또는 지정한) 언어 코드와 문장들 */
data class Transcription(val language: String, val segments: List<Segment>)

/** whisper.cpp 래퍼. 하나의 인스턴스는 한 스레드에서만 사용해야 한다. */
class WhisperContext private constructor(private var handle: Long) : AutoCloseable {

    fun transcribe(
        pcm: FloatArray,
        threads: Int = 4,
        language: String = "ko",
        beamSize: Int = 5,
        initialPrompt: String? = null,
        vadModelPath: String? = null,
        /** 인코더 창 크기 (0 = 기본). 짧은 조각에서 연산을 줄임 */
        audioCtx: Int = 0,
        /** language가 빈 문자열(자동 감지)일 때 한국어/영어로만 제한 */
        koEnOnly: Boolean = false,
        /** 30초 창 사이에 앞 인식 결과를 문맥으로 넘기지 않음 (긴 오디오 정밀 인식용) */
        noContext: Boolean = false,
        /** 진행률 0~100 */
        onProgress: ProgressCallback? = null,
    ): Transcription {
        check(handle != 0L) { "closed" }
        val bytes = WhisperLib.nativeTranscribe(
            handle, pcm, threads, language, beamSize, initialPrompt, vadModelPath, audioCtx, koEnOnly, noContext, onProgress
        ) ?: error("transcription failed")
        val lines = String(bytes, Charsets.UTF_8).lineSequence().toList()
        val lang = lines.firstOrNull { it.startsWith("#lang\t") }?.substringAfter('\t')?.trim().orEmpty()
        val segs = lines.asSequence()
            .filter { it.isNotBlank() && !it.startsWith("#lang") }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) null
                else Segment(parts[0].toLong(), parts[1].toLong(), parts[2].trim())
            }
            .filter { it.text.isNotEmpty() }
            .toList()
        return Transcription(lang.ifEmpty { language }, segs)
    }

    override fun close() {
        if (handle != 0L) {
            WhisperLib.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        fun load(modelPath: String): WhisperContext {
            val h = WhisperLib.nativeInit(modelPath)
            require(h != 0L) { "Whisper 모델을 불러오지 못했습니다: $modelPath" }
            return WhisperContext(h)
        }
    }
}

/**
 * CPU 세대에 맞는 엔진 선택.
 * - "dp": ARMv8.2 dotprod+fp16 가속판 (whisper_jni) — Cortex-A55/A75 이후 (Snapdragon 845+, Helio G70+/P90, Dimensity 전 기종 등)
 * - "base": ARMv8.0 기본판 (whisper_jni_base) — Cortex-A53/A73 (Helio P22/P35/P60/P70/G35/G37, Snapdragon 66x/68x, Exynos 9611 등)
 * 가속판을 지원하지 않는 CPU에서 가속판을 쓰면 첫 연산에서 SIGILL로 앱이 종료되므로 반드시 확인 후 불러온다.
 * 테스트용으로 시스템 속성 meetingnote.cpu=base|dp 로 강제할 수 있다 (라이브러리를 불러오기 전에 설정).
 */
object WhisperNative {
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
            System.loadLibrary(if (want == "dp") "whisper_jni" else "whisper_jni_base"); want
        } catch (e: UnsatisfiedLinkError) {
            if (want == "base") throw e
            System.loadLibrary("whisper_jni_base"); "base"
        }
    }
}

internal object WhisperLib {
    init {
        WhisperNative.load()
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(handle: Long)
    external fun nativeTranscribe(
        handle: Long, pcm: FloatArray, threads: Int, lang: String, beamSize: Int,
        initialPrompt: String?, vadModelPath: String?, audioCtx: Int, koEnOnly: Boolean,
        noContext: Boolean, progress: ProgressCallback?,
    ): ByteArray?
    external fun nativeSystemInfo(): String
}
