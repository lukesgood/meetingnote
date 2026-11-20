package com.meetingnote.app

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import com.meetingnote.llama.LlamaNative

/**
 * 그래픽 칩(GPU)·AI 전용 칩(NPU)으로 회의록·통역 모델 돌리기.
 *
 * AI 전용 칩: llama.cpp Hexagon 백엔드가 지원하는 v73(SD 8 Gen 2)·v75(8 Gen 3)·v79(8 Elite)·v81(8 Elite Gen 5).
 * 칩 이름(Build.SOC_MODEL)으로 판단하고, 칩 쪽 코드(libggml-htp-v*.so)는 앱 저장소로 복사해 경로를 알려 준다.
 *
 * llama.cpp OpenCL 백엔드가 공식 지원하는 Adreno 750(SD 8 Gen 3)·810(SD 7s Gen 3)·830(SD 8 Elite)·840(SD 8 Elite Gen 5)
 * 기기에서만 그래픽 칩 판 엔진(llama_jni_gpu)을 불러온다. 다른 Adreno(650·710 실측)는 OpenCL 초기화·커널 컴파일 중
 * 드라이버가 앱을 강제 종료하므로 시도조차 하지 않는다.
 *  - 기기 점검에서 같은 모델을 CPU와 그래픽 칩으로 재서 빠른 쪽을 저장 (점검 전에는 CPU)
 *  - 그래픽 칩 판을 처음 쓰는 동안 앱이 죽으면 다음 실행에서 알아채고 그 기기에서는 다시 쓰지 않는다
 */
object GpuSupport {
    private const val TAG = "GpuSupport"
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("gpu", Context.MODE_PRIVATE) }

    /** llama.cpp 공식 지원 목록 (docs/backend/OPENCL.md) */
    private val SUPPORTED = Regex("Adreno[^0-9]*(750|810|830|840)\\b")
    /** Hexagon v73 이상 칩 (SM8550 8 Gen 2, SM8650 8 Gen 3, SM8635 8s Gen 3, SM8750 8 Elite, SM8850 8 Elite Gen 5) */
    private val NPU_SOCS = setOf("SM8550", "SM8650", "SM8635", "SM8750", "SM8850")

    const val CPU = 0
    const val GPU = 1
    const val NPU = 2

    /** 앱 시작 시 (엔진을 처음 불러오기 전에) 호출 */
    fun init(context: Context) {
        app = context.applicationContext
        if (prefs.getBoolean("inflight", false)) {
            // 지난 실행이 그래픽 칩을 쓰는 도중에 끝남 → 이 기기에서는 쓰지 않음
            prefs.edit().putBoolean("inflight", false).putBoolean("failed", true).apply()
            Log.w(TAG, "previous GPU attempt did not finish; GPU engine disabled")
            ErrorReporter.trail("그래픽 칩 사용 중 앱 종료 감지 → 그래픽 칩 사용 안 함")
        }
        LlamaNative.useGpuEngine = (supported || npuSupported) && !failed
        Log.i(TAG, "renderer='$renderer' soc='$soc' gpu=$supported npu=$npuSupported failed=$failed -> accel engine ${LlamaNative.useGpuEngine}")
        if (LlamaNative.useGpuEngine) runCatching {
            // 그래픽 칩 커널 컴파일 결과를 앱 캐시에 보관 (없으면 모델을 불러올 때마다 다시 컴파일해 느림)
            android.system.Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", java.io.File(app.cacheDir, "cl-cache").path, true)
            // AI 칩 쪽 코드: 앱에 들어 있으면(assets/htp) 앱 저장소에 풀어 두고 경로를 알려 줌
            prepareHtpLibs()?.let { android.system.Os.setenv("GGML_HEXAGON_LIBHTP_DIR", it.path, true) }
        }
    }

    /** 칩 이름 (Android 12+) */
    val soc: String get() = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL ?: "" else ""
    val npuSupported: Boolean get() = soc in NPU_SOCS

    /** assets/htp의 AI 칩용 라이브러리를 앱 저장소로 복사 (앱 버전이 바뀔 때만) */
    private fun prepareHtpLibs(): java.io.File? {
        val names = runCatching { app.assets.list("htp")?.toList() }.getOrNull().orEmpty()
        if (names.isEmpty()) return null
        val dir = java.io.File(app.filesDir, "htp").apply { mkdirs() }
        val ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(0L).toString()
        val stamp = java.io.File(dir, ".version")
        if (stamp.takeIf { it.exists() }?.readText() != ver) {
            names.forEach { n -> app.assets.open("htp/$n").use { i -> java.io.File(dir, n).outputStream().use { o -> i.copyTo(o) } } }
            stamp.writeText(ver)
        }
        return dir
    }

    /** 그래픽 칩 이름 (예: "Adreno (TM) 750"). 처음 한 번 OpenGL ES로 읽어 저장 */
    val renderer: String by lazy {
        prefs.getString("renderer", null) ?: (readRenderer() ?: "").also { prefs.edit().putString("renderer", it).apply() }
    }

    val supported: Boolean get() = SUPPORTED.containsMatchIn(renderer)
    val failed: Boolean get() = prefs.getBoolean("failed", false)
    /** 그래픽 칩 판 엔진을 불러왔는지 */
    val engineLoaded: Boolean get() = LlamaNative.loaded == "gpu"

    /** 기기 점검 결과로 고른 장치 (CPU / GPU / NPU). 가속 판 엔진이 아니면 항상 CPU */
    var accel: Int
        get() = if (engineLoaded) prefs.getInt("accel", CPU) else CPU
        set(v) { prefs.edit().putInt("accel", v).apply() }
    val useGpu: Boolean get() = accel != CPU

    fun accelName(a: Int) = when (a) { GPU -> "그래픽 칩"; NPU -> "AI 칩"; else -> "CPU" }

    /** 그래픽 칩 판 엔진을 쓰는 작업을 감싸 둔다: 도중에 앱이 죽으면 다음 실행에서 그래픽 칩을 끈다 */
    fun <T> guard(block: () -> T): T {
        if (!LlamaNative.useGpuEngine) return block()
        prefs.edit().putBoolean("inflight", true).commit()   // 죽기 전에 기록되도록 즉시 저장
        try { return block() } finally { prefs.edit().putBoolean("inflight", false).commit() }
    }

    /** 기기 점검 화면용 한 줄 설명 */
    fun statusText(): String {
        val chip = listOf(soc, renderer).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "칩 정보 없음" }
        return when {
            !supported && !npuSupported -> "$chip — 그래픽 칩·AI 칩 지원 대상이 아니어서 CPU만 씁니다 (스냅드래곤 8 2세대 이상 지원)."
            failed -> "$chip — 점검 중 오류가 있어 CPU만 씁니다."
            !engineLoaded -> "$chip — 가속 엔진을 불러오지 못해 CPU만 씁니다."
            accel != CPU -> "$chip — 회의록·통역에 ${accelName(accel)}을 씁니다."
            else -> "$chip — 기기 점검에서 CPU가 가장 빨라 CPU를 씁니다."
        }
    }

    /** 화면 없이 OpenGL ES 컨텍스트를 잠깐 만들어 GL_RENDERER를 읽음 */
    private fun readRenderer(): String? = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null
        try {
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            val attr = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return null
            val ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            val r = GLES20.glGetString(GLES20.GL_RENDERER)
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx)
            r
        } finally { EGL14.eglTerminate(dpy) }
    }.onFailure { Log.w(TAG, "renderer read failed", it) }.getOrNull()
}
