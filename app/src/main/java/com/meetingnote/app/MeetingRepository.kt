package com.meetingnote.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 녹취록 한 문장.
 * @param lang 인식 언어 ("ko"/"en", 모르면 null)
 * @param translation 통역 결과 (통역 모드에서만, 번역 전이면 null)
 */
data class TranscriptLine(
    val startMs: Long, val endMs: Long, val text: String,
    val lang: String? = null, val translation: String? = null,
)

data class Meeting(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val hasMinutes: Boolean,
    val modelUsed: String? = null,
    /** 녹취록을 만든 음성인식 모델 */
    val sttModel: String? = null,
    val stats: String? = null,
    /** 사용자가 직접 제목을 수정했는지 (자동 제목 생성 여부 판단) */
    val titleEdited: Boolean = false,
    /** 녹음 진행 중 표시. 앱 시작 시 true로 남아 있으면 비정상 종료로 판단 */
    val recording: Boolean = false,
    val recovered: Boolean = false,
)

/** 회의별 디렉터리: meta.json, audio.wav, transcript.json, minutes.md */
class MeetingRepository(context: Context) {
    val root: File = File(context.getExternalFilesDir(null), "meetings").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun audioFile(id: String) = File(dir(id), "audio.wav")
    fun minutesFile(id: String) = File(dir(id), "minutes.md")
    private fun metaFile(id: String) = File(dir(id), "meta.json")
    private fun transcriptFile(id: String) = File(dir(id), "transcript.json")

    fun create(): Meeting {
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        dir(id).mkdirs()
        val title = SimpleDateFormat("M월 d일 (E) HH:mm 회의", Locale.KOREAN).format(Date(now))
        val m = Meeting(id, title, now, 0, false, recording = true)
        save(m)
        return m
    }

    fun save(m: Meeting) {
        val o = JSONObject()
            .put("id", m.id).put("title", m.title).put("createdAt", m.createdAt)
            .put("durationMs", m.durationMs).put("hasMinutes", m.hasMinutes)
            .put("modelUsed", m.modelUsed ?: JSONObject.NULL).put("sttModel", m.sttModel ?: JSONObject.NULL).put("stats", m.stats ?: JSONObject.NULL)
            .put("titleEdited", m.titleEdited).put("recording", m.recording).put("recovered", m.recovered)
        metaFile(m.id).writeText(o.toString(2))
    }

    fun get(id: String): Meeting? {
        val f = metaFile(id)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        return Meeting(
            id = o.getString("id"),
            title = o.getString("title"),
            createdAt = o.getLong("createdAt"),
            durationMs = o.optLong("durationMs"),
            hasMinutes = minutesFile(id).exists(),
            modelUsed = o.optString("modelUsed").takeIf { it.isNotEmpty() && it != "null" },
            sttModel = o.optString("sttModel").takeIf { it.isNotEmpty() && it != "null" },
            stats = o.optString("stats").takeIf { it.isNotEmpty() && it != "null" },
            titleEdited = o.optBoolean("titleEdited"),
            recording = o.optBoolean("recording"),
            recovered = o.optBoolean("recovered"),
        )
    }

    fun list(): List<Meeting> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { get(it.name) }
            ?.sortedByDescending { it.createdAt } ?: emptyList()

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun saveTranscript(id: String, lines: List<TranscriptLine>) {
        val arr = JSONArray()
        lines.forEach {
            val o = JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)
            it.lang?.let { l -> o.put("l", l) }
            it.translation?.let { tr -> o.put("tr", tr) }
            arr.put(o)
        }
        transcriptFile(id).writeText(arr.toString())
    }

    fun loadTranscript(id: String): List<TranscriptLine> {
        val f = transcriptFile(id)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TranscriptLine(
                o.getLong("s"), o.getLong("e"), o.getString("t"),
                lang = o.optString("l").takeIf { it.isNotEmpty() },
                translation = o.optString("tr").takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * 녹음 중 앱이 종료된 회의 복구: WAV 헤더(녹음 종료 시 기록)를 실제 파일 크기로 다시 쓰고
     * 30초마다 저장된 부분 녹취록은 그대로 둔다.
     */
    fun recoverUnfinished() {
        list().filter { it.recording }.forEach { m ->
            val f = audioFile(m.id)
            val samples = if (f.exists()) (f.length() - 44).coerceAtLeast(0) / 2 else 0
            if (samples > 0) {
                java.io.RandomAccessFile(f, "rw").use { raf ->
                    val dataLen = samples * 2
                    val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    h.put("RIFF".toByteArray()).putInt(minOf(36 + dataLen, 0xFFFFFFFFL).toInt()).put("WAVE".toByteArray())
                    h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                        .putInt(AudioRecorder.SAMPLE_RATE).putInt(AudioRecorder.SAMPLE_RATE * 2).putShort(2).putShort(16)
                    h.put("data".toByteArray()).putInt(minOf(dataLen, 0xFFFFFFFFL).toInt())
                    raf.seek(0); raf.write(h.array())
                }
                save(m.copy(recording = false, recovered = true, durationMs = samples * 1000 / AudioRecorder.SAMPLE_RATE))
            } else {
                delete(m.id)
            }
        }
    }

    fun loadMinutes(id: String): String? = minutesFile(id).takeIf { it.exists() }?.readText()
    fun saveMinutes(id: String, text: String) = minutesFile(id).writeText(text)
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%02d:%02d".format(s / 60, s % 60)
}
