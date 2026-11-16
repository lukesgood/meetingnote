package com.meetingnote.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meetingnote.app.Meeting
import com.meetingnote.app.formatTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 홈 목록의 회의 카드: 상태 배지 · 시각/길이 · 제목 · 회의 요약 미리보기 */
@Composable
fun MeetingCard(m: Meeting, preview: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 132.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(m)
                Spacer(Modifier.weight(1f))
                Text(
                    "${SimpleDateFormat("HH:mm", Locale.KOREAN).format(Date(m.createdAt))} · ${formatTime(m.durationMs)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    preview.isNotBlank() -> preview
                    m.recovered -> "녹음 중 앱이 종료되어 복구된 회의입니다. '다시 인식'을 권장합니다."
                    m.hasMinutes -> "회의록이 있습니다."
                    else -> "아직 회의록을 만들지 않았습니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusBadge(m: Meeting) {
    val c = MaterialTheme.colorScheme
    val (icon, label, colors) = when {
        m.recovered -> Triple(Icons.Default.WarningAmber, "복구됨", c.errorContainer to c.onErrorContainer)
        m.hasMinutes -> Triple(Icons.Default.Description, "회의록", c.tertiaryContainer to c.onTertiaryContainer)
        else -> Triple(Icons.Default.Mic, "녹취록만", c.secondaryContainer to c.onSecondaryContainer)
    }
    Badge(icon, label, colors.first, colors.second)
}

@Composable
fun Badge(icon: ImageVector?, label: String, bg: Color, fg: Color) {
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 회의록의 '회의 요약' 단락을 한 줄 미리보기로 (마크다운 기호 제거) */
fun summaryPreview(md: String): String {
    val lines = md.lines()
    val start = lines.indexOfFirst { it.trimStart().startsWith("#") && it.contains("요약") }
    val body = if (start >= 0) lines.drop(start + 1).takeWhile { !it.trimStart().startsWith("#") }
               else lines.filterNot { it.trimStart().startsWith("#") }
    return body.joinToString(" ") { it.trim().removePrefix("- ").removePrefix("* ") }
        .replace("**", "").replace(Regex("\\s+"), " ").trim()
}

/** 날짜 묶음 이름: 오늘 · 어제 · 이번 주 · 그 이전은 월 단위 */
fun dateGroup(ms: Long, now: Long = System.currentTimeMillis()): String {
    fun dayStart(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = ((dayStart(now) - dayStart(ms)) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "오늘"
        days == 1 -> "어제"
        days < 7 -> "이번 주"
        else -> SimpleDateFormat("yyyy년 M월", Locale.KOREAN).format(Date(ms))
    }
}
