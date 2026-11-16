package com.meetingnote.whisper

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
    ): Transcription {
        check(handle != 0L) { "closed" }
        val bytes = WhisperLib.nativeTranscribe(
            handle, pcm, threads, language, beamSize, initialPrompt, vadModelPath, audioCtx, koEnOnly
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

internal object WhisperLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(handle: Long)
    external fun nativeTranscribe(
        handle: Long, pcm: FloatArray, threads: Int, lang: String, beamSize: Int,
        initialPrompt: String?, vadModelPath: String?, audioCtx: Int, koEnOnly: Boolean,
    ): ByteArray?
    external fun nativeSystemInfo(): String
}
