package com.meetingnote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetingnote.app.TranscriptLine
import com.meetingnote.app.formatTime

/**
 * 녹취록 한 줄: 시각 · (통역 시) 언어 배지 · 원문 · 번역.
 * @param showTranslation 통역 회의이면 번역 줄(또는 '번역 중…')을 표시
 * @param translating 지금 번역이 진행 중인지. false면 번역이 없는 문장(번역 실패·빈 결과)에 '번역 중…'을 띄우지 않는다
 */
@Composable
fun TranscriptRow(l: TranscriptLine, showTranslation: Boolean, scale: Float = 1f, translating: Boolean = true) {
    // 통역 모드에서는 번역을 원문보다 크게 (회의 참석자가 읽는 쪽이 번역)
    val origSize = (if (showTranslation) 15f else 16f) * scale
    val trSize = 19f * scale
    Row(Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.width(64.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatTime(l.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (showTranslation && l.lang != null) {
                val c = MaterialTheme.colorScheme
                Badge(null, l.lang.uppercase(), if (l.lang == "en") c.tertiaryContainer else c.secondaryContainer,
                    if (l.lang == "en") c.onTertiaryContainer else c.onSecondaryContainer)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(l.text, style = MaterialTheme.typography.bodyLarge, fontSize = origSize.sp, lineHeight = (origSize * 1.4f).sp,
                color = if (showTranslation) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (showTranslation) {
                if (l.translation != null) Text(
                    l.translation, style = MaterialTheme.typography.bodyLarge, fontSize = trSize.sp, lineHeight = (trSize * 1.35f).sp,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                ) else if (translating) Text(
                    "번역 중…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}
