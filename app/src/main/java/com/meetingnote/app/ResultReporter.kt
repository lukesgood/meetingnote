package com.meetingnote.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기기 점검 결과 보내기: 로그인 없이 구글 설문지에 응답으로 제출 → 개발자의 구글 시트에 한 줄씩 쌓인다.
 * 기기 정보와 속도 숫자만 보낸다 (녹음·녹취록·회의록·용어 사전 등 회의 내용은 넣지 않음).
 * 사용자가 내용을 확인하고 '보내기'를 누를 때만 전송한다.
 */
object ResultReporter {
    /** 설문지 응답 제출 주소 (…/formResponse). 비어 있으면 결과 보내기 버튼을 숨긴다 */
    private const val FORM_URL = ""
    /** 질문별 입력 이름 (entry.숫자): 기기, 칩, 앱 버전, 결과 */
    private const val ENTRY_DEVICE = ""
    private const val ENTRY_CHIP = ""
    private const val ENTRY_VERSION = ""
    private const val ENTRY_RESULT = ""

    val enabled: Boolean get() = FORM_URL.isNotEmpty() && ENTRY_RESULT.isNotEmpty()

    data class Report(val device: String, val chip: String, val version: String, val result: String)

    fun build(ctx: Context): Report {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "-"
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        val chip = "${DeviceInfo.chipName} · ${GpuSupport.soc.ifEmpty { Build.HARDWARE }} · ${GpuSupport.renderer.ifEmpty { "그래픽 칩 정보 없음" }}"
        val mem = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val b = DeviceCheck.bench
        val result = buildString {
            appendLine("점검 시각: ${b?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREAN).format(Date(it.at)) } ?: "실측 없음"}")
            appendLine("RAM %.1fGB · 고성능 코어 %d개 · 코어 %d개 · 스레드 설정 %d".format(
                mem.totalMem / 1e9, DeviceInfo.bigCores, Runtime.getRuntime().availableProcessors(), MeetingEngine.settings.threads))
            if (b != null) {
                appendLine("음성 인식(10초 음성 처리 시간 비율): 기본 ${b.sttRtf?.let { "%.2f".format(it) } ?: "-"} · 정밀 ${b.turboRtf?.let { "%.2f".format(it) } ?: "-"}")
                b.llm.forEach { (k, v) -> appendLine("회의록 모델 $k: 읽기 %.0f / 쓰기 %.1f 토큰/초".format(v.promptTps, v.genTps)) }
            }
            DeviceCheck.accelLog.takeIf { it.isNotEmpty() }?.let { appendLine("장치별: $it") }
            appendLine("고른 장치: ${GpuSupport.accelName(GpuSupport.accel)} · 엔진 ${com.meetingnote.llama.LlamaNative.loaded ?: "아직 안 불러옴"}" +
                (com.meetingnote.llama.LlamaNative.gpuLoadError?.let { " · 가속 엔진 오류: ${it.take(200)}" } ?: "") +
                (if (GpuSupport.failed) " · 그래픽 칩 사용 중 종료 기록" else ""))
            appendLine("점검 항목:")
            DeviceCheck.evaluate().forEach { appendLine("- ${it.title}: ${it.level} · ${it.detail}") }
            append("상세: ${DeviceCheck.summaryLine()}")
        }
        return Report(device, chip, version, result)
    }

    /** 설문지에 제출 (네트워크, 백그라운드에서 호출). 실패하면 예외 */
    fun send(r: Report) {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val body = listOf(ENTRY_DEVICE to r.device, ENTRY_CHIP to r.chip, ENTRY_VERSION to r.version, ENTRY_RESULT to r.result)
            .filter { it.first.isNotEmpty() }.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val c = URL(FORM_URL).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            // 구글 설문지는 제출 성공 시 200 또는 확인 페이지로 이동(3xx)
            check(code in 200..399) { "전송 실패 (응답 $code)" }
        } finally { c.disconnect() }
    }
}
