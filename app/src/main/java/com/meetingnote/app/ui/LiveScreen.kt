package com.meetingnote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meetingnote.app.MeetingEngine
import com.meetingnote.app.Phase
import com.meetingnote.app.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(onDone: (String?) -> Unit, onBack: () -> Unit) {
    val s by MeetingEngine.state.collectAsState()
    var paused by remember { mutableStateOf(MeetingEngine.isPaused) }
    var wasBusy by remember { mutableStateOf(false) }

    LaunchedEffect(s.phase) {
        if (s.busy) wasBusy = true
        else if (wasBusy && s.phase == Phase.IDLE) onDone(s.meetingId)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(
                    when (s.phase) {
                        Phase.LOADING -> "준비 중"
                        Phase.RECORDING -> if (paused) "일시정지" else "녹음 중"
                        Phase.FINISHING_STT, Phase.TRANSCRIBING_FILE -> "음성 인식 중"
                        Phase.SUMMARIZING -> "회의록 작성 중"
                        Phase.ERROR -> "오류"
                        Phase.IDLE -> "완료"
                    }
                )
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { pad ->
        // 상태(타이머·오실로스코프·버튼)
        var wideLayout = false
        val statusCard: @Composable () -> Unit = {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(s.elapsedMs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(16.dp))
                        if (s.busy && s.phase != Phase.RECORDING) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(s.message ?: s.summaryStage, Modifier.weight(1f))
                        }
                    }
                    if (s.phase == Phase.RECORDING) {
                        val scope by MeetingEngine.scopeData.collectAsState()
                        ScopeView(scope, paused, Modifier.fillMaxWidth(), crtHeight = if (wideLayout) 400.dp else 240.dp)
                    }
                    val rtf = s.lastRtf?.let { "인식 속도: 실제 시간의 %.2f배".format(it) } ?: "인식 속도 측정 중"
                    Text(
                        "$rtf · 인식 대기 ${formatTime(s.sttBacklogMs)} · 문장 ${s.lines.size}개",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s.phase == Phase.ERROR) {
                        Text(s.message ?: "", color = MaterialTheme.colorScheme.error)
                        var reporting by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { reporting = true }) { Text("오류 보고") }
                        if (reporting) ErrorReportDialog(engineError = s.message ?: "오류", onDismiss = { reporting = false })
                    }

                    if (s.phase == Phase.RECORDING) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { paused = !paused; MeetingEngine.setPaused(paused) }) {
                            Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                            Text(if (paused) " 계속" else " 일시정지")
                        }
                        Button(
                            onClick = { MeetingEngine.stopRecording(autoSummarize = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Default.Stop, null); Text(" 종료하고 회의록 만들기")
                        }
                    }
                    if (s.phase == Phase.SUMMARIZING) OutlinedButton(onClick = { MeetingEngine.cancel() }) { Text("요약 중단") }
                }
            }
        }
        // 녹취록 또는 생성 중인 회의록
        val contentPane: @Composable ColumnScope.() -> Unit = {
            if (s.phase == Phase.SUMMARIZING) {
                Text("회의록 (생성 중)", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(s.summaryText.length) { scroll.animateScrollTo(scroll.maxValue) }
                    Column(Modifier.padding(16.dp).verticalScroll(scroll)) {
                        MarkdownText(s.summaryText.ifEmpty { "모델이 녹취록을 읽는 중입니다…" })
                    }
                }
            } else {
                Text("실시간 녹취록", style = MaterialTheme.typography.titleMedium)
                val listState = rememberLazyListState()
                LaunchedEffect(s.lines.size) { if (s.lines.isNotEmpty()) listState.animateScrollToItem(s.lines.size - 1) }
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                        if (s.lines.isEmpty()) item {
                            Text("말하기 시작하면 약 30초 단위로 텍스트가 나타납니다.", style = MaterialTheme.typography.bodyMedium)
                        }
                        items(s.lines) { l ->
                            Row(Modifier.padding(vertical = 4.dp)) {
                                Text(formatTime(l.startMs), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(56.dp))
                                Text(l.text)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        // 반응형: 가로로 넓으면(태블릿 가로) 좌우 2단, 좁으면(휴대폰·세로) 위아래
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            wideLayout = maxWidth >= 840.dp
            if (wideLayout) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState())) { statusCard() }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) { contentPane() }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    statusCard()
                    contentPane()
                }
            }
        }
    }
}
