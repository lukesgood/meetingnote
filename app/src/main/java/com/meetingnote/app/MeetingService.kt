package com.meetingnote.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.meetingnote.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MeetingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorReporter.install(this)
        MeetingEngine.init(this)
        // IMPORTANCE_LOW 채널은 '무음' 영역에 접혀 종료 버튼이 안 보이므로 DEFAULT + 소리/진동 없음으로 생성.
        // 채널 중요도는 생성 후 바꿀 수 없어 새 ID를 쓰고 예전 채널은 삭제한다.
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("meeting")
        nm.createNotificationChannel(
            NotificationChannel(MeetingService.CHANNEL, "회의 녹음/처리", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }
}

/**
 * 녹음·인식·요약이 진행되는 동안 프로세스를 포그라운드로 유지하고
 * 화면이 꺼져도 CPU가 멈추지 않도록 partial wake lock을 잡는다.
 */
class MeetingService : Service() {
    companion object {
        const val CHANNEL = "meeting_status"
        const val ACTION_STOP = "com.meetingnote.app.STOP"
        private const val NOTI_ID = 1
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentType = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeetingNote::processing")
            .apply {
                setReferenceCounted(false)
                // 시간 제한 없이 유지하고, 처리가 끝나 서비스가 종료될 때 onDestroy에서 해제
                @Suppress("WakelockTimeout") acquire()
            }

        scope.launch {
            MeetingEngine.state
                .map { NotiKey(it.phase, it.summaryStage, it.elapsedMs / 1000, it.refineFraction?.let { f -> (f * 100).toInt() }) }
                .distinctUntilChanged()
                .collect { (phase, stage, sec, _) ->
                    if (phase == Phase.IDLE || phase == Phase.ERROR) {
                        ServiceCompat.stopForeground(this@MeetingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    val text = when (phase) {
                        Phase.LOADING -> "준비 중…"
                        Phase.RECORDING -> "녹음 중 ${formatTime(sec * 1000)}"
                        Phase.FINISHING_STT -> "남은 음성 인식 중…"
                        Phase.TRANSCRIBING_FILE -> "음성 파일 인식 중… ${formatTime(sec * 1000)}"
                        Phase.SUMMARIZING -> "회의록 작성 중 · $stage"
                        Phase.REFINING -> MeetingEngine.state.value.message ?: "정밀 인식 중…"
                        else -> ""
                    }
                    val type = if (phase == Phase.RECORDING || phase == Phase.LOADING)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    if (type != currentType) {
                        ServiceCompat.startForeground(this@MeetingService, NOTI_ID, build(text), type)
                        currentType = type
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTI_ID, build(text))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 알림의 '종료' 버튼: 녹음 중이면 종료 후 회의록 작성, 요약 중이면 중단
            when (MeetingEngine.state.value.phase) {
                Phase.RECORDING -> MeetingEngine.stopRecording(autoSummarize = true)
                Phase.SUMMARIZING -> MeetingEngine.cancel()
                else -> {}
            }
        }
        if (currentType == 0) {
            // startForegroundService 후 5초 내 startForeground 필요
            val phase = MeetingEngine.state.value.phase
            val type = if (phase == Phase.RECORDING || phase == Phase.LOADING)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ServiceCompat.startForeground(this, NOTI_ID, build("준비 중…"), type)
            currentType = type
        }
        return START_NOT_STICKY
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_live", true),
            PendingIntent.FLAG_IMMUTABLE
        )
        val phase = MeetingEngine.state.value.phase
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("회의노트")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
        if (phase == Phase.RECORDING || phase == Phase.SUMMARIZING) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, MeetingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, if (phase == Phase.RECORDING) "종료하고 회의록 만들기" else "요약 중단", stop)
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}

/** 알림 갱신 기준 (정밀 인식은 1% 단위로 갱신) */
private data class NotiKey(val phase: Phase, val stage: String, val sec: Long, val refinePct: Int?)
