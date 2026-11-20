package com.meetingnote.app.ui

import com.meetingnote.app.DeviceCheck
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Translate
import com.meetingnote.app.DeviceInfo
import com.meetingnote.app.ErrorReporter
import com.meetingnote.app.MeetingEngine
import com.meetingnote.app.ModelCatalog
import com.meetingnote.app.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 설정 화면 (전체 화면, 변경 즉시 적용).
 * 넓은 화면은 2단(모델 | 사전·성능·저장 공간·정보), 좁은 화면은 1단.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = MeetingEngine.settings
    val engine by MeetingEngine.state.collectAsState()
    var llm by remember { mutableStateOf(settings.llm) }
    var tick by remember { mutableIntStateOf(0) }           // 다운로드 진행률·용량 갱신
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    var reporting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ModelSpec?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("설정") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { pad ->
        val sttCard: @Composable () -> Unit = {
            SectionCard(Icons.Default.GraphicEq, "음성인식", "녹음을 글로 바꾸는 모델입니다.") {
                ModelOption(ModelCatalog.WHISPER, selected = null, tick = tick, busy = engine.busy,
                    onSelect = {}, onDelete = {})
                HorizontalDivider()
                ModelOption(ModelCatalog.VAD, selected = null, tick = tick, busy = engine.busy,
                    onSelect = {}, onDelete = { confirmDelete = ModelCatalog.VAD })
                HorizontalDivider()
                var liveModel by remember { mutableStateOf(settings.liveModel) }
                Text("실시간 인식 모델", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "자동", "small" to "기본", "turbo" to "정밀").forEach { (id, label) ->
                        FilterChip(selected = liveModel == id, onClick = { liveModel = id; settings.liveModel = id },
                            label = { Text(label) }, enabled = !engine.busy)
                    }
                }
                val turboReady = MeetingEngine.models.isReady(ModelCatalog.REFINER)
                val picked = remember(liveModel, tick) { MeetingEngine.liveSttSpec() }
                Text(
                    "기본(small)은 빠르지만 회의실처럼 마이크에서 먼 목소리는 오인식이 많습니다. 정밀(turbo)은 훨씬 정확하지만 " +
                        "약 3배 느려 큰 코어를 많이 씁니다. 자동은 기기 점검 결과로 정밀 모델을 쓸 수 있으면 씁니다. " +
                        "통역 중에는 번역과 함께 돌려도 충분히 빠를 때만 정밀 모델을 씁니다.\n" +
                        when {
                            !turboReady && liveModel != "small" -> "→ 정밀 모델이 없어 기본 모델을 씁니다. 아래에서 내려받으세요."
                            liveModel == "auto" && DeviceCheck.bench == null -> "→ 기기 점검 전이라 기본 모델을 씁니다."
                            else -> "→ 지금 설정으로는 ${if (picked == ModelCatalog.REFINER) "정밀" else "기본"} 모델을 씁니다."
                        },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var showLive by remember { mutableStateOf(settings.showLiveTranscript) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("녹음 중 녹취록 표시", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("끄면 녹음 중에는 파형만 보입니다. 인식은 계속하므로 회의 후 녹취록과 회의록은 똑같이 만들어집니다. " +
                            "(통역 중에는 항상 표시)",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(showLive, { showLive = it; settings.showLiveTranscript = it })
                }
                HorizontalDivider()
                var refine by remember { mutableStateOf(settings.refine) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("회의 후 정밀 인식", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("녹음이 끝나면 정밀 모델로 전체를 다시 인식해 녹취록을 바꾼 뒤 회의록을 만듭니다. " +
                            "작은 목소리·숫자·직함 인식이 크게 좋아지지만 녹음 시간의 약 0.7배가 더 걸립니다. " +
                            "가져온 음성 파일과 '다시 인식'에도 적용됩니다. (통역 회의는 제외)",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(refine, { refine = it; settings.refine = it })
                }
                if (refine) {
                    var skipRefine by remember { mutableStateOf(settings.skipRefineAfterTurbo) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("실시간 인식이 정밀 모델이면 생략", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("녹음 중에 이미 정밀 모델로 인식했으면 회의 후 다시 인식하지 않고 바로 회의록을 만듭니다. " +
                                "회의록이 녹음 시간의 약 0.7배만큼 빨리 나오지만, 다시 인식할 때보다 정확도는 조금 낮을 수 있습니다.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(skipRefine, { skipRefine = it; settings.skipRefineAfterTurbo = it })
                    }
                    var quick by remember { mutableStateOf(settings.quickMinutes) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("회의록 먼저 보기", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("정밀 인식을 기다리지 않고 실시간 녹취록으로 회의록을 먼저 만든 뒤, 정밀 인식이 끝나면 다시 만듭니다. " +
                                "첫 회의록은 빨리 나오지만 전체 처리 시간은 요약 한 번만큼 늘어납니다.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(quick, { quick = it; settings.quickMinutes = it })
                    }
                    var refineVocab by remember { mutableStateOf(settings.refineVocabulary) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("정밀 인식에 용어 사전 적용 (실험)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("용어 사전을 정밀 인식의 힌트로도 씁니다. 회사명·인명 표기가 좋아질 수 있지만 " +
                                "문장이 길게 뭉쳐 나올 수 있습니다. 끄면 사전은 실시간 인식과 회의록 작성에만 쓰입니다.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(refineVocab, { refineVocab = it; settings.refineVocabulary = it })
                    }
                }
                ModelOption(ModelCatalog.REFINER, selected = null, tick = tick, busy = engine.busy,
                    onSelect = {}, onDelete = { confirmDelete = ModelCatalog.REFINER })
                if (refine && !MeetingEngine.models.isReady(ModelCatalog.REFINER))
                    Hint("정밀 인식을 쓰려면 위 모델(874MB)을 내려받으세요. 없으면 실시간 녹취록으로 회의록을 만듭니다.")
            }
        }
        val llmCard: @Composable () -> Unit = {
            SectionCard(Icons.Default.Summarize, "회의록 요약", "녹취록으로 회의록과 제목을 만드는 모델입니다.") {
                ModelCatalog.llms.forEach { spec ->
                    ModelOption(spec, selected = llm == spec, tick = tick, busy = engine.busy,
                        onSelect = { llm = spec; settings.llm = spec },
                        onDelete = { confirmDelete = spec })
                }
                Hint("Gemma 4는 실시간 통역에도 쓰여서, 하나만 받으면 회의록과 통역을 모두 쓸 수 있습니다. " +
                    "EXAONE은 비상업적 용도로만 사용할 수 있습니다.")
            }
        }
        val vocabCard: @Composable () -> Unit = { VocabularyCard() }
        val langCard: @Composable () -> Unit = { LanguageCard(tick) }
        val perfCard: @Composable () -> Unit = { DeviceCheckCard(tick, busy = engine.busy); PerformanceCard() }
        val storageCard: @Composable () -> Unit = { StorageCard(tick) }
        val aboutCard: @Composable () -> Unit = { AboutCard(onReport = { reporting = true }) }

        BoxWithConstraints(Modifier.fillMaxSize().padding(pad).imePadding()) {
            val wide = maxWidth >= 900.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (wide) {
                    Row(Modifier.widthIn(max = 1400.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            sttCard(); langCard(); llmCard()
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            vocabCard(); perfCard(); storageCard(); aboutCard()
                        }
                    }
                } else {
                    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        sttCard(); langCard(); llmCard(); vocabCard(); perfCard(); storageCard(); aboutCard()
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    confirmDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("모델 삭제") },
            text = { Text("${spec.title} (${mb(spec.sizeBytes)})을 삭제합니다. 다시 쓰려면 새로 받아야 합니다.") },
            confirmButton = {
                TextButton(onClick = { MeetingEngine.models.delete(spec); confirmDelete = null; tick++ }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
        )
    }
    if (reporting) ErrorReportDialog(onDismiss = { reporting = false })
}

// ---------------- 구성 요소 ----------------

@Composable
internal fun SectionCard(
    icon: ImageVector, title: String, subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            content()
        }
    }
}

@Composable
internal fun Hint(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * 모델 한 줄: 선택(라디오) · 이름/설명 · 상태(받음/받는 중/받기) · 삭제.
 * @param selected null이면 선택 개념이 없는 모델(VAD)
 */
@Composable
private fun ModelOption(
    spec: ModelSpec, selected: Boolean?, tick: Int, busy: Boolean,
    onSelect: () -> Unit, onDelete: () -> Unit,
) {
    val store = MeetingEngine.models
    val ready = remember(tick, spec) { store.isReady(spec) }
    val progress = remember(tick, spec) { store.progress(spec) }
    val rowMod = if (selected != null) Modifier.selectable(selected, onClick = onSelect) else Modifier
    // 오른쪽 상태(받음/진행률/받기). 휴대폰처럼 좁으면 이름 아래 줄로 내려 이름·설명 칸이 찌그러지지 않게
    val status: @Composable () -> Unit = {
        when {
            ready -> {
                AssistChip(
                    onClick = {}, enabled = false,
                    label = { Text("받음 · ${mb(spec.sizeBytes)}") },
                    colors = AssistChipDefaults.assistChipColors(disabledLabelColor = MaterialTheme.colorScheme.primary),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                )
                // 선택 가능한 모델 중 사용하지 않는 것만 삭제 가능 (필수 모델·사용 중·처리 중에는 불가)
                val canDelete = selected == false && !busy
                IconButton(onClick = onDelete, enabled = canDelete) {
                    Icon(Icons.Default.DeleteOutline, if (canDelete) "삭제" else "사용 중이라 삭제 불가")
                }
            }
            progress != null -> {
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { store.cancel(spec) }) { Icon(Icons.Default.Close, "다운로드 취소") }
            }
            else -> OutlinedButton(onClick = { store.download(spec) }) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Text(" 받기 · ${mb(spec.sizeBytes)}")
            }
        }
    }
    BoxWithConstraints(rowMod.fillMaxWidth()) {
    val narrow = maxWidth < 440.dp   // 들여쓰기 한 단계 생략 (BoxWithConstraints 래퍼)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) RadioButton(selected, onClick = onSelect) else Spacer(Modifier.width(if (narrow) 0.dp else 48.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(spec.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!narrow) { Spacer(Modifier.width(8.dp)); status() }
        }
        if (narrow) Row(Modifier.padding(start = if (selected != null) 48.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) { status() }
        if (!ready && progress != null) {
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp))
        }
        if (selected == true && !ready && progress == null) {
            Text("선택되었지만 아직 받지 않았습니다. 받기를 눌러 주세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 48.dp))
        }
    }
    }
}

/** 통역(한국어 ↔ 영어)과 회의록 언어 */
@Composable
private fun LanguageCard(tick: Int) {
    val settings = MeetingEngine.settings
    var interpret by remember { mutableStateOf(settings.interpret) }
    var lang by remember { mutableStateOf(settings.minutesLang) }
    SectionCard(Icons.Default.Translate, "통역 · 회의록 언어", "한국어와 영어가 섞인 회의를 실시간으로 통역합니다.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("실시간 통역 (한국어 ↔ 영어)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("말한 언어를 자동으로 알아내 반대 언어로 번역해 녹취록에 함께 표시합니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(interpret, { interpret = it; settings.interpret = it })
        }
        val gemma = ModelCatalog.INTERPRETER
        if (interpret) {
            val spec = remember(tick) { MeetingEngine.interpreterSpec() }
            val ready = remember(tick) { MeetingEngine.models.isReady(spec) }
            val turbo = remember(tick) { MeetingEngine.liveSttSpec() == ModelCatalog.REFINER }
            Hint(
                "번역은 회의록 모델 설정과 상관없이 ${gemma.title.substringBefore(" (")}(없으면 Qwen3)로 기기 안에서 합니다. " +
                    when {
                        spec == gemma && ready -> "지금은 ${gemma.title.substringBefore(" (")}로 통역합니다."
                        ready -> "${gemma.title.substringBefore(" (")}가 없어 ${spec.title.substringBefore(" (")}로 통역합니다. " +
                            "${gemma.title.substringBefore(" (")}를 받으면 번역이 약 2배 빨라지고 숫자·고유명사가 더 정확합니다."
                        else -> ""
                    } +
                    " 통역 중 실시간 인식은 " +
                    (if (turbo) "정밀 모델을 씁니다(기기 점검 결과 번역과 함께 돌려도 충분히 빠름)."
                     else "기본 모델을 씁니다. 통역 모델을 받은 뒤 기기 점검을 하면 정밀 모델을 쓸 수 있는지 판단합니다.") +
                    " 녹음 중에도 모델이 메모리에 올라가 배터리 사용이 늘어납니다."
            )
            if (!ready) Text(
                "⚠ 통역 모델이 없습니다. 아래 '회의록 요약'에서 ${gemma.title.substringBefore(" (")}를 내려받아야 통역을 쓸 수 있습니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider()
        Text("회의록 언어", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ko" to "한국어", "en" to "English").forEach { (code, label) ->
                androidx.compose.material3.FilterChip(
                    selected = lang == code, onClick = { lang = code; settings.minutesLang = code },
                    label = { Text(label) },
                )
            }
        }
        Hint("녹취록이 한·영 혼합이어도 선택한 언어로 회의록과 제목을 작성합니다.")
    }
}

@Composable
private fun VocabularyCard() {
    val settings = MeetingEngine.settings
    var vocab by remember { mutableStateOf(settings.vocabulary) }
    val focus = LocalFocusManager.current
    SectionCard(Icons.Default.Spellcheck, "용어 사전", "회사명·인명·전문용어를 쉼표로 구분해 입력하세요.") {
        OutlinedTextField(
            vocab, { vocab = it; settings.vocabulary = it }, Modifier.fillMaxWidth(),
            placeholder = { Text("예: 에이전트 하네스, 김민수, 쿠버네티스, OKR") },
            minLines = 2,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
        val n = vocab.split(',', '\n').count { it.isNotBlank() }
        Hint(if (n == 0) "음성인식 힌트와 회의록 표기 교정에 함께 쓰입니다." else "용어 ${n}개 · 음성인식 힌트와 회의록 표기 교정에 쓰입니다. 입력하는 즉시 저장됩니다.")
    }
}

@Composable
private fun PerformanceCard() {
    val settings = MeetingEngine.settings
    var threads by remember { mutableIntStateOf(settings.threads) }
    val rec = DeviceInfo.recommendedThreads
    SectionCard(Icons.Default.Memory, "성능", "${DeviceInfo.chipName} · 고성능 코어 ${DeviceInfo.bigCores}개") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CPU 스레드", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("$threads${if (threads == rec) " (권장)" else ""}", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            threads.toFloat(), { threads = it.toInt() }, valueRange = 2f..8f, steps = 5,
            onValueChangeFinished = { settings.threads = threads },
        )
        Row(Modifier.fillMaxWidth()) {
            Text("2", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("8", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Hint("권장값($rec) = 고성능 코어 수. 효율 코어까지 쓰면 오히려 느려집니다 (Snapdragon 870 실측: 8스레드 < 4스레드).")
        if (threads != rec) TextButton(onClick = { threads = rec; settings.threads = rec }) { Text("권장값($rec)으로 되돌리기") }
    }
}

@Composable
private fun StorageCard(tick: Int) {
    data class Usage(val meetings: Int, val meetingBytes: Long, val modelBytes: Long, val free: Long)
    var u by remember { mutableStateOf<Usage?>(null) }
    LaunchedEffect(tick / 4) {   // 약 6초마다 갱신
        u = withContext(Dispatchers.IO) {
            val repo = MeetingEngine.repo
            Usage(
                meetings = repo.list().size,
                meetingBytes = repo.root.walkTopDown().filter(File::isFile).sumOf { it.length() },
                modelBytes = MeetingEngine.models.usedBytes(),
                free = repo.root.usableSpace,
            )
        }
    }
    SectionCard(Icons.Default.Storage, "저장 공간", "모든 녹음과 모델은 이 기기 안에만 저장됩니다.") {
        val x = u
        if (x == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
            UsageRow("회의 ${x.meetings}개 (녹음·녹취록·회의록)", x.meetingBytes)
            UsageRow("모델", x.modelBytes)
            UsageRow("남은 공간", x.free)
            Hint("녹음 1시간 ≈ 115MB. 회의는 홈에서 회의를 열어 삭제할 수 있습니다.")
        }
    }
}

@Composable
private fun UsageRow(label: String, bytes: Long) = Row {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Text(if (bytes >= 1_000_000_000) "%.1fGB".format(bytes / 1e9) else mb(bytes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutCard(onReport: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "-" }
    var showLicenses by remember { mutableStateOf(false) }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    SectionCard(Icons.Default.Info, "정보 및 지원", "회의노트 v$version") {
        Hint("녹음·녹취록·회의록은 기기 밖으로 전송되지 않습니다. 인터넷은 모델을 받을 때와 오류 보고를 열 때만 씁니다.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReport) { Icon(Icons.Default.BugReport, null, Modifier.size(18.dp)); Text(" 오류 보고") }
            OutlinedButton(onClick = { open("https://github.com/${ErrorReporter.REPO}") }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp)); Text(" GitHub")
            }
            TextButton(onClick = { showLicenses = !showLicenses }) { Text(if (showLicenses) "라이선스 닫기" else "라이선스") }
        }
        if (showLicenses) {
            listOf(
                "회의노트 앱" to "MIT",
                "whisper.cpp · llama.cpp (ggml)" to "MIT",
                "Whisper small (OpenAI)" to "MIT",
                "Silero VAD" to "MIT",
                "Gemma 4 E2B (Google)" to "Apache-2.0",
                "Qwen3 4B Instruct (Alibaba)" to "Apache-2.0",
                "EXAONE 4.0 1.2B (LG AI Research)" to "EXAONE AI Model License (비상업)",
            ).forEach { (name, lic) ->
                Row { Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(lic, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

private fun mb(bytes: Long) = "%.0fMB".format(bytes / 1e6)
