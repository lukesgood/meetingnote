package com.meetingnote.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.meetingnote.app.MeetingEngine
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Live : Screen
    data object Settings : Screen
    data class Detail(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) {
            MeetingEngine.startRecording()
            screen = Screen.Live
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (MeetingEngine.state.value.busy) screen = Screen.Live
        handleIntent(intent)

        setContent {
            MeetingNoteTheme {
                // 기기 점검 중에는 화면을 켜 둠 (꺼지면 느린 코어로 옮겨져 점검이 무효가 됨)
                val checking by com.meetingnote.app.MeetingEngine.checking.collectAsState()
                val view = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.DisposableEffect(checking != null) {
                    view.keepScreenOn = checking != null
                    onDispose { view.keepScreenOn = false }
                }
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        onRecord = ::requestRecord,
                        onOpen = { screen = Screen.Detail(it) },
                        onOpenLive = { screen = Screen.Live },
                        onOpenSettings = { screen = Screen.Settings },
                    )
                    Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
                    Screen.Live -> LiveScreen(
                        onDone = { id -> screen = if (id != null) Screen.Detail(id) else Screen.Home },
                        onBack = { screen = Screen.Home },
                    )
                    is Screen.Detail -> DetailScreen(
                        id = s.id,
                        onBack = { screen = Screen.Home },
                        onOpenLive = { screen = Screen.Live },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 테스트/가져오기용: 16kHz mono WAV 경로를 받아 새 회의로 인식+요약.
     * adb shell am start -n com.meetingnote.app/.ui.MainActivity --es import_wav <path>
     */
    private fun handleIntent(intent: Intent?) {
        // 알림을 누르면 진행 중인 녹음/처리 화면으로 이동
        if (intent?.getBooleanExtra("open_live", false) == true) {
            intent.removeExtra("open_live")
            if (MeetingEngine.state.value.busy) screen = Screen.Live
            return
        }
        // 테스트용: 용어 사전 설정 (--es vocabulary '용어1, 용어2'), adb로 한글 입력이 안 되는 문제 우회
        intent?.getStringExtra("vocabulary")?.let { v ->
            intent.removeExtra("vocabulary")
            MeetingEngine.settings.vocabulary = v
        }
        // 테스트용 설정: --ez interpret true|false, --es minutes_lang ko|en, --ez trim_encoder true|false
        if (intent?.hasExtra("interpret") == true) { MeetingEngine.settings.interpret = intent.getBooleanExtra("interpret", false); intent.removeExtra("interpret") }
        if (intent?.getBooleanExtra("device_check_reset", false) == true) {   // 테스트: 첫 실행 상태로
            intent.removeExtra("device_check_reset")
            com.meetingnote.app.DeviceCheck.bench = null
            getSharedPreferences("device_check", MODE_PRIVATE).edit().clear().apply()
        }
        if (intent?.getBooleanExtra("device_check", false) == true) { intent.removeExtra("device_check"); MeetingEngine.runDeviceCheck() }
        intent?.getStringExtra("minutes_lang")?.let { MeetingEngine.settings.minutesLang = it; intent.removeExtra("minutes_lang") }
        if (intent?.hasExtra("refine") == true) { MeetingEngine.settings.refine = intent.getBooleanExtra("refine", true); intent.removeExtra("refine") }
        // 테스트: --es cpu_variant base|dp|auto (앱을 다시 시작해야 적용)
        intent?.getStringExtra("cpu_variant")?.let { v ->
            getSharedPreferences("engine", MODE_PRIVATE).edit().apply { if (v == "auto") remove("cpu_variant") else putString("cpu_variant", v) }.commit()
            intent.removeExtra("cpu_variant")
        }
        if (intent?.hasExtra("trim_encoder") == true) { MeetingEngine.settings.trimEncoder = intent.getBooleanExtra("trim_encoder", false); intent.removeExtra("trim_encoder") }
        // 테스트용: 기존 회의의 회의록만 다시 생성 (--es summarize_id <id>)
        intent?.getStringExtra("summarize_id")?.let { id ->
            intent.removeExtra("summarize_id")
            if (!MeetingEngine.state.value.busy && MeetingEngine.repo.get(id) != null) {
                MeetingEngine.summarize(id)
                screen = Screen.Live
            }
            return
        }
        val path = intent?.getStringExtra("import_wav") ?: return
        intent.removeExtra("import_wav")   // 화면 재생성 시 중복 처리 방지
        val src = File(path)
        if (!src.exists() || MeetingEngine.state.value.busy) return
        if (!src.name.endsWith(".wav", ignoreCase = true)) {
            // wav 이외 형식은 디코더(AudioImporter) 경로로 처리
            MeetingEngine.importAudio(android.net.Uri.fromFile(src), src.name)
            screen = Screen.Live
            return
        }
        val m = MeetingEngine.repo.create()
        src.copyTo(MeetingEngine.repo.audioFile(m.id), overwrite = true)
        MeetingEngine.transcribeFile(m.id, thenSummarize = intent.getBooleanExtra("summarize", true))
        screen = Screen.Live
    }

    private fun requestRecord() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            MeetingEngine.startRecording()
            screen = Screen.Live
        } else {
            permLauncher.launch(perms.toTypedArray())
        }
    }
}
