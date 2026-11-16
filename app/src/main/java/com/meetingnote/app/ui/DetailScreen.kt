package com.meetingnote.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.HighQuality
import com.meetingnote.app.ModelCatalog
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.meetingnote.app.MeetingEngine
import com.meetingnote.app.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: String, onBack: () -> Unit, onOpenLive: () -> Unit) {
    val ctx = LocalContext.current
    val engine by MeetingEngine.state.collectAsState()
    val repo = MeetingEngine.repo
    var version by remember { mutableIntStateOf(0) }
    val meeting = remember(id, engine.phase, version) { repo.get(id) }
    val minutes = remember(id, engine.phase, version) { repo.loadMinutes(id) }
    val transcript = remember(id, engine.phase) { repo.loadTranscript(id) }
    var tab by remember { mutableIntStateOf(if (minutes != null) 0 else 1) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember(minutes) { mutableStateOf(minutes ?: "") }

    if (meeting == null) { onBack(); return }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(meeting.title, maxLines = 1,
                    modifier = Modifier.clickable { renaming = true })
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            actions = {
                IconButton(onClick = {
                    val text = buildString {
                        appendLine("# ${meeting.title}"); appendLine()
                        appendLine(minutes ?: "")
                        appendLine(); appendLine("---"); appendLine("## 녹취록")
                        transcript.forEach { appendLine("[${formatTime(it.startMs)}] ${it.text}") }
                    }
                    ctx.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, meeting.title).putExtra(Intent.EXTRA_TEXT, text),
                        "회의록 공유"
                    ))
                }) { Icon(Icons.Default.Share, "공유") }
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "제목 수정") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "삭제") }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "${formatDate(meeting.createdAt)} · ${formatTime(meeting.durationMs)}" +
                        (meeting.sttModel?.let { " · 인식 ${it.substringBefore(" (")}" } ?: "") + (meeting.modelUsed?.let { " · 요약 ${it.substringBefore(" (")}" } ?: "") + (meeting.stats?.let { "\n$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !engine.busy && transcript.isNotEmpty(),
                    onClick = { MeetingEngine.summarize(id); onOpenLive() },
                ) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Text(if (minutes == null) " 회의록 만들기" else " 회의록 다시 만들기")
                }
                OutlinedButton(
                    enabled = !engine.busy && repo.audioFile(id).exists(),
                    onClick = { MeetingEngine.transcribeFile(id); onOpenLive() },
                ) { Icon(Icons.Default.Refresh, null); Text(" 다시 인식") }
                // 고정밀 다시 인식: 설정이 표준 모델일 때만 별도 버튼 (모델이 없으면 받기 안내)
                if (MeetingEngine.settings.whisper != ModelCatalog.WHISPER_TURBO) {
                    val turboReady = MeetingEngine.models.isReady(ModelCatalog.WHISPER_TURBO)
                    OutlinedButton(
                        enabled = !engine.busy && repo.audioFile(id).exists() && turboReady,
                        onClick = {
                            MeetingEngine.transcribeFile(id, thenSummarize = minutes != null, sttSpec = ModelCatalog.WHISPER_TURBO)
                            onOpenLive()
                        },
                    ) {
                        Icon(Icons.Default.HighQuality, null)
                        Text(if (turboReady) " 고정밀 다시 인식 (약 ${formatTime(meeting.durationMs * 3 / 4)})" else " 고정밀 다시 인식 (설정에서 모델 받기)")
                    }
                }
            }
            val minutesPane: @Composable () -> Unit = {
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                        if (minutes != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (editing) {
                                TextButton(onClick = { editing = false; editText = minutes }) { Text("취소") }
                                Button(onClick = {
                                    repo.saveMinutes(id, editText); editing = false; version++
                                }) { Icon(Icons.Default.Check, null); Text(" 저장") }
                            } else {
                                TextButton(enabled = !engine.busy, onClick = { editing = true }) {
                                    Icon(Icons.Default.Edit, null); Text(" 회의록 편집")
                                }
                            }
                        }
                        if (editing) {
                            OutlinedTextField(editText, { editText = it }, Modifier.fillMaxWidth(), minLines = 12)
                        } else {
                            SelectionContainer { MarkdownText(minutes ?: "아직 회의록이 없습니다. '회의록 만들기'를 누르세요.") }
                        }
                    }
            }
            val transcriptPane: @Composable () -> Unit = {
                    SelectionContainer {
                        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                            items(transcript) { l ->
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
            // 반응형: 넓은 화면은 회의록·녹취록을 나란히, 좁은 화면은 탭으로 전환
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp)) {
                if (maxWidth >= 900.dp) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("회의록", style = MaterialTheme.typography.titleMedium)
                            Card(Modifier.fillMaxSize()) { minutesPane() }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("녹취록 (${transcript.size})", style = MaterialTheme.typography.titleMedium)
                            Card(Modifier.fillMaxSize()) { transcriptPane() }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text("회의록") })
                            Tab(tab == 1, { tab = 1 }, text = { Text("녹취록 (${transcript.size})") })
                        }
                        Card(Modifier.fillMaxSize()) { if (tab == 0) minutesPane() else transcriptPane() }
                    }
                }
            }
        }
    }

    if (renaming) {
        var t by remember { mutableStateOf(meeting.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("제목 수정") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = t.isNotBlank(), onClick = {
                    repo.save(meeting.copy(title = t.trim(), titleEdited = true)); renaming = false; version++
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("취소") } },
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("회의 삭제") },
        text = { Text("녹음, 녹취록, 회의록이 모두 삭제됩니다.") },
        confirmButton = { TextButton(onClick = { repo.delete(id); confirmDelete = false; onBack() }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
    )
}

/** 회의록용 간단한 마크다운 렌더러 (제목, 글머리표, 굵게) */
@Composable
fun MarkdownText(md: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("#") -> Text(
                    inlineBold(line.trimStart('#').trim()),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp),
                )
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val indent = (line.length - line.trimStart().length) / 2
                    Row(Modifier.padding(start = (indent * 16).dp)) {
                        Text("•  ")
                        Text(inlineBold(line.trimStart().substring(2)))
                    }
                }
                line.isBlank() -> {}
                line.trim() == "---" -> HorizontalDivider()
                else -> Text(inlineBold(line))
            }
        }
    }
}

private fun inlineBold(s: String): AnnotatedString = buildAnnotatedString {
    val parts = s.split("**")
    parts.forEachIndexed { i, p ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p)
    }
}
