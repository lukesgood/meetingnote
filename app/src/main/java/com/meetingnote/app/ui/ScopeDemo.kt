package com.meetingnote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meetingnote.app.ScopeAnalyzer
import com.meetingnote.app.ScopeData
import com.meetingnote.app.formatTime
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * 개발용: 녹음 없이 오실로스코프 화면 확인 (합성 음성 신호).
 * adb shell am start -n com.meetingnote.app/.ui.MainActivity --ez scope_demo true
 */
@Composable
fun ScopeDemoScreen(onBack: () -> Unit) {
    val analyzer = remember { ScopeAnalyzer() }
    var data by remember { mutableStateOf<ScopeData?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        var n = 0L
        var phase = 0.0
        while (true) {
            val frame = ShortArray(1600)
            val t = n / 16000.0
            // 음절(약 4Hz) 단위로 커졌다 작아지고, 2.5초마다 1초 쉼. 3번째 구간은 작은 목소리
            val cycle = t % 7.5
            val loud = when { cycle < 2.5 -> 0.25; cycle < 3.5 -> 0.0; cycle < 6.0 -> 0.03; else -> 0.0 }
            val f0 = 130.0 + 25 * sin(2 * PI * 0.7 * t)
            for (i in frame.indices) {
                val tt = (n + i) / 16000.0
                val env = loud * (0.5 + 0.5 * sin(2 * PI * 4.2 * tt)).let { it * it }
                phase += 2 * PI * f0 / 16000
                val v = env * (sin(phase) + 0.6 * sin(2 * phase + 0.4) + 0.35 * sin(3 * phase + 1.1) + 0.2 * sin(5 * phase)) +
                    (Math.random() - 0.5) * 0.002
                frame[i] = (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            }
            n += frame.size
            data = analyzer.push(frame)
            elapsed = n * 1000 / 16000
            delay(100)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val compact = maxWidth < 480.dp || maxHeight < 560.dp
            val crt = (maxHeight * 0.4f).coerceIn(90.dp, 420.dp)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("녹음 중 (데모)", style = MaterialTheme.typography.titleMedium)
                    Text(formatTime(elapsed), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                    ScopeView(data, paused = false, modifier = Modifier.fillMaxWidth(), crtHeight = crt, compact = compact)
                }
            }
        }
    }
}
