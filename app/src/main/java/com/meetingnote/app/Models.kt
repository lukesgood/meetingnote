package com.meetingnote.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/** 앱에서 사용하는 모델 정의. 벤치마크(Snapdragon 870, 8GB) 결과를 기반으로 선정. */
data class ModelSpec(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val kind: Kind,
    val note: String,
    /** LLM 응답 앞에 강제로 붙일 텍스트 (예: EXAONE의 사고 과정 생략) */
    val assistantPrefix: String = "",
) {
    enum class Kind { STT, VAD, LLM }
}

object ModelCatalog {
    private const val HF = "https://huggingface.co"

    val WHISPER = ModelSpec(
        id = "whisper-small-q8",
        title = "Whisper small (q8_0)",
        fileName = "ggml-small-q8_0.bin",
        url = "$HF/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin",
        sizeBytes = 264_464_607,
        kind = ModelSpec.Kind.STT,
        note = "한국어 음성인식 · 녹음 시간의 약 0.3배",
    )

    val VAD = ModelSpec(
        id = "silero-vad",
        title = "Silero VAD v5.1.2",
        fileName = "ggml-silero-v5.1.2.bin",
        url = "$HF/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
        sizeBytes = 885_098,
        kind = ModelSpec.Kind.VAD,
        note = "무음 구간 제거 · 문장 누락 방지",
    )

    /** 회의 후 정밀 인식용. 기기가 충분히 빠르면 녹음 중 실시간 인식에도 사용 (설정 > 실시간 인식 모델) */
    val WHISPER_TURBO = ModelSpec(
        id = "whisper-large-v3-turbo-q8",
        title = "Whisper large-v3-turbo (q8_0)",
        fileName = "ggml-large-v3-turbo-q8_0.bin",
        url = "$HF/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin",
        sizeBytes = 874_188_075,
        kind = ModelSpec.Kind.STT,
        note = "정밀 인식 · 작은 목소리·숫자·직함 인식이 크게 좋아짐 · 회의 후 다시 인식하면 녹음 시간의 약 0.7배 소요 · 빠른 기기에서는 실시간 인식에도 사용",
    )

    val QWEN3_4B = ModelSpec(
        id = "qwen3-4b",
        title = "Qwen3 4B Instruct 2507 (Q4_0)",
        fileName = "Qwen3-4B-Instruct-2507-Q4_0.gguf",
        url = "$HF/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_0.gguf",
        sizeBytes = 2_375_773_280,
        kind = ModelSpec.Kind.LLM,
        note = "세부 숫자를 더 많이 남김 · Gemma 4보다 느림 · Apache-2.0",
    )

    val EXAONE_1B = ModelSpec(
        id = "exaone4-1.2b",
        title = "EXAONE 4.0 1.2B (Q4_K_M)",
        fileName = "EXAONE-4.0-1.2B-Q4_K_M.gguf",
        url = "$HF/LGAI-EXAONE/EXAONE-4.0-1.2B-GGUF/resolve/main/EXAONE-4.0-1.2B-Q4_K_M.gguf",
        sizeBytes = 812_437_792,
        kind = ModelSpec.Kind.LLM,
        note = "빠름 · 약 21 토큰/초 · 한국어 토큰 효율 우수 · 비상업 라이선스",
        assistantPrefix = "<think>\n\n</think>\n\n",
    )

    /**
     * 기본 회의록·통역 모델.
     *  - 통역 (2026-11-19 PC 비교, 17문장): Qwen3 4B보다 입력 처리 약 5배·생성 약 2배 빠르고 숫자·고유명사가 더 정확.
     *    오인식으로 뜻이 불분명한 문장을 지어낸 경우가 1번 있음.
     *  - 회의록 (2026-11-20, 31분 회의): 구간 메모를 짧게·종합 시 다시 묶어 쓰게 한 프롬프트로 Qwen3와 비슷한 품질,
     *    PC 기준 약 3.9배 빠름 (229초 vs 884초). Qwen3는 세부 숫자를 더 많이 남기고, Gemma는 회의 전체를 고르게 다룸.
     */
    val GEMMA4_E2B = ModelSpec(
        id = "gemma4-e2b",
        title = "Gemma 4 E2B (Q4_0)",
        fileName = "gemma-4-E2B-it-Q4_0.gguf",
        url = "$HF/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf",
        sizeBytes = 2_841_481_184,
        kind = ModelSpec.Kind.LLM,
        note = "기본 · 빠름 · Qwen3보다 회의록 약 4배, 번역 약 2배 빠름 · 통역에도 사용 · Apache-2.0",
    )

    val llms = listOf(GEMMA4_E2B, QWEN3_4B, EXAONE_1B)
    /** 실시간 통역 모델 (권장). 없고 Qwen3만 있으면 Qwen3로 통역 (EXAONE은 영→한 번역이 되지 않음, 2026-11-17 비교) */
    val INTERPRETER = GEMMA4_E2B
    val INTERPRETER_FALLBACK = QWEN3_4B
    val all = listOf(WHISPER, WHISPER_TURBO, VAD, QWEN3_4B, EXAONE_1B, GEMMA4_E2B)
    /** 회의 후 정밀 인식 모델 */
    val REFINER = WHISPER_TURBO

    /** 이전 버전에서 제공했다가 삭제한 모델 파일 (앱 시작 시 정리) */
    val removedFiles = emptyList<String>()
}

class ModelStore(private val context: Context) {
    val dir: File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun file(spec: ModelSpec) = File(dir, spec.fileName)

    fun isReady(spec: ModelSpec): Boolean {
        val f = file(spec)
        return f.exists() && f.length() == spec.sizeBytes
    }

    /** 시스템 DownloadManager로 내려받음 (백그라운드·재시도 지원). */
    fun download(spec: ModelSpec): Long {
        val dm = context.getSystemService(DownloadManager::class.java)
        file(spec).delete()
        val req = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("모델 다운로드: ${spec.title}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models", spec.fileName)
            .setAllowedOverMetered(true)
        return dm.enqueue(req)
    }

    /** 받은 모델 파일 삭제 (진행 중인 다운로드도 취소) */
    fun delete(spec: ModelSpec) {
        cancel(spec)
        file(spec).delete()
    }

    /** 진행 중인 다운로드 취소 */
    fun cancel(spec: ModelSpec) {
        val dm = context.getSystemService(DownloadManager::class.java)
        val ids = ArrayList<Long>()
        dm.query(DownloadManager.Query())?.use { c ->
            val titleIdx = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val idIdx = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            while (c.moveToNext()) {
                val st = c.getInt(statusIdx)
                val active = st != DownloadManager.STATUS_SUCCESSFUL && st != DownloadManager.STATUS_FAILED
                if (active && c.getString(titleIdx)?.endsWith(spec.title) == true) ids += c.getLong(idIdx)
            }
        }
        if (ids.isNotEmpty()) dm.remove(*ids.toLongArray())
    }

    /** 받아 둔 모델이 차지하는 용량 */
    fun usedBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** 진행률(0~1), 진행 중인 다운로드가 없으면 null */
    fun progress(spec: ModelSpec): Float? {
        val dm = context.getSystemService(DownloadManager::class.java)
        val q = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED
        )
        dm.query(q)?.use { c ->
            val titleIdx = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val doneIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            while (c.moveToNext()) {
                if (c.getString(titleIdx)?.endsWith(spec.title) == true) {
                    return c.getLong(doneIdx).toFloat() / spec.sizeBytes
                }
            }
        }
        return null
    }
}
