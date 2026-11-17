package com.meetingnote.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 오류 보고: 오류·크래시 정보를 모아 GitHub 이슈 작성 화면을 미리 채워 연다.
 * 앱에 비밀키를 넣지 않으며, 사용자가 내용을 확인한 뒤 직접 제출한다.
 *
 * 개인정보: 녹음·녹취록·회의록·회의 제목·용어 사전은 절대 포함하지 않는다.
 * (GitHub 이슈는 공개되므로 기기·앱 상태와 오류 정보만 담는다)
 */
object ErrorReporter {
    const val REPO = "lukesgood/meetingnote"
    private const val MAX_URL = 6000          // GitHub은 약 6,500자 이상 URL에 500 오류 (실측)

    private lateinit var app: Context
    private val dir get() = File(app.filesDir, "errors").apply { mkdirs() }
    private val crashFile get() = File(dir, "jvm_crash.txt")
    private val trailFile get() = File(dir, "trail.txt")
    private val lastErrorFile get() = File(dir, "last_error.txt")
    private val prefs get() = app.getSharedPreferences("error_reporter", Context.MODE_PRIVATE)
    private val timeFmt get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun install(context: Context) {
        app = context.applicationContext
        // 처음 설치 시 과거 종료 기록은 보고 대상에서 제외
        if (!prefs.contains("seen_exit_ts")) prefs.edit().putLong("seen_exit_ts", System.currentTimeMillis()).apply()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile.writeText("시각: ${timeFmt.format(Date())}\n스레드: ${t.name}\n${e.stackTraceToString().take(16_000)}")
                trail("JVM 크래시: ${e.javaClass.simpleName}")
            }
            prev?.uncaughtException(t, e)
        }
    }

    /**
     * 최근 동작 기록(최대 40줄). 네이티브 크래시는 그 순간 아무것도 기록할 수 없으므로
     * 주요 단계마다 동기적으로 파일에 남겨 '무엇을 하던 중이었는지'를 알 수 있게 한다.
     * 녹취 내용이나 제목은 넣지 않는다.
     */
    @Synchronized
    fun trail(msg: String) {
        if (!::app.isInitialized) return
        runCatching {
            val lines = (if (trailFile.exists()) trailFile.readLines() else emptyList()) + "${timeFmt.format(Date())} $msg"
            trailFile.writeText(lines.takeLast(40).joinToString("\n"))
        }
    }

    /** 처리 중 발생한(앱이 죽지 않은) 오류 */
    fun recordError(e: Throwable) {
        trail("오류: ${e.javaClass.simpleName}")
        runCatching { lastErrorFile.writeText("시각: ${timeFmt.format(Date())}\n${e.stackTraceToString().take(12_000)}") }
    }

    data class Crash(val kind: String, val timestamp: Long, val summary: String, val detail: String)

    /** 마지막으로 확인한 이후의 비정상 종료 (없으면 null) */
    fun pendingCrash(): Crash? {
        val seen = prefs.getLong("seen_exit_ts", 0)
        if (Build.VERSION.SDK_INT >= 30) {
            val am = app.getSystemService(ActivityManager::class.java)
            val info = runCatching { am.getHistoricalProcessExitReasons(null, 0, 10) }.getOrDefault(emptyList())
                .firstOrNull { it.timestamp > seen && isAbnormal(it) }
            if (info != null) return describe(info)
        }
        // API 29 이하 또는 종료 기록이 없을 때: 자체 저장한 JVM 크래시
        if (crashFile.exists() && crashFile.lastModified() > seen) {
            return Crash("앱 오류(Java/Kotlin)", crashFile.lastModified(), crashFile.readLines().getOrNull(2) ?: "", crashFile.readText())
        }
        return null
    }

    fun markCrashSeen() {
        prefs.edit().putLong("seen_exit_ts", System.currentTimeMillis()).apply()
    }

    private fun isAbnormal(i: ApplicationExitInfo): Boolean = when (i.reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> true
        // 메모리 부족 종료는 녹음/처리 중(포그라운드 서비스 이상)일 때만 의미 있음
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            i.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        else -> false
    }

    private fun describe(i: ApplicationExitInfo): Crash {
        val trace = runCatching { i.traceInputStream?.use { it.readBytes(2_000_000) } }.getOrNull()
        return when (i.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> Crash(
                "네이티브 크래시 (음성인식/요약 엔진)", i.timestamp,
                "signal ${i.status} ${signalName(i.status)}",
                buildString {
                    appendLine("설명: ${i.description ?: "-"}")
                    appendLine("메모리(PSS): ${i.pss / 1024}MB, RSS: ${i.rss / 1024}MB")
                    if (trace != null) {
                        appendLine("백트레이스 단서 (tombstone에서 추출):")
                        nativeHints(trace).forEach { appendLine("  $it") }
                    }
                },
            )
            ApplicationExitInfo.REASON_ANR -> Crash(
                "응답 없음 (ANR)", i.timestamp, i.description ?: "",
                trace?.toString(Charsets.UTF_8)?.let(::mainThreadSection) ?: "",
            )
            ApplicationExitInfo.REASON_LOW_MEMORY -> Crash(
                "메모리 부족으로 강제 종료", i.timestamp,
                "PSS ${i.pss / 1024}MB · RSS ${i.rss / 1024}MB",
                "설명: ${i.description ?: "-"}\n메모리(PSS): ${i.pss / 1024}MB, RSS: ${i.rss / 1024}MB",
            )
            else -> {
                val jvm = crashFile.takeIf { it.exists() }?.readText()
                Crash(
                    "앱 오류(Java/Kotlin)", i.timestamp,
                    jvm?.lines()?.getOrNull(2) ?: (i.description ?: ""),
                    jvm ?: "설명: ${i.description ?: "-"}",
                )
            }
        }
    }

    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf); if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun signalName(s: Int) = when (s) {
        4 -> "(SIGILL)"; 6 -> "(SIGABRT)"; 7 -> "(SIGBUS)"; 8 -> "(SIGFPE)"; 11 -> "(SIGSEGV)"; else -> ""
    }

    /**
     * Android 12+ tombstone은 protobuf라 파싱 대신 출력 가능한 문자열만 뽑아
     * 라이브러리·함수 이름, abort 메시지를 단서로 남긴다.
     */
    private fun nativeHints(bytes: ByteArray): List<String> {
        val strings = ArrayList<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) sb.append(c.toChar()) else {
                if (sb.length >= 5) strings += sb.toString()
                sb.setLength(0)
            }
        }
        val interesting = Regex("""\.so\b|ggml|whisper|llama|_jni|Java_com_meetingnote|abort|Abort|assert|GGML_|signal|SIG[A-Z]+|fault addr""")
        return strings.asSequence()
            .map { it.trim().trim('"', '{', '}', '$', '*', '(', ')', ' ').replace(Regex("^[^A-Za-z_/]{1,3}(?=[A-Za-z_/])"), "") }
            .filter { interesting.containsMatchIn(it) && !it.startsWith("/data/app/") }
            // 안드로이드 런타임 공통 프레임은 단서가 안 되므로 제외
            .filterNot { it.contains("boot.oat") || it.contains("/apex/") || it.contains("dalvik-cache") || it.startsWith("art_") }
            .map { it.replace(Regex("""/data/app/[^ ]*/lib/arm64/"""), "") }
            .distinct().take(60).toList()
    }

    private fun mainThreadSection(t: String): String {
        val start = t.indexOf("\"main\"")
        return if (start >= 0) t.substring(start).substringBefore("\n\n").take(6000) else t.take(6000)
    }

    // ---------------- 보고서 ----------------

    data class Report(val title: String, val body: String)

    /** 보고서 생성 (logcat 실행이 있어 IO 스레드에서 호출) */
    fun buildReport(crash: Crash? = null, engineError: String? = null): Report {
        val s = MeetingEngine.settings
        val pm = app.packageManager.getPackageInfo(app.packageName, 0)
        val mem = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val models = ModelCatalog.all.joinToString(", ") { "${it.title.substringBefore(" (")} ${if (MeetingEngine.models.isReady(it)) "✓" else "✗"}" }
        val free = app.getExternalFilesDir(null)?.usableSpace?.let { "%.1fGB".format(it / 1e9) } ?: "-"

        val env = buildString {
            appendLine("- 앱: ${pm.versionName} (${if (Build.VERSION.SDK_INT >= 28) pm.longVersionCode else 0})")
            appendLine("- 기기: ${Build.MANUFACTURER} ${Build.MODEL} · SoC ${soc} · CPU 코어 ${Runtime.getRuntime().availableProcessors()}개")
            appendLine("- RAM: %.1fGB (여유 %.1fGB) · 저장 공간 여유 %s".format(mem.totalMem / 1e9, mem.availMem / 1e9, free))
            appendLine("- Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("- 설정: 요약 모델 ${s.llm.title}, 스레드 ${s.threads}, 용어 사전 ${if (s.vocabulary.isBlank()) "없음" else "사용(내용 제외)"}")
            appendLine("- 모델: $models")
            appendLine("- 기기 점검: ${runCatching { DeviceCheck.summaryLine() }.getOrDefault("-")}")
            append("- 엔진 상태: ${MeetingEngine.state.value.phase}")
        }

        val error = buildString {
            if (crash != null) {
                appendLine("**${crash.kind}** · ${timeFmt.format(Date(crash.timestamp))}")
                if (crash.summary.isNotBlank()) appendLine(crash.summary)
                appendLine("```"); appendLine(crash.detail.trim().take(8000)); append("```")
            } else if (engineError != null) {
                appendLine("**처리 오류**: $engineError")
                lastErrorFile.takeIf { it.exists() }?.readText()?.let { appendLine("```"); appendLine(it.trim()); append("```") }
            } else append("(사용자가 직접 보고 — 아래 설명 참고)")
        }

        val trail = trailFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val log = readLogcat()

        val body = buildString {
            appendLine("### 환경"); appendLine(env); appendLine()
            appendLine("### 오류"); appendLine(error); appendLine()
            appendLine("### 최근 동작"); appendLine("```"); appendLine(trail.ifBlank { "(기록 없음)" }); appendLine("```"); appendLine()
            appendLine("### 앱 로그 (최근)"); appendLine("```"); appendLine(log.ifBlank { "(없음)" }); append("```")
        }
        val title = when {
            crash != null -> "[크래시] ${crash.kind}${crash.summary.takeIf { it.isNotBlank() }?.let { ": " + it.take(60) } ?: ""}"
            engineError != null -> "[오류] ${engineError.take(70)}"
            else -> "[문제 보고] "
        } + " (v${pm.versionName}, ${Build.MODEL})"
        return Report(title, body)
    }

    /** 이 앱(uid)의 로그만 읽힌다. 녹취 문장은 로그에 남기지 않도록 앱 전체에서 관리 중. */
    private fun readLogcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "250", "-v", "time", "*:I").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text.lines()
            .filterNot { it.contains("Choreographer") || it.contains("ViewRootImpl") || it.isBlank() }
            .takeLast(150).joinToString("\n").take(12_000)
    }.getOrDefault("")

    /**
     * 전체 보고서를 클립보드에 복사하고 GitHub 이슈 작성 화면을 연다.
     * URL 길이 제한 때문에 미리 채우는 내용은 잘릴 수 있으며, 그 경우 붙여넣기를 안내한다.
     */
    fun openGitHub(context: Context, r: Report) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("오류 보고", r.body))

        fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val base = "https://github.com/$REPO/issues/new?template=app_report.yml&title=${enc(r.title)}&report="
        var body = r.body
        if (base.length + enc(body).length > MAX_URL) {
            val note = "\n\n… (길이 제한으로 잘림 — 전체 보고서가 클립보드에 복사되어 있습니다. 여기에 붙여넣어 주세요)"
            // 앱 로그부터 잘라 핵심(환경·오류·최근 동작)을 남긴다
            body = body.substringBefore("### 앱 로그")
            while (base.length + enc(body + note).length > MAX_URL && body.length > 200) body = body.dropLast(200)
            body += note
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(base + enc(body))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
