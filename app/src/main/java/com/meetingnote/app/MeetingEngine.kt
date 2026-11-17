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

enum class Phase { IDLE, LOADING, RECORDING, FINISHING_STT, TRANSCRIBING_FILE, REFINING, SUMMARIZING, ERROR }

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
    /** 한↔영 통역 중 */
    val interpreting: Boolean = false,
    /** 번역 대기 문장 수 */
    val translatePending: Int = 0,
    /** 정밀 인식 진행률 0..1, 남은 시간(초) */
    val refineFraction: Float? = null,
    val refineEtaSec: Int? = null,
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

    /** 오실로스코프 TIME/DIV 변경 (녹음 중 즉시 반영, 설정에 저장) */
    fun setScopeTimeDiv(i: Int) { settings.scopeTimeDiv = i; scopeAnalyzer.timeDivIndex = i }
    /** 추론 전용 스레드 (빅코어 4개를 ggml 스레드풀이 사용) */
    private val inference = Dispatchers.Default.limitedParallelism(1)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    private var recorder: AudioRecorder? = null
    private var transcriber: StreamingTranscriber? = null
    private var translator: Translator? = null
    /** 통역용으로 녹음 중 불러 둔 LLM. 녹음이 끝나면 회의록 작성에 그대로 재사용 */
    private var sharedLlm: LlamaModel? = null
    private var sharedLlmSpec: ModelSpec? = null
    private var whisper: WhisperContext? = null
    private var whisperSpec: ModelSpec? = null

    /** 요청한 음성인식 모델을 불러옴. 다른 모델이 올라가 있으면 해제 후 교체 */
    private fun loadWhisper(spec: ModelSpec): WhisperContext {
        whisper?.let { if (whisperSpec == spec) return it }
        releaseWhisper()
        ErrorReporter.trail("Whisper 로드: ${spec.id} (스레드 ${settings.threads})")
        return WhisperContext.load(models.file(spec).path).also { whisper = it; whisperSpec = spec }
    }
    @Volatile private var summarizer: Summarizer? = null
    private var recordedSamples = 0L
    private var processedMs = 0L

    fun init(context: Context) {
        app = context.applicationContext
        repo = MeetingRepository(app)
        models = ModelStore(app)
        settings = Settings(app)
        DeviceCheck.init(app)
        // 앱이 녹음 중 비정상 종료된 경우: WAV 헤더 복구 + 부분 녹취록 유지
        if (!_state.value.busy) repo.recoverUnfinished()
        // 삭제된 기능의 모델 파일 정리 (예: 고정밀 turbo 874MB)
        ModelCatalog.removedFiles.forEach { java.io.File(models.dir, it).delete() }
        // 오류 보고용 동작 기록 (단계 변화만, 내용은 남기지 않음)
        scope.launch {
            state.map { it.phase }.distinctUntilChanged().collect { ErrorReporter.trail("단계: $it") }
        }
    }

    // ---------------------------------------------------------------- 기기 점검

    private val _checking = MutableStateFlow<String?>(null)
    /** 기기 점검 진행 문구 (null = 점검 중 아님) */
    val checking: StateFlow<String?> = _checking

    /** 점검 중에는 녹음·인식·요약을 시작하지 않음 (같은 CPU·메모리를 써서 결과와 동작 모두 망가짐) */
    private fun checkingBlocks(): Boolean {
        if (_checking.value == null) return false
        _state.value = EngineState(Phase.ERROR, message = "기기 점검 중입니다. 끝난 뒤(약 30초) 다시 시도해 주세요.")
        return true
    }

    private class CheckInterrupted(msg: String) : Exception(msg)

    /** 앱이 화면 앞에 있는지 (뒤로 가면 시스템이 느린 코어로 옮겨 실측값이 틀어짐) */
    private fun inForeground(): Boolean =
        android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
            .importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    /** 실측 점검: 음성인식 10초 처리 시간, 내려받은 요약 모델별 처리 속도 */
    fun runDeviceCheck() {
        if (_state.value.busy || _checking.value != null) return
        _checking.value = "준비 중…"
        ErrorReporter.trail("기기 점검 시작")
        scope.launch(inference) {
            try {
                releaseSharedLlm()
                val bg = CheckInterrupted("점검 중 앱이 화면에서 벗어나 결과를 저장하지 않았습니다. 화면을 켠 채로 다시 점검해 주세요.")
                if (!inForeground()) throw bg
                var rtf: Double? = null
                if (missingSttModels().isEmpty()) {
                    _checking.value = "음성인식 속도 측정 중…"
                    val w = loadWhisper(settings.whisper)
                    val pcm = DeviceCheck.loadSample()
                    val audioCtx = ((pcm.size * 50L / AudioRecorder.SAMPLE_RATE + 64 + 63) / 64 * 64).toInt()
                    var best = Long.MAX_VALUE
                    repeat(2) {   // 첫 실행은 메모리 적재 등으로 느려 두 번 중 빠른 쪽
                        val t0 = System.currentTimeMillis()
                        w.transcribe(pcm.copyOf(), threads = settings.threads, language = "ko", beamSize = 5,
                            vadModelPath = models.file(ModelCatalog.VAD).path,
                            audioCtx = if (settings.trimEncoder) audioCtx else 0)
                        best = minOf(best, System.currentTimeMillis() - t0)
                    }
                    rtf = best / (pcm.size * 1000.0 / AudioRecorder.SAMPLE_RATE)
                    releaseWhisper()
                    if (!inForeground()) throw bg
                    Log.i("DeviceCheck", "stt ${best}ms rtf=%.3f".format(rtf))
                }
                val llm = mutableMapOf<String, DeviceCheck.LlmBench>()
                for (spec in ModelCatalog.llms.filter { models.isReady(it) && DeviceCheck.ramOkFor(it) }) {
                    _checking.value = "${spec.title.substringBefore(" (")} 속도 측정 중…"
                    LlamaModel.load(models.file(spec).path, nCtx = 2048, threads = settings.threads).use { m ->
                        // 예열 (모델 파일을 메모리에 올림)
                        m.generate(m.formatChat(null, "안녕하세요", spec.assistantPrefix), maxTokens = 2, temperature = 0f) { true }
                        val st = m.generate(m.formatChat(null, DeviceCheck.LLM_BENCH_TEXT, spec.assistantPrefix),
                            maxTokens = 48, temperature = 0f) { true }
                        if (!inForeground()) throw bg
                        if (st.promptMs > 0 && st.genMs > 0 && st.genTokens >= 8) {
                            llm[spec.id] = DeviceCheck.LlmBench(st.promptTps, st.genTps)
                        }
                        Log.i("DeviceCheck", "${spec.id} prompt ${st.promptTokens}tok/${st.promptMs}ms gen ${st.genTokens}tok/${st.genMs}ms " +
                            "-> %.1f / %.2f tok/s".format(st.promptTps, st.genTps))
                    }
                }
                DeviceCheck.bench = DeviceCheck.Bench(rtf, llm, System.currentTimeMillis(), settings.threads)
                ErrorReporter.trail("기기 점검 완료")
            } catch (e: Throwable) {
                Log.e("DeviceCheck", "check failed", e)
                if (e !is CheckInterrupted) ErrorReporter.recordError(e)
                runCatching { releaseWhisper() }
                _state.update { it.copy(phase = Phase.ERROR, message = "기기 점검 실패: ${e.message ?: e}") }
            } finally {
                _checking.value = null
            }
        }
    }

    fun missingSttModels(spec: ModelSpec = settings.whisper) = listOf(spec, ModelCatalog.VAD).filterNot { models.isReady(it) }

    private val interpreterMissingMessage get() =
        "실시간 통역에는 ${ModelCatalog.INTERPRETER.title.substringBefore(" (")} 모델이 필요합니다. 설정에서 내려받거나 통역을 꺼 주세요."

    /** 시작 전에 필요한 모델 확인: 없으면 오류 상태로 두고 false */
    private fun checkModels(sttSpec: ModelSpec): Boolean {
        val missing = missingSttModels(sttSpec)
        val msg = when {
            missing.isNotEmpty() -> "음성인식 모델이 필요합니다: ${missing.joinToString { it.title }}"
            settings.interpret && !models.isReady(ModelCatalog.INTERPRETER) -> interpreterMissingMessage
            else -> return true
        }
        _state.value = EngineState(Phase.ERROR, message = msg)
        return false
    }

    // ---------------------------------------------------------------- 녹음

    fun startRecording() {
        if (_state.value.busy || checkingBlocks()) return
        val sttSpec = settings.whisper
        if (!checkModels(sttSpec)) return
        val meeting = repo.create().copy(sttModel = sttSpec.title).also { repo.save(it) }
        _state.value = EngineState(Phase.LOADING, meetingId = meeting.id, message = "음성인식 모델 불러오는 중…")
        startService()

        scope.launch(inference) {
            try {
                val w = loadWhisper(sttSpec)
                if (settings.interpret) startInterpreter()
                recordedSamples = 0; processedMs = 0
                val t = StreamingTranscriber(
                    scope = CoroutineScope(inference),
                    whisper = w,
                    vadPath = models.file(ModelCatalog.VAD).path,
                    threads = settings.threads,
                    vocabulary = settings.vocabulary,
                    language = if (settings.interpret) "" else "ko",
                    trimEncoder = settings.trimEncoder,
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
                scopeAnalyzer = ScopeAnalyzer().also { it.timeDivIndex = settings.scopeTimeDiv }; _scope.value = null
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

    /** 통역 준비: 통역 모델(항상 Qwen3)을 불러와 번역기 생성 (추론 스레드에서 호출) */
    private fun startInterpreter(): Translator {
        val spec = ModelCatalog.INTERPRETER
        require(models.isReady(spec)) { interpreterMissingMessage }
        val prevMessage = _state.value.message
        _state.update { it.copy(message = "통역 모델 불러오는 중…") }
        ErrorReporter.trail("통역 모델 로드: ${spec.id}")
        val llm = sharedLlm?.takeIf { sharedLlmSpec == spec }
            ?: LlamaModel.load(models.file(spec).path, nCtx = 4096, threads = settings.threads).also { sharedLlm = it; sharedLlmSpec = spec }
        return Translator(llm, spec, CoroutineScope(inference)) { tr -> updateLine(tr) }
            .also { translator = it; _state.update { s -> s.copy(interpreting = true, message = prevMessage) } }
    }

    /** 번역 결과를 해당 문장에 반영하고 저장 */
    private fun updateLine(l: TranscriptLine) {
        val st = _state.updateAndGet { s ->
            s.copy(
                lines = s.lines.map { if (it.startMs == l.startMs && it.text == l.text) l else it },
                translatePending = translator?.pendingCount ?: 0,
            )
        }
        st.meetingId?.let { runCatching { repo.saveTranscript(it, st.lines) } }
    }

    /** 남은 번역 마무리 */
    private suspend fun finishInterpreter() {
        val t = translator ?: return
        _state.update { it.copy(phase = Phase.FINISHING_STT, message = "남은 번역 마무리 중…") }
        t.finish(); translator = null
        _state.update { it.copy(translatePending = 0) }
    }

    private fun releaseSharedLlm() {
        sharedLlm?.close(); sharedLlm = null; sharedLlmSpec = null
    }

    /** 인식 결과 추가 + 즉시 저장 (비정상 종료 시에도 녹취록 보존) */
    private fun appendLines(lines: List<TranscriptLine>) {
        translator?.submit(lines)
        val st = _state.updateAndGet { it.copy(lines = it.lines + lines, translatePending = translator?.pendingCount ?: 0) }
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
            finishInterpreter()
            val lines = _state.value.lines
            repo.saveTranscript(id, lines)
            repo.get(id)?.let { repo.save(it.copy(durationMs = r.samplesWritten * 1000 / AudioRecorder.SAMPLE_RATE, recording = false)) }
            releaseWhisper()
            try {
                if (shouldRefine(lines)) refineInternal(id)
            } catch (e: Throwable) {
                // 정밀 인식이 실패해도 실시간 녹취록으로 회의록은 만든다
                Log.e(TAG, "refine failed", e); ErrorReporter.recordError(e)
            }
            val finalLines = _state.value.lines
            if (autoSummarize && finalLines.isNotEmpty()) summarizeInternal(id) else { releaseSharedLlm(); finishIdle() }
        }
    }

    // ------------------------------------------------- 파일에서 인식 (가져오기/재인식)

    /** @param sttSpec 사용할 음성인식 모델 */
    fun transcribeFile(meetingId: String, thenSummarize: Boolean = true, sttSpec: ModelSpec = settings.whisper) {
        if (_state.value.busy || checkingBlocks()) return
        if (!checkModels(sttSpec)) return
        _state.value = EngineState(Phase.TRANSCRIBING_FILE, meetingId = meetingId, message = "음성 파일 인식 중…")
        startService()
        // 파일 인식(가져오기·다시 인식)은 실시간 표시가 필요 없으므로 바로 정밀 인식
        if (shouldRefine(repo.loadTranscript(meetingId))) {
            scope.launch {
                try {
                    val totalMs = wavSampleCount(repo.audioFile(meetingId)) * 1000L / AudioRecorder.SAMPLE_RATE
                    repo.get(meetingId)?.let { repo.save(it.copy(durationMs = totalMs, recording = false, recovered = false)) }
                    refineInternal(meetingId)
                    if (thenSummarize && _state.value.lines.isNotEmpty()) summarizeInternal(meetingId) else finishIdle()
                } catch (e: Throwable) {
                    fail(e)
                }
            }
            return
        }
        scope.launch {
            try {
                val w = withContext(inference) {
                    loadWhisper(sttSpec).also { if (settings.interpret) startInterpreter() }
                }
                val audio = repo.audioFile(meetingId)
                val totalMs = wavSampleCount(audio) * 1000L / AudioRecorder.SAMPLE_RATE
                repo.get(meetingId)?.let { repo.save(it.copy(durationMs = totalMs, recording = false, recovered = false, sttModel = sttSpec.title)) }
                processedMs = 0
                val t = StreamingTranscriber(
                    scope = CoroutineScope(inference), whisper = w,
                    vadPath = models.file(ModelCatalog.VAD).path, threads = settings.threads,
                    vocabulary = settings.vocabulary,
                    language = if (settings.interpret) "" else "ko",
                    trimEncoder = settings.trimEncoder,
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
                finishInterpreter()
                repo.saveTranscript(meetingId, _state.value.lines)
                releaseWhisper()
                if (thenSummarize && _state.value.lines.isNotEmpty()) summarizeInternal(meetingId) else { releaseSharedLlm(); finishIdle() }
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

    // ------------------------------------------------- 회의 후 정밀 인식

    /** 정밀 인식 대상: 설정이 켜져 있고 모델이 있으며, 통역 회의가 아님(번역이 실시간 녹취 문장에 붙어 있음) */
    private fun shouldRefine(lines: List<TranscriptLine>) =
        settings.refine && models.isReady(ModelCatalog.REFINER) && !settings.interpret && lines.none { it.translation != null }

    /**
     * 녹음 전체를 정밀 모델(large-v3-turbo)로 다시 인식해 녹취록을 교체한다.
     * 실시간 인식(짧은 조각 + 앞 문장 문맥)과 달리 최대 5분 단위로 길게 넣고 앞 구간 결과를 문맥으로 넘기지 않아
     * 작은 목소리·숫자·직함 오인식과 환각 반복이 크게 줄었다 (2026-11-17 실제 회의 녹음 비교).
     * 원래 실시간 녹취록은 transcript_live.json으로 보관.
     */
    private suspend fun refineInternal(id: String) = withContext(inference) {
        val spec = ModelCatalog.REFINER
        val audio = repo.audioFile(id)
        val total = wavSampleCount(audio)
        if (total < AudioRecorder.SAMPLE_RATE) return@withContext
        val sr = AudioRecorder.SAMPLE_RATE
        _state.update { it.copy(phase = Phase.REFINING, message = "정밀 인식 모델 불러오는 중…", refineFraction = 0f, refineEtaSec = null) }
        ErrorReporter.trail("정밀 인식 시작: ${total / sr}초")
        releaseSharedLlm()
        val w = loadWhisper(spec)
        val vocab = settings.vocabulary.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(", ").take(150).ifEmpty { null }
        val maxBlock = 5 * 60 * sr
        val buf = ShortArray(maxBlock + sr)
        var len = 0
        var offset = 0L
        val out = ArrayList<TranscriptLine>()
        val recent = ArrayDeque<String>()
        val t0 = System.currentTimeMillis()

        fun progress(done: Double) {
            val f = (done / total).toFloat().coerceIn(0f, 1f)
            val el = (System.currentTimeMillis() - t0) / 1000.0
            val eta = if (f > 0.03) (el / f * (1 - f)).toInt() else null
            _state.update { it.copy(refineFraction = f, refineEtaSec = eta,
                message = "정밀 인식 중 ${(f * 100).toInt()}% (녹음 ${formatTime(done.toLong() * 1000 / sr)} / ${formatTime(total * 1000 / sr)})") }
        }

        fun process(n: Int) {
            val pcm = FloatArray(n) { buf[it] / 32768f }
            val base = offset
            val r = w.transcribe(pcm, threads = settings.threads, language = "ko", beamSize = 5,
                initialPrompt = vocab, vadModelPath = models.file(ModelCatalog.VAD).path,
                noContext = true, onProgress = { pct -> progress(base + n * pct / 100.0) })
            val offMs = base * 1000 / sr
            for (seg in r.segments) {
                val text = Repetition.collapse(seg.text)
                val key = Repetition.normalize(text)
                if (Hallucination.isLikely(text) || (key.length >= 2 && key in recent)) continue
                out += TranscriptLine(offMs + seg.startMs, offMs + seg.endMs, text, lang = "ko")
                if (key.length >= 2) { recent.addLast(key); while (recent.size > 5) recent.removeFirst() }
            }
            System.arraycopy(buf, n, buf, 0, len - n)
            len -= n; offset += n
            progress(offset.toDouble())
        }

        /** 블록 끝 20초 안에서 가장 조용한 100ms 지점 (말을 자르지 않도록) */
        fun quietCut(): Int {
            val frame = sr / 10
            var best = len; var bestE = Double.MAX_VALUE
            var i = len - 20 * sr
            while (i + frame <= len) {
                var e = 0.0
                for (j in i until i + frame) { val v = buf[j].toDouble(); e += v * v }
                if (e < bestE) { bestE = e; best = i + frame / 2 }
                i += frame / 2
            }
            return best
        }

        progress(0.0)
        readWavBlocks(audio, sr / 10) { block ->
            System.arraycopy(block, 0, buf, len, block.size); len += block.size
            if (len >= maxBlock) process(quietCut())
        }
        if (len > sr / 2) process(len)
        releaseWhisper()

        if (out.isNotEmpty()) {
            repo.backupLiveTranscript(id)
            repo.saveTranscript(id, out)
            repo.get(id)?.let { repo.save(it.copy(sttModel = "${spec.title.substringBefore(" (")} (정밀 인식)")) }
            _state.update { it.copy(lines = out) }
        }
        val sec = (System.currentTimeMillis() - t0) / 1000
        Log.i(TAG, "refine done: ${total / sr}s audio in ${sec}s, ${out.size} lines")
        ErrorReporter.trail("정밀 인식 완료: ${sec}초, ${out.size}문장")
        _state.update { it.copy(refineFraction = null, refineEtaSec = null, message = null) }
    }

    // ---------------------------------------------------------------- 요약

    fun summarize(meetingId: String) {
        if (_state.value.busy || checkingBlocks()) return
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
            // 통역용으로 이미 불러 둔 모델이 있으면 재사용 (다시 불러오는 시간 절약)
            llm = sharedLlm?.takeIf { sharedLlmSpec == spec }?.also { sharedLlm = null; sharedLlmSpec = null }
                ?: run { releaseSharedLlm(); LlamaModel.load(models.file(spec).path, nCtx = 4096, threads = settings.threads) }
            val s = Summarizer(llm, spec, vocabulary = settings.vocabulary, lang = settings.minutesLang).also { summarizer = it }
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
                    val date = if (settings.minutesLang == "en")
                        java.text.SimpleDateFormat("MMM d", java.util.Locale.ENGLISH).format(java.util.Date(meeting!!.createdAt))
                    else java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREAN).format(java.util.Date(meeting!!.createdAt))
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
        whisper?.close(); whisper = null; whisperSpec = null
    }

    private fun finishIdle() {
        _state.update { it.copy(phase = Phase.IDLE, message = null) }
    }

    private fun fail(e: Throwable) {
        Log.e(TAG, "error", e)
        scope.launch(inference) {
            runCatching { translator?.finish() }; translator = null
            releaseSharedLlm()
        }
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
    /** 음성인식 모델 (고정밀 turbo 옵션은 실사용 오인식이 많아 삭제) */
    val whisper: ModelSpec get() = ModelCatalog.WHISPER

    var vocabulary: String
        get() = prefs.getString("vocabulary", "") ?: ""
        set(v) = prefs.edit().putString("vocabulary", v.trim()).apply()

    /** Snapdragon 870: 빅코어(A77) 4개 사용 시 가장 빠름 (8스레드는 오히려 느림) */
    var threads: Int
        get() = prefs.getInt("threads", DeviceInfo.recommendedThreads)
        set(v) = prefs.edit().putInt("threads", v).apply()

    /** 한국어 ↔ 영어 실시간 통역 */
    var interpret: Boolean
        get() = prefs.getBoolean("interpret", false)
        set(v) = prefs.edit().putBoolean("interpret", v).apply()
    /** 회의록 언어: "ko" / "en" */
    var minutesLang: String
        get() = prefs.getString("minutes_lang", "ko") ?: "ko"
        set(v) = prefs.edit().putString("minutes_lang", v).apply()
    /** 회의 후 정밀 인식 (large-v3-turbo 모델이 있을 때) */
    var refine: Boolean
        get() = prefs.getBoolean("refine", true)
        set(v) = prefs.edit().putBoolean("refine", v).apply()
    /** 짧은 조각 인코더 창 축소 (실측: 정확도 동일, 조각당 처리 7.2s → 2.0s) */
    var trimEncoder: Boolean
        get() = prefs.getBoolean("trim_encoder", true)
        set(v) = prefs.edit().putBoolean("trim_encoder", v).apply()

    /** 통역 자막 글자 크기 (sp) */
    var captionSize: Int
        get() = prefs.getInt("caption_size", 30)
        set(v) = prefs.edit().putInt("caption_size", v).apply()

    // 오실로스코프 손잡이 (0..10 단계, TIME/DIV는 TimeDiv 인덱스)
    var scopeIntensity: Int
        get() = prefs.getInt("scope_intensity", 7)
        set(v) = prefs.edit().putInt("scope_intensity", v).apply()
    var scopeFocus: Int
        get() = prefs.getInt("scope_focus", 7)
        set(v) = prefs.edit().putInt("scope_focus", v).apply()
    var scopeTimeDiv: Int
        get() = prefs.getInt("scope_timediv", TimeDiv.DEFAULT)
        set(v) = prefs.edit().putInt("scope_timediv", v).apply()
}
