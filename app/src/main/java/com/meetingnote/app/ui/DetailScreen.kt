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
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Event
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.meetingnote.app.MeetingEngine
import com.meetingnote.app.formatTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(id: String, onBack: () -> Unit, onOpenLive: () -> Unit) {
    val ctx = LocalContext.current
    val engine by MeetingEngine.state.collectAsState()
    val repo = MeetingEngine.repo
    var version by remember { mutableIntStateOf(0) }
    val meeting = remember(id, engine.phase, version) { repo.get(id) }
    val minutes = remember(id, engine.phase, version) { repo.loadMinutes(id) }
    val transcript = remember(id, engine.phase) { repo.loadTranscript(id) }
    val hasTranslation = transcript.any { it.translation != null || it.lang != null }
    var tab by remember { mutableIntStateOf(if (minutes != null) 0 else 1) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember(minutes) { mutableStateOf(minutes ?: "") }

    if (meeting == null) { onBack(); return }

    Scaffold(topBar = {
        TopAppBar(
            title = {},
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            actions = {
                IconButton(onClick = {
                    val text = buildString {
                        appendLine("# ${meeting.title}"); appendLine()
                        appendLine(minutes ?: "")
                        appendLine(); appendLine("---"); appendLine("## 녹취록")
                        transcript.forEach {
                            appendLine("[${formatTime(it.startMs)}] ${it.text}")
                            it.translation?.let { tr -> appendLine("    → $tr") }
                        }
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
            // 헤더: 제목(눌러서 수정) + 정보 배지
            Text(
                meeting.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clickable { renaming = true },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val c = MaterialTheme.colorScheme
                Badge(Icons.Default.Event, formatDate(meeting.createdAt), c.surfaceContainerHigh, c.onSurface)
                Badge(Icons.Default.Schedule, formatTime(meeting.durationMs), c.surfaceContainerHigh, c.onSurface)
                meeting.sttModel?.let { Badge(Icons.Default.GraphicEq, "인식 " + it.substringBefore(" ("), c.surfaceContainerHigh, c.onSurface) }
                meeting.modelUsed?.let { Badge(Icons.Default.AutoAwesome, "요약 " + it.substringBefore(" ("), c.surfaceContainerHigh, c.onSurface) }
                meeting.stats?.let { Badge(null, it, c.surfaceContainerHigh, c.onSurfaceVariant) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                TextButton(onClick = {
                                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                                        .setPrimaryClip(android.content.ClipData.newPlainText(meeting.title, minutes))
                                }) { Icon(Icons.Default.ContentCopy, null); Text(" 복사") }
                                TextButton(enabled = !engine.busy, onClick = { editing = true }) {
                                    Icon(Icons.Default.Edit, null); Text(" 편집")
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
                                TranscriptRow(l, showTranslation = hasTranslation)
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
                            Card(Modifier.fillMaxSize(), colors = paneColors(), border = paneBorder()) { minutesPane() }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("녹취록 (${transcript.size})", style = MaterialTheme.typography.titleMedium)
                            Card(Modifier.fillMaxSize(), colors = paneColors(), border = paneBorder()) { transcriptPane() }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text("회의록") })
                            Tab(tab == 1, { tab = 1 }, text = { Text("녹취록 (${transcript.size})") })
                        }
                        Card(Modifier.fillMaxSize(), colors = paneColors(), border = paneBorder()) { if (tab == 0) minutesPane() else transcriptPane() }
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

@Composable
private fun paneColors() = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

@Composable
private fun paneBorder() = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

/** 회의록용 마크다운 렌더러: 섹션 제목(강조 막대), 글머리표, 굵게 */
@Composable
fun MarkdownText(md: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var first = true
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("#") -> {
                    Row(
                        Modifier.padding(top = if (first) 0.dp else 14.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(width = 4.dp, height = 20.dp)
                                .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(inlineBold(line.trimStart('#').trim()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val indent = (line.length - line.trimStart().length) / 2
                    Row(Modifier.padding(start = (14 + indent * 16).dp)) {
                        Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(16.dp), style = MaterialTheme.typography.bodyLarge)
                        Text(inlineBold(line.trimStart().substring(2)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                line.isBlank() -> {}
                line.trim() == "---" -> HorizontalDivider()
                else -> Text(inlineBold(line), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 14.dp))
            }
            if (line.isNotBlank()) first = false
        }
    }
}

private fun inlineBold(s: String): AnnotatedString = buildAnnotatedString {
    val parts = s.split("**")
    parts.forEachIndexed { i, p ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p)
    }
}
