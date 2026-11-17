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
    }
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
