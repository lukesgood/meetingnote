package com.meetingnote.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetingnote.app.MeetingEngine
import com.meetingnote.app.TranscriptLine

/**
 * 통역 모드 화면: 맨 위에 최신 문장의 번역을 큰 자막으로, 아래에 원문+번역 기록.
 * 글자 크기는 A−/A+로 조절하고 설정에 저장.
 */
@Composable
fun ColumnScope.InterpretPane(lines: List<TranscriptLine>, pending: Int) {
    val settings = MeetingEngine.settings
    var size by remember { mutableIntStateOf(settings.captionSize) }
    fun setSize(v: Int) { size = v.coerceIn(20, 56); settings.captionSize = size }
    val scale = size / 30f

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("실시간 통역", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.padding(start = 8.dp))
        Badge(null, "한국어 ↔ English", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        if (pending > 0) {
            Spacer(Modifier.padding(start = 6.dp))
            Text("번역 대기 $pending", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { setSize(size - 4) }) { Icon(Icons.Default.TextDecrease, "글자 작게") }
        Text("${size}", style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { setSize(size + 4) }) { Icon(Icons.Default.TextIncrease, "글자 크게") }
    }

    // 최신 자막: 번역이 있는 가장 최근 문장 (없으면 최근 원문)
    val latest = lines.lastOrNull { it.translation != null } ?: lines.lastOrNull()
    Card(
        Modifier.fillMaxWidth().heightIn(min = (size * 4.2f).dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        AnimatedContent(latest, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "caption") { l ->
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (l == null) {
                    Text("말하면 이곳에 번역이 크게 표시됩니다.", fontSize = (size * 0.7f).sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Text(
                        l.translation ?: l.text,
                        fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        if (l.translation != null) l.text else "번역 중…",
                        fontSize = (size * 0.55f).sp, lineHeight = (size * 0.75f).sp,
                        fontStyle = if (l.translation == null) FontStyle.Italic else FontStyle.Normal,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }

    // 기록
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }
    Card(Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            items(lines) { l ->
                TranscriptRow(l, showTranslation = true, scale = (scale * 0.85f).coerceAtLeast(0.8f))
                HorizontalDivider()
            }
        }
    }
}
