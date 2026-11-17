package com.meetingnote.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meetingnote.app.SummaryUi
import com.meetingnote.app.TranscriptLine
import com.meetingnote.app.formatTime
import kotlinx.coroutines.delay

private val STEPS = listOf("모델 준비", "녹취 분석", "회의록 작성", "제목 정하기")

fun etaText(sec: Int?): String = when {
    sec == null -> "남은 시간 계산 중"
    sec < 10 -> "곧 끝납니다"
    sec < 60 -> "약 ${(sec + 9) / 10 * 10}초 남음"
    else -> "약 ${(sec + 30) / 60}분 남음"
}

/** 진행 중 화면 상단: 진행률 · 남은 시간 · 경과 · 단계 표시 */
@Composable
fun SummaryProgressCard(su: SummaryUi?) {
    val s = su ?: SummaryUi()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val frac by animateFloatAsState(s.fraction ?: 0f, tween(600), label = "frac")

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.fraction == null) "—" else "${(frac * 100).toInt()}%",
                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(s.detail, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (s.fraction == null) "" else etaText(s.etaSec) + " · ") + "경과 ${formatTime(now - s.startedAt)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (s.fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
        else LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(8.dp))
        Stepper(s.phase)
    }
}

/** 4단계 표시: 끝난 단계 ✓, 진행 중인 단계는 숨쉬듯 깜빡임 */
@Composable
private fun Stepper(active: Int) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        STEPS.forEachIndexed { i, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 왼쪽 연결선
                    Box(Modifier.weight(1f).height(2.dp).background(if (i == 0) Color.Transparent else if (i <= active) c.primary else c.outlineVariant))
                    val done = i < active
                    val cur = i == active
                    val bg by animateColorAsState(if (done || cur) c.primary else c.surface, label = "bg")
                    Box(
                        Modifier.size(28.dp).scale(if (cur) scale else 1f)
                            .background(bg, CircleShape)
                            .border(2.dp, if (done || cur) c.primary else c.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = c.onPrimary)
                        else Text("${i + 1}", style = MaterialTheme.typography.labelMedium,
                            color = if (cur) c.onPrimary else c.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.weight(1f).height(2.dp).background(if (i == STEPS.lastIndex) Color.Transparent else if (i < active) c.primary else c.outlineVariant))
                }
                Spacer(Modifier.height(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    color = if (i <= active) c.onSurface else c.onSurfaceVariant,
                    fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/**
 * 녹취록을 읽는 동안의 연출: 지금 읽고 있는 구간의 문장들을 보여 주고,
 * 읽은 문장은 진하게, 현재 문장은 강조, 아직 읽지 않은 문장은 흐리게 표시하며 따라 내려간다.
 */
@Composable
fun ReadingView(lines: List<TranscriptLine>, su: SummaryUi?, modifier: Modifier = Modifier) {
    val range = su?.readLines?.takeIf { !it.isEmpty() && it.last < lines.size }
    val shown = if (range != null) lines.subList(range.first, range.last + 1) else emptyList()
    val c = MaterialTheme.colorScheme
    if (shown.isEmpty()) {
        // 구간 메모를 모아 읽는 중 등 표시할 원문이 없을 때: 물결처럼 움직이는 자리 표시줄
        val t = rememberInfiniteTransition(label = "shimmer")
        val a by t.animateFloat(0.25f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0.9f, 0.75f, 0.95f, 0.6f, 0.85f, 0.7f).forEachIndexed { i, w ->
                Box(Modifier.fillMaxWidth(w).height(14.dp).alpha(if (i % 2 == 0) a else 1.05f - a)
                    .background(c.surfaceVariant, RoundedCornerShape(7.dp)))
            }
        }
        return
    }
    val pos = ((su?.readFraction ?: 0f) * shown.size).toInt().coerceIn(0, shown.lastIndex)
    val state = rememberLazyListState()
    LaunchedEffect(pos) { state.animateScrollToItem((pos - 2).coerceAtLeast(0)) }
    LazyColumn(modifier, state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(shown) { i, l ->
            val read = i < pos
            val cur = i == pos
            val bg by animateColorAsState(if (cur) c.primaryContainer else Color.Transparent, label = "lbg")
            Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(formatTime(l.startMs), Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall,
                    color = c.primary.copy(alpha = if (read || cur) 1f else 0.4f))
                Text(l.text, style = MaterialTheme.typography.bodyMedium,
                    color = when { cur -> c.onPrimaryContainer; read -> c.onSurface; else -> c.onSurface.copy(alpha = 0.35f) },
                    fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** 글을 쓰는 중임을 보여 주는 깜빡이는 커서 */
@Composable
fun rememberCursor(): String {
    val t = rememberInfiniteTransition(label = "cursor")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Restart), label = "c")
    return if (v < 0.5f) " ▍" else "  "
}
