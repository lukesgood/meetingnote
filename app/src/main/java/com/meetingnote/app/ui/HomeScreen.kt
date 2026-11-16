package com.meetingnote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.OutlinedTextField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.provider.OpenableColumns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.DialogProperties
import com.meetingnote.app.Meeting
import com.meetingnote.app.MeetingEngine
import com.meetingnote.app.ModelCatalog
import com.meetingnote.app.ModelSpec
import com.meetingnote.app.Phase
import com.meetingnote.app.formatTime
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onRecord: () -> Unit, onOpen: (String) -> Unit, onOpenLive: () -> Unit) {
    val state by MeetingEngine.state.collectAsState()
    val ctx = LocalContext.current
    // 외부 음성/동영상 파일 가져오기 (휴대폰 녹음, 메신저로 받은 파일 등)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            MeetingEngine.importAudio(uri, name)
            onOpenLive()
        }
    }
    var meetings by remember { mutableStateOf(emptyList<Meeting>()) }
    var showSettings by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.phase, tick) { meetings = MeetingEngine.repo.list() }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }   // 다운로드 진행률 갱신

    val requiredMissing = (listOf(ModelCatalog.WHISPER, ModelCatalog.VAD, MeetingEngine.settings.llm))
        .filterNot { MeetingEngine.models.isReady(it) }
        .also { tick }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("회의노트", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        enabled = !state.busy,
                        onClick = { importLauncher.launch(arrayOf("audio/*", "video/*")) },
                    ) { Icon(Icons.Default.UploadFile, "음성 파일 가져오기") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "설정") }
                },
            )
        },
        floatingActionButton = {
            if (!state.busy) ExtendedFloatingActionButton(
                onClick = onRecord,
                icon = { Icon(Icons.Default.Mic, null) },
                text = { Text("회의 녹음 시작") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            // 마지막 항목이 '회의 녹음 시작' 버튼에 가리지 않도록 하단 여백
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
        ) {
            if (state.busy) item {
                Card(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenLive),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            when (state.phase) {
                                Phase.RECORDING -> "● 녹음 중 ${formatTime(state.elapsedMs)}"
                                Phase.SUMMARIZING -> "회의록 작성 중 · ${state.summaryStage}"
                                else -> state.message ?: "처리 중…"
                            }, fontWeight = FontWeight.SemiBold
                        )
                        Text("눌러서 진행 화면 보기", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.phase == Phase.ERROR) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.message ?: "오류", Modifier.weight(1f))
                        TextButton(onClick = { MeetingEngine.clearError() }) { Text("닫기") }
                    }
                }
            }
            if (requiredMissing.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("처음 사용하려면 모델이 필요합니다", fontWeight = FontWeight.SemiBold)
                        Text(
                            "모든 처리는 기기 안에서만 이루어지며, 녹음은 외부로 전송되지 않습니다. Wi-Fi에서 받으세요.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        requiredMissing.forEach { ModelRow(it, tick) }
                    }
                }
            }
            item {
                Text("회의 기록", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            if (meetings.isEmpty()) item {
                Text("아직 기록이 없습니다. 아래 버튼으로 회의 녹음을 시작하세요.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp))
            }
            items(meetings, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(m.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (m.hasMinutes) Icons.Default.Description else Icons.Default.Mic, null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatTime(m.durationMs)} · " + when {
                                    m.hasMinutes -> "회의록 완료"
                                    m.recovered -> "비정상 종료 후 복구됨 · '다시 인식' 권장"
                                    else -> "녹취록만 있음"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(96.dp)) }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
}

@Composable
fun ModelRow(spec: ModelSpec, tick: Int) {
    val ready = MeetingEngine.models.isReady(spec)
    val progress = remember(tick) { MeetingEngine.models.progress(spec) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(spec.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("${spec.note} · ${"%.0f".format(spec.sizeBytes / 1e6)}MB", style = MaterialTheme.typography.bodySmall)
            }
            when {
                ready -> Icon(Icons.Default.CheckCircle, "준비됨", tint = MaterialTheme.colorScheme.primary)
                progress != null -> Text("${(progress * 100).toInt()}%")
                else -> OutlinedButton(onClick = { MeetingEngine.models.download(spec) }) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Text(" 받기")
                }
            }
        }
        if (!ready && progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val settings = MeetingEngine.settings
    var llm by remember { mutableStateOf(settings.llm) }
    var threads by remember { mutableIntStateOf(settings.threads) }
    var vocab by remember { mutableStateOf(settings.vocabulary) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val focus = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        // 가로 태블릿에서 키보드가 저장 버튼을 가리지 않도록 키보드 높이만큼 창을 줄임
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        confirmButton = {
            TextButton(onClick = {
                settings.llm = llm; settings.threads = threads; settings.vocabulary = vocab; onDismiss()
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        title = { Text("설정") },
        text = {
            Column(
                Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("용어 사전", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    vocab, { vocab = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("예: 에이전트 하네스, 김민수, 쿠버네티스, OKR") },
                    supportingText = { Text("회사명·인명·전문용어를 쉼표로 구분. 음성인식과 회의록 표기에 반영됩니다.") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                Text("회의록 요약 모델", fontWeight = FontWeight.SemiBold)
                ModelCatalog.llms.forEach { spec ->
                    Row(
                        Modifier.fillMaxWidth().selectable(llm == spec, onClick = { llm = spec }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(llm == spec, onClick = { llm = spec })
                        Column(Modifier.weight(1f)) { ModelRow(spec, tick) }
                    }
                }
                Text("음성인식 모델", fontWeight = FontWeight.SemiBold)
                ModelRow(ModelCatalog.WHISPER, tick)
                ModelRow(ModelCatalog.VAD, tick)
                Text("CPU 스레드: $threads", fontWeight = FontWeight.SemiBold)
                Slider(threads.toFloat(), { threads = it.toInt() }, valueRange = 2f..8f, steps = 5)
                Text("이 기기(Snapdragon 870)는 고성능 코어 4개를 쓰는 4스레드가 가장 빠릅니다.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

fun formatDate(ms: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREAN).format(Date(ms))
