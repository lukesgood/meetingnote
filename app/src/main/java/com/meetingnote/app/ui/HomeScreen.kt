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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.DialogProperties
import com.meetingnote.app.Meeting
import com.meetingnote.app.ErrorReporter
import androidx.compose.material3.Button
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
fun HomeScreen(onRecord: () -> Unit, onOpen: (String) -> Unit, onOpenLive: () -> Unit, onOpenSettings: () -> Unit) {
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
    // 오류 보고: 지난 실행의 비정상 종료 / 처리 오류 / 직접 보고
    var pendingCrash by remember { mutableStateOf(ErrorReporter.pendingCrash()) }
    var report by remember { mutableStateOf<ReportRequest?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.phase, tick) { meetings = MeetingEngine.repo.list() }
    // 검색과 회의 요약 미리보기
    var query by remember { mutableStateOf("") }
    var previews by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(meetings) {
        previews = withContext(Dispatchers.IO) {
            meetings.associate { m -> m.id to (MeetingEngine.repo.loadMinutes(m.id)?.let(::summaryPreview) ?: "") }
        }
    }
    val shown = remember(meetings, previews, query) {
        val q = query.trim()
        if (q.isEmpty()) meetings
        else meetings.filter { it.title.contains(q, true) || (previews[it.id] ?: "").contains(q, true) }
    }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }   // 다운로드 진행률 갱신

    val requiredMissing = (listOf(MeetingEngine.settings.whisper, ModelCatalog.VAD, MeetingEngine.settings.llm))
        .filterNot { MeetingEngine.models.isReady(it) }
        .also { tick }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("회의노트", fontWeight = FontWeight.Bold) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(
                        enabled = !state.busy,
                        onClick = { importLauncher.launch(arrayOf("audio/*", "video/*")) },
                    ) { Icon(Icons.Default.UploadFile, "음성 파일 가져오기") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "설정") }
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
        // 반응형: 화면 너비에 따라 회의 카드를 여러 열로 배치 (휴대폰 1열, 태블릿 가로 3~4열)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            // 마지막 항목이 '회의 녹음 시작' 버튼에 가리지 않도록 하단 여백
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
        ) {
            if (state.busy) item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenLive),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            when (state.phase) {
                                Phase.RECORDING -> "● 녹음 중 ${formatTime(state.elapsedMs)}"
                                Phase.SUMMARIZING -> state.summary?.let { su ->
                                    su.fraction?.let { f -> "회의록 작성 중 · ${(f * 100).toInt()}% · ${etaText(su.etaSec)}" }
                                } ?: "회의록 작성 중 · ${state.summaryStage}"
                                else -> state.message ?: "처리 중…"
                            }, fontWeight = FontWeight.SemiBold
                        )
                        Text("눌러서 진행 화면 보기", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            pendingCrash?.let { c ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("지난 실행에서 앱이 비정상 종료됐습니다", fontWeight = FontWeight.SemiBold)
                                Text("${c.kind}. 오류 보고를 보내 주시면 개선에 도움이 됩니다.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { ErrorReporter.markCrashSeen(); pendingCrash = null }) { Text("닫기") }
                            Button(onClick = {
                                report = ReportRequest(crash = c); ErrorReporter.markCrashSeen(); pendingCrash = null
                            }) { Text("보고하기") }
                        }
                    }
                }
            }
            if (!state.busy) item(span = { GridItemSpan(maxLineSpan) }, key = "device_check") {
                DeviceCheckBanner(tick, onOpenSettings = onOpenSettings, onDismissed = {})
            }
            if (state.phase == Phase.ERROR) item(span = { GridItemSpan(maxLineSpan) }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.message ?: "오류", Modifier.weight(1f))
                        TextButton(onClick = { report = ReportRequest(engineError = state.message ?: "오류") }) { Text("오류 보고") }
                        TextButton(onClick = { MeetingEngine.clearError() }) { Text("닫기") }
                    }
                }
            }
            if (requiredMissing.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
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
            // 헤더: 통계 + 검색
            item(span = { GridItemSpan(maxLineSpan) }) {
                val totalMin = meetings.sumOf { it.durationMs } / 60_000
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("회의 기록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "회의 ${meetings.size}개 · 총 " + (if (totalMin >= 60) "${totalMin / 60}시간 ${totalMin % 60}분" else "${totalMin}분") + " 녹음",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (meetings.isNotEmpty()) OutlinedTextField(
                        query, { query = it },
                        Modifier.widthIn(min = 200.dp, max = 420.dp),
                        placeholder = { Text("제목·요약 검색") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "지우기") } },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                    )
                }
            }
            if (meetings.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Mic, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("아직 회의 기록이 없습니다", style = MaterialTheme.typography.titleMedium)
                    Text("'회의 녹음 시작'을 누르거나, 위의 가져오기 버튼으로 음성 파일을 불러오세요.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("'$query'에 해당하는 회의가 없습니다.", Modifier.padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 날짜별 묶음
            shown.groupBy { dateGroup(it.createdAt) }.forEach { (group, list) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "g_$group") {
                    Text(group, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                items(list, key = { it.id }) { m ->
                    MeetingCard(m, previews[m.id] ?: "", onClick = { onOpen(m.id) })
                }
            }
        }
    }

    report?.let { r -> ErrorReportDialog(r.crash, r.engineError, onDismiss = { report = null }) }
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

data class ReportRequest(val crash: ErrorReporter.Crash? = null, val engineError: String? = null)

fun formatDate(ms: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREAN).format(Date(ms))
