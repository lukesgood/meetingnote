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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextAlign
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
                        Phase.REFINING -> "정밀 인식 중"
                        Phase.SUMMARIZING -> "회의록 작성 중"
                        Phase.ERROR -> "오류"
                        Phase.IDLE -> "완료"
                    }
                )
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { pad ->
        // 반응형 기준
        //  wide: 태블릿 가로(840dp+) 또는 가로로 돌린 휴대폰 → 좌(상태) | 우(녹취록) 2단
        //  narrow: 휴대폰 세로 → 위(상태, 최대 화면 절반·스크롤) / 녹취록 / 하단 고정 버튼
        //  compact: 휴대폰 폭(480dp 미만) — 오실로스코프를 줄이고 버튼 문구를 짧게
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            val wideLayout = maxWidth >= 840.dp || (maxWidth > maxHeight && maxWidth >= 560.dp)
            val compact = maxWidth < 480.dp || maxHeight < 560.dp
            val shortScreen = maxHeight < 560.dp

            // 상태(타이머·진행률·오실로스코프). 버튼은 controls로 분리해 항상 보이게 함
            val statusCard: @Composable () -> Unit = {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(if (compact) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.phase == Phase.SUMMARIZING) SummaryProgressCard(s.summary)
                        else Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatTime(s.elapsedMs),
                                style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(16.dp))
                            if (s.busy && s.phase != Phase.RECORDING) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(s.message ?: s.summaryStage, Modifier.weight(1f))
                            }
                        }
                        s.refineFraction?.let { f ->
                            val anim by androidx.compose.animation.core.animateFloatAsState(f, label = "refine")
                            androidx.compose.material3.LinearProgressIndicator(progress = { anim }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "녹음 전체를 한 번에 인식해 정확한 녹취록을 만드는 중입니다." +
                                    (s.refineEtaSec?.let { e -> " 남은 시간 약 ${if (e < 60) "${e}초" else "${e / 60}분"}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (s.phase == Phase.RECORDING) {
                            val scope by MeetingEngine.scopeData.collectAsState()
                            ScopeView(scope, paused, Modifier.fillMaxWidth(),
                                crtHeight = when {
                                    shortScreen -> 90.dp
                                    s.interpreting -> if (compact) 90.dp else 170.dp
                                    wideLayout -> 400.dp
                                    compact -> 120.dp
                                    else -> 240.dp
                                },
                                compact = compact)
                        }
                        val model = if (s.phase != Phase.RECORDING) ""
                            else if (MeetingEngine.liveSpec == com.meetingnote.app.ModelCatalog.REFINER) "정밀 모델 · " else "기본 모델 · "
                        val rtf = model + (s.lastRtf?.let { "인식 속도: 실제 시간의 %.2f배".format(it) } ?: "인식 속도 측정 중")
                        if (s.phase != Phase.SUMMARIZING && !(s.phase == Phase.RECORDING && !s.liveText)) Text(
                            "$rtf · 인식 대기 ${formatTime(s.sttBacklogMs)} · 문장 ${s.lines.size}개" +
                                (if (s.interpreting) " · 번역 대기 ${s.translatePending}문장" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (s.phase == Phase.RECORDING) s.liveNotice?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (s.phase == Phase.ERROR) {
                            Text(s.message ?: "", color = MaterialTheme.colorScheme.error)
                            var reporting by remember { mutableStateOf(false) }
                            OutlinedButton(onClick = { reporting = true }) { Text("오류 보고") }
                            if (reporting) ErrorReportDialog(engineError = s.message ?: "오류", onDismiss = { reporting = false })
                        }
                    }
                }
            }
            // 녹음 제어 버튼: 어떤 화면 크기에서도 잘리지 않도록 스크롤 영역 밖에 고정
            val controls: @Composable () -> Unit = {
                if (s.phase == Phase.RECORDING) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { paused = !paused; MeetingEngine.setPaused(paused) }) {
                        Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                        Text(if (paused) " 계속" else " 일시정지", maxLines = 1)
                    }
                    Button(
                        onClick = { MeetingEngine.stopRecording(autoSummarize = true) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Text(if (compact) " 회의 종료" else " 종료하고 회의록 만들기", maxLines = 1)
                    }
                }
                if (s.phase == Phase.SUMMARIZING) OutlinedButton(onClick = { MeetingEngine.cancel() }) { Text("요약 중단") }
            }
            // 녹취록 또는 생성 중인 회의록
            val contentPane: @Composable ColumnScope.() -> Unit = {
                if (s.phase == Phase.SUMMARIZING) {
                    val su = s.summary
                    val reading = s.summaryText.isEmpty() && su?.writing != true
                    Text(
                        when {
                            su == null || su.phase == 0 -> "요약 모델 준비 중"
                            reading -> if (su.readLines != null) "녹취록 읽는 중" else "구간 메모 모아 읽는 중"
                            su.phase == 2 -> "회의록 쓰는 중"
                            su.phase == 3 -> "회의록 완성 · 제목 정하는 중"
                            else -> "구간 메모 정리 중"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Card(Modifier.fillMaxWidth().weight(1f)) {
                        if (reading) ReadingView(s.lines, su, Modifier.fillMaxWidth())
                        else {
                            val scroll = rememberScrollState()
                            LaunchedEffect(s.summaryText.length) { scroll.animateScrollTo(scroll.maxValue) }
                            val cursor = if (su?.writing == true) rememberCursor() else ""
                            Column(Modifier.padding(16.dp).verticalScroll(scroll)) {
                                MarkdownText(s.summaryText + cursor)
                            }
                        }
                    }
                } else if (s.phase == Phase.REFINING && s.summaryText.isNotBlank()) {
                    // 회의록 먼저 보기: 정밀 인식이 도는 동안 먼저 만든 회의록을 보여 줌
                    Text("먼저 만든 회의록 · 정밀 인식이 끝나면 다시 작성합니다", style = MaterialTheme.typography.titleMedium)
                    Card(Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.padding(if (compact) 12.dp else 16.dp).verticalScroll(rememberScrollState())) {
                            MarkdownText(s.summaryText)
                        }
                    }
                } else if (s.interpreting) {
                    InterpretPane(s.lines, s.translatePending, compact = compact)
                } else if ((s.phase == Phase.RECORDING || s.phase == Phase.LOADING) && !s.liveText) {
                    // 실시간 녹취록 없음 (설정에서 끔, 또는 인식이 밀려 멈춤): 녹음만 하고 회의 후 녹음 전체를 인식
                    Card(Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("녹음 중입니다", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("회의가 끝나면 녹음 전체로 정확한 녹취록과 회의록을 만듭니다." +
                                (if (s.lines.isNotEmpty()) "\n(멈추기 전까지 ${s.lines.size}문장 인식)" else ""),
                                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text("실시간 녹취록", style = MaterialTheme.typography.titleMedium)
                    val listState = rememberLazyListState()
                    LaunchedEffect(s.lines.size) { if (s.lines.isNotEmpty()) listState.animateScrollToItem(s.lines.size - 1) }
                    Card(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(if (compact) 12.dp else 16.dp)) {
                            if (s.lines.isEmpty()) item {
                                Text("말하기 시작하면 문장이 끝날 때마다(보통 5~15초) 텍스트가 나타납니다.", style = MaterialTheme.typography.bodyMedium)
                            }
                            items(s.lines) { l ->
                                TranscriptRow(l, showTranslation = s.interpreting)
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            if (wideLayout) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 통역 중에는 자막 영역을 넓게 (상태 1 : 통역 1.8)
                    Column(Modifier.weight(if (s.interpreting) 1f else 1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { statusCard() }
                        controls()
                    }
                    Column(Modifier.weight(if (s.interpreting) 1.8f else 1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) { contentPane() }
                }
            } else {
                val statusMax = maxHeight * (if (s.phase == Phase.SUMMARIZING) 0.5f else 0.45f)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                    Column(Modifier.heightIn(max = statusMax).verticalScroll(rememberScrollState())) { statusCard() }
                    contentPane()
                    controls()
                }
            }
        }
    }
}
