package com.meetingnote.whisper

fun interface ProgressCallback {
    fun onProgress(percent: Int)
}

/**
 * @param noSpeechProb 이 구간이 무음일 확률 (0~1)
 * @param avgProb 글자 토큰의 평균 확률 (0~1, 낮을수록 모델이 확신 없이 쓴 문장)
 */
data class Segment(
    val startMs: Long, val endMs: Long, val text: String,
    val noSpeechProb: Float = 0f, val avgProb: Float = 1f,
)

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
        /**
         * noContext일 때 initialPrompt(용어 사전)를 모든 30초 창에 넣을지. false면 사전은 쓰이지 않는다.
         * 켜면 표기는 좋아질 수 있으나 문장이 길게 뭉칠 수 있음 (실험)
         */
        carryInitialPrompt: Boolean = false,
        /** VAD 말소리 판정 임계값 (음수 = 기본 0.5). 낮출수록 작은 목소리를 덜 버림 */
        vadThreshold: Float = -1f,
        /** VAD가 찾은 말소리 앞뒤로 붙이는 여유 ms (음수 = 기본 30) */
        vadPadMs: Int = -1,
        /** 디코딩 실패 시 온도 폴백 간격 (음수 = 기본 0.2 → 최대 6회, 0.6 → 1회, 0 → 없음) */
        temperatureInc: Float = -1f,
        /** 진행률 0~100 */
        onProgress: ProgressCallback? = null,
    ): Transcription {
        check(handle != 0L) { "closed" }
        val bytes = WhisperLib.nativeTranscribe(
            handle, pcm, threads, language, beamSize, initialPrompt, vadModelPath, audioCtx, koEnOnly, noContext, carryInitialPrompt,
            vadThreshold, vadPadMs, temperatureInc, onProgress,
        ) ?: error("transcription failed")
        val lines = String(bytes, Charsets.UTF_8).lineSequence().toList()
        val lang = lines.firstOrNull { it.startsWith("#lang\t") }?.substringAfter('\t')?.trim().orEmpty()
        val segs = lines.asSequence()
            .filter { it.isNotBlank() && !it.startsWith("#lang") }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 5)
                if (parts.size < 5) null
                else Segment(
                    parts[0].toLong(), parts[1].toLong(), parts[4].trim(),
                    noSpeechProb = parts[2].toFloatOrNull() ?: 0f, avgProb = parts[3].toFloatOrNull() ?: 1f,
                )
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

internal object WhisperLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(handle: Long)
    external fun nativeTranscribe(
        handle: Long, pcm: FloatArray, threads: Int, lang: String, beamSize: Int,
        initialPrompt: String?, vadModelPath: String?, audioCtx: Int, koEnOnly: Boolean,
        noContext: Boolean, carryPrompt: Boolean, vadThreshold: Float, vadPadMs: Int, temperatureInc: Float,
        progress: ProgressCallback?,
    ): ByteArray?
    external fun nativeSystemInfo(): String
}
