package com.meetingnote.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.meetingnote.app.TimeDiv
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meetingnote.app.ScopeData
import com.meetingnote.app.VoiceLevel
import com.meetingnote.app.VoiceThreshold
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

// 형광체 색 (P31 녹색 / 주황 / 빨강)
private val PhosphorGood = Color(0xFF52FF9A)
private val PhosphorLow = Color(0xFFFFB43A)
private val PhosphorUnclear = Color(0xFFFF5C4D)
private val PhosphorPaused = Color(0xFF8FA89A)
private val PanelLight = Color(0xFF34383A)    // 흑연색 금속 케이스
private val PanelDark = Color(0xFF1F2223)
private val Ink = Color(0xFFD5DAD6)            // 패널 인쇄 글씨 (밝은 회색)
private val BezelColor = Color(0xFF0D0F0E)
private val Recess = Color(0xFF111413)         // 움푹 들어간 계기 바탕

fun levelColor(l: VoiceLevel) = when (l) {
    VoiceLevel.GOOD -> PhosphorGood
    VoiceLevel.LOW -> PhosphorLow
    VoiceLevel.UNCLEAR -> PhosphorUnclear
}

/**
 * 아날로그 오실로스코프 스타일 녹음 모니터.
 * 왼쪽: CRT 화면(형광 잔상·스캔라인·유리 반사), 오른쪽: 금속 패널의 바늘 레벨 미터와 상태 램프.
 * 형광 색: 녹색=양호, 주황=작음(자동 보정), 빨강=인식 어려움(무음 처리 기준 미만).
 */
@Composable
fun ScopeView(
    data: ScopeData?, paused: Boolean, modifier: Modifier = Modifier,
    crtHeight: androidx.compose.ui.unit.Dp = 240.dp,
    /** 휴대폰: 레벨 미터를 빼고 상태 한 줄만 (화면 높이를 녹취록에 양보) */
    compact: Boolean = false,
) {
    val level = data?.let { VoiceThreshold.classify(it.holdDb) } ?: VoiceLevel.UNCLEAR
    val phosphor by animateColorAsState(if (paused) PhosphorPaused else levelColor(level), tween(300), label = "phosphor")
    // LED 미터: 빠르게 오르고 천천히 내려오도록
    val meterDb by animateFloatAsState(
        (data?.db ?: VoiceThreshold.FLOOR_DB).coerceIn(METER_MIN, METER_MAX),
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow), label = "meter",
    )

    // 손잡이 값 (설정에 저장, 다음 녹음에도 유지)
    val settings = com.meetingnote.app.MeetingEngine.settings
    var intensity by remember { mutableIntStateOf(settings.scopeIntensity) }
    var focus by remember { mutableIntStateOf(settings.scopeFocus) }
    var timeDiv by remember { mutableIntStateOf(settings.scopeTimeDiv) }

    // INTENSITY가 높을수록 잔상이 길게 남음 (2~7장)
    val trailLen = 2 + intensity / 2
    val trail = remember { ArrayDeque<FloatArray>() }
    if (data != null && (trail.isEmpty() || trail.last() !== data.wave)) {
        trail.addLast(data.wave)
    }
    while (trail.size > trailLen) trail.removeFirst()
    val beam = Beam(intensity / 10f, focus / 10f)

    val crt: @Composable (Modifier, androidx.compose.ui.unit.Dp) -> Unit = { m, h ->
        Box(
            m.height(h)
                .clip(RoundedCornerShape(16.dp))
                .background(BezelColor)
                .padding(6.dp)
        ) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
                drawCrtGlass()
                drawGraticule(phosphor)
                val n = trail.size
                trail.forEachIndexed { i, w ->
                    val age = n - 1 - i
                    drawTrace(w, phosphor, if (age == 0) 1f else 0.30f / age, glow = age == 0, beam)
                }
                if (trail.isEmpty()) drawTrace(FloatArray(2), phosphor, 0.8f, glow = true, beam)
                drawScanlinesAndReflection()
                drawReadout(data, paused, phosphor, TimeDiv.label(timeDiv))
            }
        }
    }
    /** 최근 10초 음량: 가운데 기준 위아래 대칭 막대 (녹음 앱처럼 흘러감) */
    val history: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { h ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                PanelLabel("최근 10초")
                Spacer(Modifier.weight(1f))
                PanelLabel("— 인식 기준")
            }
            Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(10.dp)).background(Recess)) {
                Canvas(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) { drawHistory(data?.history, paused) }
            }
        }
    }
    val meter: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                PanelLabel("입력 음량")
                Spacer(Modifier.weight(1f))
                PanelLabel(data?.db?.let { if (it <= VoiceThreshold.FLOOR_DB) "-∞ dB" else "%.0f dB".format(it) } ?: "--")
            }
            Box(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp)).background(Recess).padding(5.dp)) {
                Canvas(Modifier.fillMaxSize()) { drawLedMeter(meterDb, data?.holdDb ?: VoiceThreshold.FLOOR_DB) }
            }
            Row {
                listOf("-60", "-48", "-38", "0").forEachIndexed { i, t ->
                    if (i > 0) Spacer(Modifier.weight(if (i == 1) 12f else if (i == 2) 10f else 38f))
                    PanelLabel(t)
                }
            }
        }
    }
    val statusTitle = when {
        paused -> "일시정지"
        level == VoiceLevel.GOOD -> "음성 양호"
        level == VoiceLevel.LOW -> "소리가 작음"
        else -> "음성 감지 안 됨"
    }
    val statusHint = when {
        paused -> "다시 시작하면 이어서 녹음합니다"
        level == VoiceLevel.GOOD -> "인식에 충분한 음량입니다"
        level == VoiceLevel.LOW -> "자동 보정 중 · 가까이서 말하면 더 정확합니다"
        else -> "이 구간은 인식되지 않을 수 있습니다"
    }
    val status: @Composable (Boolean) -> Unit = { small ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(phosphor.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = if (small) 6.dp else 10.dp)) {
            Canvas(Modifier.size(if (small) 14.dp else 18.dp)) { drawLamp(phosphor) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(statusTitle, color = phosphor, fontWeight = FontWeight.Bold, fontSize = if (small) 14.sp else 16.sp, maxLines = 1)
                if (!small) Text(statusHint, color = Ink.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }
    }
    val knobs: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            Knob("INTENSITY", intensity, 10, "$intensity") { intensity = it; settings.scopeIntensity = it }
            Knob("FOCUS", focus, 10, "$focus") { focus = it; settings.scopeFocus = it }
            Knob("TIME/DIV", timeDiv, TimeDiv.msPerDiv.lastIndex, "${TimeDiv.msPerDiv[timeDiv]}ms") {
                timeDiv = it; com.meetingnote.app.MeetingEngine.setScopeTimeDiv(it)
            }
        }
    }

    // 장비 케이스 (반응형: 넓으면 화면 옆에 계기 패널, 좁으면 아래로)
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(PanelLight, PanelDark)))
            .padding(if (compact) 10.dp else 16.dp)
    ) {
        if (maxWidth >= 620.dp) {
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    crt(Modifier.fillMaxWidth(), crtHeight)
                    history(56.dp)
                }
                Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    status(false)
                    meter(Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                    knobs()
                    PanelLabel("MEETINGNOTE  MN-1")
                }
            }
        } else if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                crt(Modifier.fillMaxWidth(), crtHeight)
                history(32.dp)
                status(true)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                crt(Modifier.fillMaxWidth(), crtHeight.coerceAtMost(200.dp))
                history(44.dp)
                status(false)
                meter(Modifier.fillMaxWidth())
            }
        }
    }
}

private const val METER_MIN = -60f
private const val METER_MAX = 0f

@Composable
private fun PanelLabel(t: String) =
    Text(t, color = Ink.copy(alpha = 0.8f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

/**
 * 동작하는 손잡이: 탭하면 한 단계 증가(최대에서 처음으로), 위아래로 끌면 연속 조절.
 * 단계가 바뀔 때마다 짧은 진동.
 */
@Composable
private fun Knob(label: String, step: Int, maxStep: Int, valueText: String, onChange: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(step)
    val change by rememberUpdatedState(onChange)
    val stepPx = with(LocalDensity.current) { 18.dp.toPx() }
    fun set(v: Int) {
        val nv = v.coerceIn(0, maxStep)
        if (nv != current) { change(nv); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .semantics { contentDescription = "$label $valueText, 탭하거나 위아래로 끌어 조절" }
            .pointerInput(maxStep) {
                detectTapGestures { set(if (current >= maxStep) 0 else current + 1) }
            }
            .pointerInput(maxStep) {
                var acc = 0f
                detectVerticalDragGestures(onDragStart = { acc = 0f }) { _, dy ->
                    acc -= dy                                   // 위로 끌면 증가
                    while (acc >= stepPx) { set(current + 1); acc -= stepPx }
                    while (acc <= -stepPx) { set(current - 1); acc += stepPx }
                }
            },
    ) {
        val pos = if (maxStep == 0) 0f else step.toFloat() / maxStep
        Canvas(Modifier.size(44.dp)) {
            val r = size.minDimension / 2
            // 눈금 링 (단계 수만큼)
            for (i in 0..maxStep) {
                val a = (135 + i * 270f / maxStep) * PI / 180
                val on = i <= step
                drawLine(Ink.copy(alpha = if (on) 0.85f else 0.35f),
                    center + Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat()),
                    center + Offset((cos(a) * r * 0.84f).toFloat(), (sin(a) * r * 0.84f).toFloat()), if (on) 2f else 1.5f)
            }
            drawCircle(Color.Black.copy(alpha = 0.25f), r * 0.78f, center + Offset(2f, 3f))   // 그림자
            drawCircle(Brush.radialGradient(listOf(Color(0xFF8C9190), Color(0xFF3A3E3F)), center, r * 0.8f), r * 0.76f)
            drawCircle(Brush.linearGradient(listOf(Color(0xFFC9CDCB), Color(0xFF5B6061))), r * 0.6f)
            val a = (135 + pos * 270) * PI / 180
            drawLine(Color(0xFF151716), center + Offset((cos(a) * r * 0.18f).toFloat(), (sin(a) * r * 0.18f).toFloat()),
                center + Offset((cos(a) * r * 0.56f).toFloat(), (sin(a) * r * 0.56f).toFloat()), 3f, cap = StrokeCap.Round)
        }
        Text(label, color = Ink, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(valueText, color = Ink.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

/** 빔 설정: INTENSITY(밝기 0..1), FOCUS(선명도 0..1) */
private class Beam(val intensity: Float, val focus: Float)

// ---------------- CRT ----------------

private fun DrawScope.drawCrtGlass() {
    // 중앙이 약간 밝고 가장자리로 갈수록 어두운 브라운관 유리
    drawRect(Brush.radialGradient(
        listOf(Color(0xFF14261C), Color(0xFF0A1610), Color(0xFF030805)),
        center, size.maxDimension * 0.62f,
    ))
}

private fun DrawScope.drawGraticule(c: Color) {
    val cols = 10; val rows = 8
    val g = c.copy(alpha = 0.16f)
    for (i in 0..cols) { val x = size.width * i / cols; drawLine(g, Offset(x, 0f), Offset(x, size.height), 1f) }
    for (j in 0..rows) { val y = size.height * j / rows; drawLine(g, Offset(0f, y), Offset(size.width, y), 1f) }
    // 중앙축 세부 눈금 (5분할)
    val t = c.copy(alpha = 0.3f)
    for (i in 0..cols * 5) {
        val x = size.width * i / (cols * 5)
        drawLine(t, Offset(x, size.height / 2 - 5f), Offset(x, size.height / 2 + 5f), 1f)
    }
    for (j in 0..rows * 5) {
        val y = size.height * j / (rows * 5)
        drawLine(t, Offset(size.width / 2 - 5f, y), Offset(size.width / 2 + 5f, y), 1f)
    }
}

/**
 * 작은 음성도 보이도록 6배 증폭 후 tanh로 부드럽게 압축.
 * INTENSITY: 전체 밝기, FOCUS: 높을수록 가늘고 선명, 낮을수록 굵고 번짐.
 */
private fun DrawScope.drawTrace(w: FloatArray, c: Color, alpha: Float, glow: Boolean, beam: Beam) {
    val mid = size.height * 0.5f
    val amp = size.height * 0.40f
    val path = Path()
    for (i in w.indices) {
        val x = size.width * i / (w.size - 1).coerceAtLeast(1)
        val y = mid - tanh(w[i] * 6f) * amp
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val a = alpha * (0.25f + 0.75f * beam.intensity)
    val blur = 1f - beam.focus                       // 0 = 선명, 1 = 흐림
    val round = { width: Float -> Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    if (glow) {
        // 형광체 번짐(bloom): 초점이 흐릴수록 넓고 진하게
        drawPath(path, c.copy(alpha = a * (0.05f + 0.08f * blur)), style = round(14f + 30f * blur))
        drawPath(path, c.copy(alpha = a * (0.12f + 0.12f * blur)), style = round(6f + 14f * blur))
        drawPath(path, c.copy(alpha = a * 0.45f), style = round(3f + 6f * blur))
        drawPath(path, Color.White.copy(alpha = a * 0.6f * beam.focus), style = round(1.1f))   // 빔 중심의 하얀 코어
    }
    drawPath(path, c.copy(alpha = a), style = round(1.6f + 3.4f * blur))
}

/** 최근 10초 음량: 가운데 기준 위아래 대칭 막대 + 인식 기준선 */
private fun DrawScope.drawHistory(h: FloatArray?, paused: Boolean) {
    val mid = size.height / 2
    val range = -10f - VoiceThreshold.FLOOR_DB
    fun half(db: Float) = mid * ((db - VoiceThreshold.FLOOR_DB) / range).coerceIn(0f, 1f)
    val thr = half(VoiceThreshold.UNCLEAR_DB)
    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    drawLine(PhosphorUnclear.copy(alpha = 0.45f), Offset(0f, mid - thr), Offset(size.width, mid - thr), 1.5f, pathEffect = dash)
    drawLine(PhosphorUnclear.copy(alpha = 0.45f), Offset(0f, mid + thr), Offset(size.width, mid + thr), 1.5f, pathEffect = dash)
    drawLine(Ink.copy(alpha = 0.15f), Offset(0f, mid), Offset(size.width, mid), 1f)
    if (h == null) return
    val bw = size.width / h.size
    h.forEachIndexed { i, db ->
        if (db <= VoiceThreshold.FLOOR_DB) return@forEachIndexed
        val lv = VoiceThreshold.classify(db)
        // 인식 기준 미만(쉼·잡음)은 흐린 회색 점으로: 빨간 막대가 줄지어 경고처럼 보이지 않게
        val col = if (paused || lv == VoiceLevel.UNCLEAR) PhosphorPaused else levelColor(lv)
        val hh = if (lv == VoiceLevel.UNCLEAR) 1.5f else half(db)
        // 최근 것일수록 진하게
        val a = (0.45f + 0.5f * i / h.size) * (if (lv == VoiceLevel.UNCLEAR) 0.5f else 1f)
        drawRoundRect(col.copy(alpha = a), Offset(i * bw + bw * 0.18f, mid - hh), Size(bw * 0.64f, hh * 2),
            androidx.compose.ui.geometry.CornerRadius(bw * 0.3f))
    }
}

private fun DrawScope.drawScanlinesAndReflection() {
    var y = 0f
    while (y < size.height) { drawLine(Color.Black.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), 1f); y += 3f }
    // 볼록한 유리 반사광 (경계 없이 부드러운 하이라이트)
    drawRect(Brush.radialGradient(
        listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0f)),
        Offset(size.width * 0.22f, size.height * 0.08f), size.width * 0.38f,
    ))
    // 가장자리 비네팅
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), center, size.maxDimension * 0.65f))
}

private fun DrawScope.drawReadout(d: ScopeData?, paused: Boolean, c: Color, timeDiv: String) {
    val p = android.graphics.Paint().apply {
        isAntiAlias = true; textSize = 13.sp.toPx(); typeface = android.graphics.Typeface.MONOSPACE
        color = android.graphics.Color.argb((c.alpha * 230).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
        setShadowLayer(8f, 0f, 0f, color)
    }
    val db = d?.db?.let { if (it <= VoiceThreshold.FLOOR_DB) "-INF" else "%.0f".format(it) } ?: "--"
    drawContext.canvas.nativeCanvas.apply {
        drawText("CH1  $db dBFS", 14.dp.toPx(), 22.dp.toPx(), p)
        val right = if (paused) "HOLD" else "RUN"
        drawText(right, size.width - p.measureText(right) - 14.dp.toPx(), 22.dp.toPx(), p)
        p.textSize = 10.sp.toPx()
        drawText(timeDiv, 14.dp.toPx(), size.height - 12.dp.toPx(), p)
    }
}

// ---------------- 패널 계기 ----------------

/** LED 막대 미터 (-60 ~ 0 dBFS): 구간 색 빨강 < -48, 주황 < -38, 녹색. 최근 0.5초 최고값에 표시선 */
private fun DrawScope.drawLedMeter(db: Float, holdDb: Float) {
    val n = 30
    val gap = 3f
    val w = (size.width - gap * (n - 1)) / n
    fun dbAt(i: Int) = METER_MIN + (METER_MAX - METER_MIN) * (i + 0.5f) / n
    val peak = ((holdDb - METER_MIN) / (METER_MAX - METER_MIN) * n).toInt().coerceIn(-1, n - 1)
    for (i in 0 until n) {
        val v = dbAt(i)
        val col = levelColor(VoiceThreshold.classify(v))
        val on = v <= db
        val x = i * (w + gap)
        drawRoundRect(if (on) col else col.copy(alpha = 0.13f), Offset(x, 0f), Size(w, size.height),
            androidx.compose.ui.geometry.CornerRadius(2f))
        if (on) drawRoundRect(col.copy(alpha = 0.35f), Offset(x - 1.5f, -1.5f), Size(w + 3f, size.height + 3f),
            androidx.compose.ui.geometry.CornerRadius(3f))
        if (i == peak && !on) drawRoundRect(col.copy(alpha = 0.8f), Offset(x, 0f), Size(w, size.height),
            androidx.compose.ui.geometry.CornerRadius(2f))
    }
}

private fun DrawScope.drawLamp(c: Color) {
    val r = size.minDimension / 2
    drawCircle(c.copy(alpha = 0.35f), r)                                   // 빛 번짐
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), c, c.copy(red = c.red * 0.5f, green = c.green * 0.5f, blue = c.blue * 0.5f)),
        center - Offset(r * 0.25f, r * 0.25f), r * 0.9f), r * 0.72f)
    drawCircle(Color(0xFF0D0F0E), r * 0.74f, style = Stroke(2f))          // 금속 테
}
