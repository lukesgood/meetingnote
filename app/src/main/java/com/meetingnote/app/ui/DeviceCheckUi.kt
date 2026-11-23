package com.meetingnote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meetingnote.app.DeviceCheck
import com.meetingnote.app.DeviceCheck.Level
import com.meetingnote.app.MeetingEngine
import com.meetingnote.app.ResultReporter
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun levelColor(l: Level): Color = when (l) {
    Level.OK -> MaterialTheme.colorScheme.tertiary
    Level.SLOW -> Color(0xFFC77700)
    Level.NO -> MaterialTheme.colorScheme.error
    Level.UNKNOWN -> MaterialTheme.colorScheme.outline
}

@Composable
fun CheckRow(item: DeviceCheck.Item) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            when (item.level) {
                Level.OK -> Icons.Default.CheckCircle
                Level.SLOW -> Icons.Default.WarningAmber
                Level.NO -> Icons.Default.Error
                Level.UNKNOWN -> Icons.Default.HelpOutline
            }, null, Modifier.padding(top = 2.dp).size(20.dp), tint = levelColor(item.level),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 설정 화면: 기기 점검 결과와 실측 버튼 */
@Composable
fun DeviceCheckCard(tick: Int, busy: Boolean) {
    val checking by MeetingEngine.checking.collectAsState()
    // 점검이 끝나면(checking → null) 다시 계산
    val items = remember(tick / 4, checking) { DeviceCheck.evaluate() }
    val bench = remember(checking) { DeviceCheck.bench }
    SectionCard(Icons.Default.Speed, "기기 점검", "이 기기에서 각 기능이 원활히 동작하는지 확인합니다.") {
        items.forEach { CheckRow(it) }
        val c = checking
        if (c != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Hint(c)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Hint(
                        bench?.let {
                            "마지막 실측: ${SimpleDateFormat("M월 d일 HH:mm", Locale.KOREAN).format(Date(it.at))} (스레드 ${it.threads})" +
                                if (it.threads != MeetingEngine.settings.threads) " — 스레드 설정이 바뀌었으니 다시 점검하세요." else ""
                        } ?: "실측은 내려받은 모델로 약 30초 동안 실제 처리 속도를 잽니다."
                    )
                }
                Button(onClick = { MeetingEngine.runDeviceCheck() }, enabled = !busy) {
                    Text(if (bench == null) "점검 실행" else "다시 점검")
                }
            }
        }
        Hint("예상 시간은 기준 기기(Snapdragon 870) 실측과 비교한 추정치입니다.")
        if (ResultReporter.enabled && bench != null && c == null) {
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = true }) { Text("결과 보내기") }
            if (open) SendResultDialog(onDismiss = { open = false })
        }
    }
}

/** 점검 결과 보내기: 보낼 내용을 그대로 보여 주고, 동의하면 익명으로 제출 */
@Composable
private fun SendResultDialog(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val report = remember { ResultReporter.build(ctx) }
    var sending by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(if (done == null) "점검 결과 보내기" else "결과 보내기") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (done != null) Text(done!!)
                else {
                    Text("앱 개선을 위해 아래 기기 정보와 속도 측정값을 익명으로 보냅니다. " +
                        "이름·계정·녹음·녹취록·회의록 등 회의 내용은 보내지 않습니다.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("기기: ${report.device}\n칩: ${report.chip}\n앱 버전: ${report.version}\n\n${report.result}",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            if (done == null) Button(enabled = !sending, onClick = {
                sending = true
                scope.launch {
                    done = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { ResultReporter.send(report) }
                            .fold({ "보냈습니다. 고맙습니다!" }, { "보내지 못했습니다: ${it.message ?: it}\n인터넷 연결을 확인한 뒤 다시 시도해 주세요." })
                    }
                    sending = false
                }
            }) { Text(if (sending) "보내는 중…" else "보내기") }
            else TextButton(onClick = onDismiss) { Text("닫기") }
        },
        dismissButton = { if (done == null) TextButton(enabled = !sending, onClick = onDismiss) { Text("취소") } },
    )
}

/** 홈 화면: 문제가 있거나 아직 점검하지 않았을 때만 표시 */
@Composable
fun DeviceCheckBanner(tick: Int, onOpenSettings: () -> Unit, onDismissed: () -> Unit) {
    val checking by MeetingEngine.checking.collectAsState()
    var closed by remember { mutableIntStateOf(0) }
    // 약 15초마다 다시 계산 (저장 공간·모델 다운로드 상태 반영)
    val problems = remember(tick / 10, checking, closed) { DeviceCheck.problems() }
    val suggest = remember(tick / 10, checking, closed) { DeviceCheck.shouldSuggestBench() }
    // 홈에서 점검을 마치면 결과를 닫을 때까지 보여 줌
    var wasChecking by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }
    LaunchedEffect(checking) {
        if (checking != null) wasChecking = true
        else if (wasChecking) { wasChecking = false; showResult = DeviceCheck.bench != null }
    }
    val showProblems = problems.isNotEmpty() && !DeviceCheck.isDismissed(problems)
    if (showResult && checking == null) {
        val items = remember(closed) { DeviceCheck.evaluate().filter { it.title.startsWith("실시간") || it.title.startsWith("회의록") } }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (problems.isEmpty()) "기기 점검 완료 · 모든 기능을 원활히 쓸 수 있습니다" else "기기 점검 완료",
                    fontWeight = FontWeight.SemiBold)
                items.forEach { CheckRow(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenSettings) { Text("자세히") }
                    TextButton(onClick = {
                        showResult = false
                        if (problems.isNotEmpty()) DeviceCheck.dismiss(problems)   // 방금 본 문제는 다시 띄우지 않음
                        closed++; onDismissed()
                    }) { Text("닫기") }
                }
            }
        }
        return
    }
    if (!showProblems && !suggest && checking == null) return
    val warn = showProblems && problems.any { it.level == Level.NO }
    Card(colors = CardDefaults.cardColors(
        containerColor = if (showProblems) MaterialTheme.colorScheme.errorContainer.copy(alpha = if (warn) 1f else 0.6f)
                         else MaterialTheme.colorScheme.secondaryContainer,
    )) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                checking != null -> {
                    Text("기기 점검 중…", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(checking ?: "", style = MaterialTheme.typography.bodySmall)
                }
                showProblems -> {
                    Text(if (warn) "이 기기에서 일부 기능을 쓰기 어렵습니다" else "이 기기에서 일부 기능이 느릴 수 있습니다",
                        fontWeight = FontWeight.SemiBold)
                    problems.forEach { CheckRow(it) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { DeviceCheck.dismiss(problems); closed++; onDismissed() }) { Text("닫기") }
                        TextButton(onClick = onOpenSettings) { Text("자세히") }
                    }
                }
                else -> {
                    Text("이 기기에서 얼마나 빠르게 동작할지 확인해 보세요", fontWeight = FontWeight.SemiBold)
                    Text("약 30초 동안 실제 음성인식·요약 속도를 재서 녹취 지연, 통역, 회의록 작성 시간을 알려 드립니다.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { DeviceCheck.dismissSuggestion(); closed++; onDismissed() }) { Text("나중에") }
                        Button(onClick = { MeetingEngine.runDeviceCheck() }, enabled = !MeetingEngine.state.value.busy) { Text("점검 실행") }
                    }
                }
            }
        }
    }
}
