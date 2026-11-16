package com.meetingnote.whisper

data class Segment(val startMs: Long, val endMs: Long, val text: String)

/** whisper.cpp 래퍼. 하나의 인스턴스는 한 스레드에서만 사용해야 한다. */
class WhisperContext private constructor(private var handle: Long) : AutoCloseable {

    fun transcribe(
        pcm: FloatArray,
        threads: Int = 4,
        language: String = "ko",
        beamSize: Int = 5,
        initialPrompt: String? = null,
        vadModelPath: String? = null,
    ): List<Segment> {
        check(handle != 0L) { "closed" }
        val bytes = WhisperLib.nativeTranscribe(
            handle, pcm, threads, language, beamSize, initialPrompt, vadModelPath
        ) ?: error("transcription failed")
        return String(bytes, Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) null
                else Segment(parts[0].toLong(), parts[1].toLong(), parts[2].trim())
            }
            .filter { it.text.isNotEmpty() }
            .toList()
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
        initialPrompt: String?, vadModelPath: String?,
    ): ByteArray?
    external fun nativeSystemInfo(): String
}
