package com.meetingnote.app

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.core.content.ContextCompat
import com.meetingnote.llama.LlamaModel
import com.meetingnote.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { IDLE, LOADING, RECORDING, FINISHING_STT, TRANSCRIBING_FILE, SUMMARIZING, ERROR }

data class EngineState(
    val phase: Phase = Phase.IDLE,
    val meetingId: String? = null,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val lines: List<TranscriptLine> = emptyList(),
    /** 아직 인식되지 않은 녹음 길이 */
    val sttBacklogMs: Long = 0,
    val lastRtf: Double? = null,
    val summaryText: String = "",
    val summaryStage: String = "",
    val message: String? = null,
) {
    val busy get() = phase != Phase.IDLE && phase != Phase.ERROR
}

/**
 * 녹음 → 실시간 인식 → 회의록 생성 전체 흐름을 관리하는 싱글턴.
 * 무거운 추론은 전용 단일 스레드에서 순차 실행하여 메모리 사용과 발열을 제어한다
 * (Whisper와 LLM을 동시에 메모리에 올리지 않음).
 */
@OptIn(ExperimentalCoroutinesApi::class)
object MeetingEngine {
    private const val TAG = "MeetingEngine"

    private lateinit var app: Context
    lateinit var repo: MeetingRepository; private set
    lateinit var models: ModelStore; private set
    lateinit var settings: Settings; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 녹음 화면 오실로스코프 데이터 (녹음 중에만 갱신) */
    private val _scope = MutableStateFlow<ScopeData?>(null)
    val scopeData: StateFlow<ScopeData?> = _scope
    private var scopeAnalyzer = ScopeAnalyzer()
    /** 추론 전용 스레드 (빅코어 4개를 ggml 스레드풀이 사용) */
    private val inference = Dispatchers.Default.limitedParallelism(1)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    private var recorder: AudioRecorder? = null
    private var transcriber: StreamingTranscriber? = null
    private var whisper: WhisperContext? = null
    @Volatile private var summarizer: Summarizer? = null
    private var recordedSamples = 0L
    private var processedMs = 0L

    fun init(context: Context) {
        app = context.applicationContext
        repo = MeetingRepository(app)
        models = ModelStore(app)
        settings = Settings(app)
        // 앱이 녹음 중 비정상 종료된 경우: WAV 헤더 복구 + 부분 녹취록 유지
        if (!_state.value.busy) repo.recoverUnfinished()
        // 오류 보고용 동작 기록 (단계 변화만, 내용은 남기지 않음)
        scope.launch {
            state.map { it.phase }.distinctUntilChanged().collect { ErrorReporter.trail("단계: $it") }
        }
    }

    fun missingSttModels() = listOf(ModelCatalog.WHISPER, ModelCatalog.VAD).filterNot { models.isReady(it) }

    // ---------------------------------------------------------------- 녹음

    fun startRecording() {
        if (_state.value.busy) return
        val missing = missingSttModels()
        if (missing.isNotEmpty()) {
            _state.value = EngineState(Phase.ERROR, message = "음성인식 모델이 필요합니다: ${missing.joinToString { it.title }}")
            return
        }
        val meeting = repo.create()
        _state.value = EngineState(Phase.LOADING, meetingId = meeting.id, message = "음성인식 모델 불러오는 중…")
        startService()

        scope.launch(inference) {
            try {
                val w = whisper ?: run { ErrorReporter.trail("Whisper 로드 (스레드 ${settings.threads})"); WhisperContext.load(models.file(ModelCatalog.WHISPER).path) }.also { whisper = it }
                recordedSamples = 0; processedMs = 0
                val t = StreamingTranscriber(
                    scope = CoroutineScope(inference),
                    whisper = w,
                    vadPath = models.file(ModelCatalog.VAD).path,
                    threads = settings.threads,
                    vocabulary = settings.vocabulary,
                    onLines = { lines -> appendLines(lines) },
                    onChunkDone = { audioMs, procMs ->
                        processedMs += audioMs
                        _state.update {
                            it.copy(
                                lastRtf = if (procMs > 0) procMs.toDouble() / audioMs else it.lastRtf,
                                sttBacklogMs = (recordedSamples * 1000 / AudioRecorder.SAMPLE_RATE - processedMs).coerceAtLeast(0),
                            )
                        }
                    },
                )
                transcriber = t
                scopeAnalyzer = ScopeAnalyzer(); _scope.value = null
                val r = AudioRecorder(
                    repo.audioFile(meeting.id),
                    onFrames = { f ->
                        recordedSamples += f.size
                        t.feed(f)
                        _scope.value = scopeAnalyzer.push(f)
                    },
                    onLevel = { lv ->
                        val elapsed = recordedSamples * 1000 / AudioRecorder.SAMPLE_RATE
                        _state.update {
                            it.copy(level = lv, elapsedMs = elapsed,
                                sttBacklogMs = (elapsed - processedMs).coerceAtLeast(0))
                        }
                    },
                )
                recorder = r
                r.start()
                _state.update { it.copy(phase = Phase.RECORDING, message = null) }
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /** 인식 결과 추가 + 즉시 저장 (비정상 종료 시에도 녹취록 보존) */
    private fun appendLines(lines: List<TranscriptLine>) {
        val st = _state.updateAndGet { it.copy(lines = it.lines + lines) }
        st.meetingId?.let { runCatching { repo.saveTranscript(it, st.lines) } }
    }

    fun setPaused(paused: Boolean) { recorder?.paused = paused }
    val isPaused get() = recorder?.paused == true

    fun stopRecording(autoSummarize: Boolean = true) {
        val r = recorder ?: return
        val id = _state.value.meetingId ?: return
        _state.update { it.copy(phase = Phase.FINISHING_STT, message = "남은 음성 인식 중…") }
        scope.launch {
            r.stop(); recorder = null
            transcriber?.finish(); transcriber = null
            val lines = _state.value.lines
            repo.saveTranscript(id, lines)
            repo.get(id)?.let { repo.save(it.copy(durationMs = r.samplesWritten * 1000 / AudioRecorder.SAMPLE_RATE, recording = false)) }
            releaseWhisper()
            if (autoSummarize && lines.isNotEmpty()) summarizeInternal(id) else finishIdle()
        }
    }

    // ------------------------------------------------- 파일에서 인식 (가져오기/재인식)

    fun transcribeFile(meetingId: String, thenSummarize: Boolean = true) {
        if (_state.value.busy) return
        _state.value = EngineState(Phase.TRANSCRIBING_FILE, meetingId = meetingId, message = "음성 파일 인식 중…")
        startService()
        scope.launch {
            try {
                val w = withContext(inference) {
                    whisper ?: run { ErrorReporter.trail("Whisper 로드 (스레드 ${settings.threads})"); WhisperContext.load(models.file(ModelCatalog.WHISPER).path) }.also { whisper = it }
                }
                val audio = repo.audioFile(meetingId)
                val totalMs = wavSampleCount(audio) * 1000L / AudioRecorder.SAMPLE_RATE
                repo.get(meetingId)?.let { repo.save(it.copy(durationMs = totalMs, recording = false, recovered = false)) }
                processedMs = 0
                val t = StreamingTranscriber(
                    scope = CoroutineScope(inference), whisper = w,
                    vadPath = models.file(ModelCatalog.VAD).path, threads = settings.threads,
                    vocabulary = settings.vocabulary,
                    onLines = { lines -> appendLines(lines) },
                    onChunkDone = { audioMs, procMs ->
                        processedMs += audioMs
                        _state.update {
                            it.copy(elapsedMs = processedMs, sttBacklogMs = totalMs - processedMs,
                                lastRtf = if (procMs > 0) procMs.toDouble() / audioMs else it.lastRtf)
                        }
                    },
                )
                // 녹음과 동일한 분할 로직을 쓰기 위해 100ms 단위로 스트리밍 공급 (메모리 사용 일정)
                readWavBlocks(audio, AudioRecorder.SAMPLE_RATE / 10) { block ->
                    while (t.pendingChunks >= 2) Thread.sleep(200)
                    t.feed(block)
                }
                t.finish()
                repo.saveTranscript(meetingId, _state.value.lines)
                releaseWhisper()
                if (thenSummarize && _state.value.lines.isNotEmpty()) summarizeInternal(meetingId) else finishIdle()
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    // ------------------------------------------------- 외부 음성 파일 가져오기

    fun importAudio(uri: android.net.Uri, displayName: String?) {
        if (_state.value.busy) return
        val m = repo.create()
        _state.value = EngineState(Phase.TRANSCRIBING_FILE, meetingId = m.id, message = "음성 파일 변환 중…")
        startService()
        scope.launch(Dispatchers.IO) {
            try {
                AudioImporter.decodeToWav(app, uri, repo.audioFile(m.id)) { p ->
                    _state.update { it.copy(message = "음성 파일 변환 중… ${(p * 100).toInt()}%") }
                }
                displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }?.let {
                    repo.get(m.id)?.let { mm -> repo.save(mm.copy(title = it.take(40))) }
                }
                _state.value = EngineState()
                transcribeFile(m.id, thenSummarize = true)
            } catch (e: Throwable) {
                repo.delete(m.id)
                fail(e)
            }
        }
    }

    // ---------------------------------------------------------------- 요약

    fun summarize(meetingId: String) {
        if (_state.value.busy) return
        _state.value = EngineState(Phase.SUMMARIZING, meetingId = meetingId, lines = repo.loadTranscript(meetingId))
        startService()
        scope.launch { summarizeInternal(meetingId) }
    }

    fun cancel() {
        summarizer?.cancel()
    }

    private suspend fun summarizeInternal(id: String) = withContext(inference) {
        val spec = settings.llm
        if (!models.isReady(spec)) {
            _state.update { it.copy(phase = Phase.ERROR, message = "요약 모델이 없습니다: ${spec.title}") }
            return@withContext
        }
        _state.update { it.copy(phase = Phase.SUMMARIZING, summaryText = "", summaryStage = "요약 모델 불러오는 중…", message = null) }
        val transcript = repo.loadTranscript(id).ifEmpty { _state.value.lines }
        var llm: LlamaModel? = null
        try {
            val t0 = System.currentTimeMillis()
            ErrorReporter.trail("요약 모델 로드: ${spec.id}, 녹취 ${transcript.size}문장, 스레드 ${settings.threads}")
            llm = LlamaModel.load(models.file(spec).path, nCtx = 4096, threads = settings.threads)
            val s = Summarizer(llm, spec, vocabulary = settings.vocabulary).also { summarizer = it }
            val (minutes, stats) = s.summarize(
                transcript,
                onProgress = { p ->
                    ErrorReporter.trail("요약: ${p.stage} ${p.step}/${p.total}")
                    _state.update { it.copy(summaryStage = if (p.total > 1) "${p.stage} (${p.step}/${p.total})" else p.stage) }
                },
                onText = { txt -> _state.update { it.copy(summaryText = txt) } },
            )
            val totalSec = (System.currentTimeMillis() - t0) / 1000
            val genTok = stats.sumOf { it.genTokens }
            val genMs = stats.sumOf { it.genMs }
            val statText = "총 ${totalSec}초 · 생성 ${genTok}토큰 (%.1f tok/s)".format(if (genMs > 0) genTok * 1000.0 / genMs else 0.0)
            repo.saveMinutes(id, minutes)
            var meeting = repo.get(id)?.copy(hasMinutes = true, modelUsed = spec.title, stats = statText)
            // 사용자가 제목을 바꾸지 않았으면 회의록으로 제목 자동 생성
            if (meeting != null && !meeting.titleEdited) {
                _state.update { it.copy(summaryStage = "제목 만드는 중…") }
                runCatching { s.makeTitle(minutes) }.getOrNull()?.let { t ->
                    val date = java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREAN).format(java.util.Date(meeting!!.createdAt))
                    meeting = meeting!!.copy(title = "$date · $t")
                }
            }
            meeting?.let { repo.save(it) }
            Log.i(TAG, "summary done: $statText")
            finishIdle()
        } catch (e: Throwable) {
            fail(e)
        } finally {
            summarizer = null
            llm?.close()
        }
    }

    // ---------------------------------------------------------------- 공통

    private fun releaseWhisper() {
        whisper?.close(); whisper = null
    }

    private fun finishIdle() {
        _state.update { it.copy(phase = Phase.IDLE, message = null) }
    }

    private fun fail(e: Throwable) {
        Log.e(TAG, "error", e)
        ErrorReporter.recordError(e)
        recorder?.stop(); recorder = null
        _state.update { it.copy(phase = Phase.ERROR, message = e.message ?: e.toString()) }
    }

    fun clearError() {
        if (_state.value.phase == Phase.ERROR) _state.value = EngineState()
    }

    private fun startService() {
        ContextCompat.startForegroundService(app, Intent(app, MeetingService::class.java))
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var llm: ModelSpec
        get() = ModelCatalog.llms.firstOrNull { it.id == prefs.getString("llm", null) } ?: ModelCatalog.QWEN3_4B
        set(v) = prefs.edit().putString("llm", v.id).apply()

    /** 용어 사전: 회사명·인명·전문용어 (쉼표 구분). 음성인식 힌트와 회의록 표기 교정에 사용 */
    var vocabulary: String
        get() = prefs.getString("vocabulary", "") ?: ""
        set(v) = prefs.edit().putString("vocabulary", v.trim()).apply()

    /** Snapdragon 870: 빅코어(A77) 4개 사용 시 가장 빠름 (8스레드는 오히려 느림) */
    var threads: Int
        get() = prefs.getInt("threads", 4)
        set(v) = prefs.edit().putInt("threads", v).apply()
}
