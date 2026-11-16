package com.meetingnote.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 앱 공통 색: 기본 보라 톤 대신 차분한 파랑 + 중립 회색 배경 */
private val Light = lightColorScheme(
    primary = Color(0xFF2456D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF0B2A73),
    secondary = Color(0xFF52607A),
    secondaryContainer = Color(0xFFE3E8F2),
    onSecondaryContainer = Color(0xFF1C2638),
    tertiary = Color(0xFF0F7A5A),
    tertiaryContainer = Color(0xFFD3F2E6),
    onTertiaryContainer = Color(0xFF00382A),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFE1DD),
    onErrorContainer = Color(0xFF5C0D07),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFF5B6070),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEFF1F5),
    surfaceContainerHigh = Color(0xFFE9ECF1),
    surfaceContainerHighest = Color(0xFFE3E6EC),
    outline = Color(0xFFC3C7D1),
    outlineVariant = Color(0xFFDDE0E7),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB3C6FF),
    onPrimary = Color(0xFF0B2A73),
    primaryContainer = Color(0xFF223E8F),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFBAC6DE),
    secondaryContainer = Color(0xFF39455C),
    onSecondaryContainer = Color(0xFFDCE3F2),
    tertiary = Color(0xFF7FD9B6),
    tertiaryContainer = Color(0xFF0E5240),
    onTertiaryContainer = Color(0xFFD3F2E6),
    errorContainer = Color(0xFF7A2219),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF111317),
    onBackground = Color(0xFFE3E4E8),
    surface = Color(0xFF111317),
    onSurface = Color(0xFFE3E4E8),
    surfaceVariant = Color(0xFF2B2F38),
    onSurfaceVariant = Color(0xFFA9AEBB),
    surfaceContainerLowest = Color(0xFF0C0E11),
    surfaceContainerLow = Color(0xFF1A1D23),
    surfaceContainer = Color(0xFF1E2128),
    surfaceContainerHigh = Color(0xFF252932),
    surfaceContainerHighest = Color(0xFF2E323C),
    outline = Color(0xFF4A505D),
    outlineVariant = Color(0xFF353A45),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun MeetingNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes,
        content = content,
    )
}
