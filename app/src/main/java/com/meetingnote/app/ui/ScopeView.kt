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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
private val PanelLight = Color(0xFFD9D6CC)    // 베이지 금속 패널
private val PanelDark = Color(0xFFA9A598)
private val Ink = Color(0xFF2B2A26)            // 패널 인쇄 글씨
private val BezelColor = Color(0xFF1B1C1A)

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
fun ScopeView(data: ScopeData?, paused: Boolean, modifier: Modifier = Modifier, crtHeight: androidx.compose.ui.unit.Dp = 240.dp) {
    val level = data?.let { VoiceThreshold.classify(it.holdDb) } ?: VoiceLevel.UNCLEAR
    val phosphor by animateColorAsState(if (paused) PhosphorPaused else levelColor(level), tween(300), label = "phosphor")
    // 바늘: 실제 미터처럼 약간 출렁이며 따라가도록 스프링
    val needleDb by animateFloatAsState(
        (data?.db ?: VoiceThreshold.FLOOR_DB).coerceIn(METER_MIN, METER_MAX),
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow), label = "needle",
    )

    val trail = remember { ArrayDeque<FloatArray>() }
    if (data != null && (trail.isEmpty() || trail.last() !== data.wave)) {
        trail.addLast(data.wave)
        while (trail.size > 5) trail.removeFirst()
    }

    val crt: @Composable (Modifier, androidx.compose.ui.unit.Dp) -> Unit = { m, h ->
        Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.fillMaxWidth().height(h)
                    .clip(RoundedCornerShape(18.dp))
                    .background(BezelColor)
                    .padding(10.dp)
            ) {
                Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp))) {
                    drawCrtGlass()
                    drawGraticule(phosphor)
                    val n = trail.size
                    trail.forEachIndexed { i, w ->
                        val age = n - 1 - i
                        drawTrace(w, phosphor, if (age == 0) 1f else 0.30f / age, glow = age == 0)
                    }
                    if (trail.isEmpty()) drawTrace(FloatArray(2), phosphor, 0.8f, glow = true)
                    drawHistory(data?.history, phosphor)
                    drawScanlinesAndReflection()
                    drawReadout(data, paused, phosphor)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PanelLabel("CH1  MIC  ·  50 ms/DIV")
                Spacer(Modifier.weight(1f))
                PanelLabel("MEETINGNOTE  MN-1")
            }
        }
    }
    val meter: @Composable (Modifier) -> Unit = { m ->
        Box(m.clip(RoundedCornerShape(8.dp)).background(Color(0xFFF2EBD3))) {
            Canvas(Modifier.fillMaxSize()) { drawMeter(needleDb) }
        }
    }
    val status: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Canvas(Modifier.size(22.dp)) { drawLamp(phosphor) }
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        paused -> "일시정지"
                        level == VoiceLevel.GOOD -> "음성 양호"
                        level == VoiceLevel.LOW -> "소리가 작음 · 자동 보정 중"
                        else -> "음성 감지 안 됨"
                    },
                    color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
            }
            Text(
                when {
                    paused -> " "
                    level == VoiceLevel.GOOD -> "인식에 충분한 음량입니다"
                    level == VoiceLevel.LOW -> "가까이서 말하면 더 정확해집니다"
                    else -> "이 구간은 인식되지 않을 수 있습니다"
                },
                color = Ink.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.fillMaxWidth(),
            )
    }

    // 장비 케이스 (반응형: 넓으면 CRT 옆에 계기 패널, 좁으면 아래로)
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(PanelLight, PanelDark)))
            .padding(14.dp)
    ) {
        if (maxWidth >= 620.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                crt(Modifier.weight(1f), crtHeight)
                Column(
                    Modifier.width(250.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    meter(Modifier.fillMaxWidth().height(150.dp))
                    status()
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        Knob("INTENSITY", 0.62f)
                        Knob("FOCUS", 0.45f)
                        Knob("TIME/DIV", 0.3f)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                crt(Modifier.fillMaxWidth(), 180.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    meter(Modifier.width(170.dp).height(110.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { status() }
                }
            }
        }
    }
}

private const val METER_MIN = -60f
private const val METER_MAX = 0f

@Composable
private fun PanelLabel(t: String) =
    Text(t, color = Ink.copy(alpha = 0.8f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

@Composable
private fun Knob(label: String, pos: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(40.dp)) {
            val r = size.minDimension / 2
            // 눈금 링
            for (i in 0..10) {
                val a = (135 + i * 27) * PI / 180
                drawLine(Ink.copy(alpha = 0.6f),
                    center + Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat()),
                    center + Offset((cos(a) * r * 0.86f).toFloat(), (sin(a) * r * 0.86f).toFloat()), 1.5f)
            }
            drawCircle(Brush.radialGradient(listOf(Color(0xFF5A5A58), Color(0xFF1E1E1D)), center, r * 0.8f), r * 0.78f)
            drawCircle(Brush.linearGradient(listOf(Color(0xFF6E6E6B), Color(0xFF232322))), r * 0.62f)
            val a = (135 + pos * 270) * PI / 180
            drawLine(Color(0xFFEDEDED), center + Offset((cos(a) * r * 0.2f).toFloat(), (sin(a) * r * 0.2f).toFloat()),
                center + Offset((cos(a) * r * 0.58f).toFloat(), (sin(a) * r * 0.58f).toFloat()), 3f, cap = StrokeCap.Round)
        }
        Text(label, color = Ink, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

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

/** 작은 음성도 보이도록 6배 증폭 후 tanh로 부드럽게 압축 */
private fun DrawScope.drawTrace(w: FloatArray, c: Color, alpha: Float, glow: Boolean) {
    val mid = size.height * 0.44f
    val amp = size.height * 0.36f
    val path = Path()
    for (i in w.indices) {
        val x = size.width * i / (w.size - 1).coerceAtLeast(1)
        val y = mid - tanh(w[i] * 6f) * amp
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val round = { width: Float -> Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    if (glow) {
        // 형광체 번짐(bloom)
        drawPath(path, c.copy(alpha = alpha * 0.07f), style = round(26f))
        drawPath(path, c.copy(alpha = alpha * 0.16f), style = round(12f))
        drawPath(path, c.copy(alpha = alpha * 0.45f), style = round(5f))
        drawPath(path, Color.White.copy(alpha = alpha * 0.55f), style = round(1.2f))   // 빔 중심의 하얀 코어
    }
    drawPath(path, c.copy(alpha = alpha), style = round(2.4f))
}

/** CRT 하단에 최근 10초 음량을 형광 막대로 표시 */
private fun DrawScope.drawHistory(h: FloatArray?, c: Color) {
    if (h == null) return
    val top = size.height * 0.84f
    val bottom = size.height * 0.97f
    val range = -10f - VoiceThreshold.FLOOR_DB
    fun y(db: Float) = bottom - (bottom - top) * ((db - VoiceThreshold.FLOOR_DB) / range).coerceIn(0f, 1f)
    val bw = size.width / h.size
    h.forEachIndexed { i, db ->
        if (db <= VoiceThreshold.FLOOR_DB) return@forEachIndexed
        val col = levelColor(VoiceThreshold.classify(db))
        drawRect(col.copy(alpha = 0.75f), Offset(i * bw + 1f, y(db)), Size((bw - 2f).coerceAtLeast(1f), bottom - y(db)))
    }
    drawLine(PhosphorUnclear.copy(alpha = 0.55f), Offset(0f, y(VoiceThreshold.UNCLEAR_DB)), Offset(size.width, y(VoiceThreshold.UNCLEAR_DB)), 1f)
    drawLine(c.copy(alpha = 0.25f), Offset(0f, top - 6f), Offset(size.width, top - 6f), 1f)
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

private fun DrawScope.drawReadout(d: ScopeData?, paused: Boolean, c: Color) {
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
        drawText("LEVEL 10s", 14.dp.toPx(), size.height * 0.84f - 10f, p)
    }
}

// ---------------- 패널 계기 ----------------

/** 바늘 레벨 미터 (-60 ~ 0 dBFS), 구간 색: 빨강 < -48, 주황 < -38, 녹색 */
private fun DrawScope.drawMeter(db: Float) {
    val cx = size.width / 2
    val cy = size.height * 0.92f
    val r = size.height * 0.78f
    val start = 215f; val sweep = 110f                 // 바늘 각도 범위 (도)
    fun ang(v: Float) = start + sweep * (v - METER_MIN) / (METER_MAX - METER_MIN)
    val arcTopLeft = Offset(cx - r, cy - r)
    val arcSize = Size(r * 2, r * 2)
    fun zone(from: Float, to: Float, col: Color) =
        drawArc(col, ang(from), ang(to) - ang(from), false, arcTopLeft, arcSize, style = Stroke(9f))
    zone(METER_MIN, VoiceThreshold.UNCLEAR_DB, Color(0xFFD9342A))
    zone(VoiceThreshold.UNCLEAR_DB, VoiceThreshold.GOOD_DB, Color(0xFFE09A1A))
    zone(VoiceThreshold.GOOD_DB, METER_MAX, Color(0xFF2E9E5B))

    // 눈금과 숫자
    val p = android.graphics.Paint().apply {
        isAntiAlias = true; textSize = 10.sp.toPx(); color = android.graphics.Color.rgb(43, 42, 38)
        textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    var v = METER_MIN
    while (v <= METER_MAX + 0.1f) {
        val a = Math.toRadians(ang(v).toDouble())
        val major = v.toInt() % 10 == 0
        val r1 = r - 8f; val r2 = r - if (major) 22f else 14f
        drawLine(Ink, Offset(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat()),
            Offset(cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat()), if (major) 2f else 1f)
        if (major) drawContext.canvas.nativeCanvas.drawText(
            v.toInt().toString(), cx + (cos(a) * (r - 36f)).toFloat(), cy + (sin(a) * (r - 36f)).toFloat() + 4f, p)
        v += 5f
    }
    p.textSize = 11.sp.toPx()
    drawContext.canvas.nativeCanvas.drawText("INPUT LEVEL  dBFS", cx, cy - r * 0.32f, p)

    // 바늘 (그림자 + 본체)
    val a = ang(db)
    rotate(a + 90f, Offset(cx, cy)) {
        drawLine(Color.Black.copy(alpha = 0.25f), Offset(cx + 3f, cy + 3f), Offset(cx + 3f, cy - r + 6f + 3f), 3f, cap = StrokeCap.Round)
        drawLine(Color(0xFF1A1A1A), Offset(cx, cy), Offset(cx, cy - r + 6f), 2.5f, cap = StrokeCap.Round)
    }
    drawCircle(Color(0xFF2B2A26), 9f, Offset(cx, cy))
    // 미터 유리 반사
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), 0f, size.height * 0.45f))
}

private fun DrawScope.drawLamp(c: Color) {
    val r = size.minDimension / 2
    drawCircle(c.copy(alpha = 0.35f), r)                                   // 빛 번짐
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), c, c.copy(red = c.red * 0.5f, green = c.green * 0.5f, blue = c.blue * 0.5f)),
        center - Offset(r * 0.25f, r * 0.25f), r * 0.9f), r * 0.72f)
    drawCircle(Color(0xFF3A3934), r * 0.74f, style = Stroke(2f))          // 금속 테
}
