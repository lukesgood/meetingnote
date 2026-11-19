package com.meetingnote.app

import com.meetingnote.whisper.WhisperNative
import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 기기 점검: 이 기기에서 앱의 각 기능이 원활히 동작할지 판정.
 *  1) 사양 확인 (즉시) — RAM, 저장 공간, 고성능 코어, CPU 가속 명령어
 *  2) 실측 (설정 > 기기 점검, 약 30초) — 음성인식 처리 속도, 요약 모델 처리 속도
 * 실측값은 기준 기기(Snapdragon 870, 실제 회의 처리 시간을 잰 기기)와 비교해 예상 시간을 계산한다.
 */
object DeviceCheck {
    enum class Level { OK, SLOW, NO, UNKNOWN }
    data class Item(val title: String, val level: Level, val detail: String)

    data class LlmBench(val promptTps: Double, val genTps: Double)
    data class Bench(val sttRtf: Double?, val llm: Map<String, LlmBench>, val at: Long, val threads: Int,
                     /** 정밀 모델(turbo)로 같은 10초를 실시간 조건으로 인식한 속도 (모델이 있을 때만) */
                     val turboRtf: Double? = null)

    // ---- 기준 기기(SD870, 스레드 4) 기기 점검 실측값 (2026-11-17, 2회 평균): 점검 결과를 실제 처리 시간으로 환산하는 데 사용
    /** 점검용 10초 음성의 처리 시간 / 음성 길이 */
    private const val REF_STT_RTF = 0.18
    private val REF_LLM = mapOf(
        ModelCatalog.QWEN3_4B.id to LlmBench(promptTps = 38.7, genTps = 7.3),
        ModelCatalog.EXAONE_1B.id to LlmBench(promptTps = 99.0, genTps = 20.0),
    )
    /** 기준 기기에서 회의 10분당 회의록 작성 시간(초) — 7.6분 회의 실측 (Qwen3 196초, EXAONE 39초) */
    private val REF_MINUTES_SEC_PER_10MIN = mapOf(ModelCatalog.QWEN3_4B.id to 258.0, ModelCatalog.EXAONE_1B.id to 51.0)
    /** 기준 기기에서 통역 한 문장 처리 시간(초) — 프롬프트 처리 42%, 생성 58% */
    private const val REF_TRANSLATE_SEC = 5.0

    private const val GB = 1_000_000_000L
    /** totalMem은 표기 용량보다 작게 나옴 (8GB → 약 7.4GB) */
    private const val RAM_6GB = 5_300_000_000L
    private const val RAM_4GB = 3_400_000_000L

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("device_check", Context.MODE_PRIVATE) }

    fun init(context: Context) { app = context.applicationContext }

    // ------------------------------------------------------------ 사양

    val totalRam: Long by lazy {
        ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    }

    private val cpuFeatures: Set<String> by lazy {
        runCatching {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet()
        }.getOrNull() ?: emptySet()
    }
    /** 정수 내적 가속(ARMv8.2 dotprod): 없으면 LLM이 2~3배 느림 */
    val hasDotprod get() = "asimddp" in cpuFeatures
    val hasI8mm get() = "i8mm" in cpuFeatures

    /** 통역·녹취에 필요한데 아직 없는 모델 용량 */
    private fun missingModelBytes() =
        listOf(ModelCatalog.WHISPER, ModelCatalog.VAD, ModelCatalog.INTERPRETER)
            .filterNot { MeetingEngine.models.isReady(it) }.sumOf { it.sizeBytes }

    private fun freeBytes() = MeetingEngine.models.dir.usableSpace

    /** 모델을 메모리에 올릴 수 있는지 (Qwen3 약 3GB, EXAONE 약 1GB + 음성인식 0.5GB) */
    fun ramOkFor(spec: ModelSpec) = totalRam >= if (spec == ModelCatalog.QWEN3_4B) RAM_6GB else RAM_4GB

    private fun gb(b: Long) = "%.1fGB".format(b / 1e9)

    private fun staticItems(): List<Item> {
        val ram = when {
            totalRam >= RAM_6GB -> Item("메모리", Level.OK, "RAM ${gb(totalRam)}")
            totalRam >= RAM_4GB -> Item("메모리", Level.SLOW,
                "RAM ${gb(totalRam)} — Qwen3와 실시간 통역은 어렵습니다. 회의록은 EXAONE을 쓰세요.")
            else -> Item("메모리", Level.NO, "RAM ${gb(totalRam)} — 회의록 작성은 어렵고 녹취만 가능합니다.")
        }
        val free = freeBytes(); val need = missingModelBytes()
        val storage = when {
            free >= need + 2 * GB -> Item("저장 공간", Level.OK,
                "남은 공간 ${gb(free)}" + if (need > 0) " (필요한 모델 ${gb(need)})" else "")
            free >= need + GB / 4 -> Item("저장 공간", Level.SLOW,
                "남은 공간 ${gb(free)} — 모델(${gb(need)})을 받으면 녹음할 공간이 적습니다. 녹음 1시간 ≈ 115MB.")
            else -> Item("저장 공간", Level.NO, "남은 공간 ${gb(free)} — 필요한 모델 ${gb(need)}를 받을 공간이 부족합니다.")
        }
        val cores = DeviceInfo.bigCores
        val accel = WhisperNative.supportsAccelerated
        val cpuText = "${DeviceInfo.chipName} · 고성능 코어 ${cores}개" + if (accel) "" else " · 가속 명령어 없음(기본 엔진)"
        val cpu = when {
            cores >= 4 && accel -> Item("프로세서", Level.OK, cpuText)
            cores >= 2 && accel -> Item("프로세서", Level.SLOW, "$cpuText — 처리 시간이 길어질 수 있습니다.")
            else -> Item("프로세서", Level.SLOW,
                "$cpuText — 이전 세대 CPU라 기본 엔진으로 동작합니다. 실시간 녹취가 밀리거나 회의록 작성이 매우 느릴 수 있으니 '성능 점검'으로 확인하세요.")
        }
        return listOf(ram, storage, cpu)
    }

    // ------------------------------------------------------------ 실측 결과

    var bench: Bench?
        get() = prefs.getString("bench", null)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                val llm = o.getJSONObject("llm").let { j ->
                    j.keys().asSequence().associateWith { k ->
                        j.getJSONObject(k).let { LlmBench(it.getDouble("p"), it.getDouble("g")) }
                    }
                }
                Bench(if (o.has("stt")) o.getDouble("stt") else null, llm, o.getLong("at"), o.getInt("threads"),
                    if (o.has("turbo")) o.getDouble("turbo") else null)
            }.getOrNull()
        }
        set(b) {
            if (b == null) { prefs.edit().remove("bench").apply(); return }
            val o = JSONObject().put("at", b.at).put("threads", b.threads)
            b.sttRtf?.let { o.put("stt", it) }
            b.turboRtf?.let { o.put("turbo", it) }
            o.put("llm", JSONObject().apply { b.llm.forEach { (k, v) -> put(k, JSONObject().put("p", v.promptTps).put("g", v.genTps)) } })
            prefs.edit().putString("bench", o.toString()).apply()
        }

    /**
     * 실시간 인식에 turbo를 써도 될 만큼 빠른지.
     * 실측이 없으면 small 실측의 3배로 추정 (PC·SD870에서 turbo는 small의 약 3배 시간).
     * 0.7 이하: 10초 발언을 약 7초 안에 처리 → 회의 내내 밀리지 않음 (발열로 느려질 여유 포함)
     */
    fun turboLiveOk(): Boolean {
        val b = bench ?: return false
        val est = b.turboRtf ?: b.sttRtf?.times(TURBO_SMALL_RATIO) ?: return false
        return est <= TURBO_LIVE_MAX_RTF
    }
    const val TURBO_SMALL_RATIO = 3.0
    const val TURBO_LIVE_MAX_RTF = 0.7

    /** 요약 모델의 처리 속도: 기기 점검 실측값, 없으면 기준 기기 값 */
    fun llmSpeed(spec: ModelSpec): LlmBench =
        bench?.llm?.get(spec.id) ?: REF_LLM[spec.id] ?: LlmBench(30.0, 6.0)

    /** 처리 시간 배율 (1 = 기준 기기와 같음, 2 = 두 배 걸림) */
    private fun llmFactor(id: String, b: LlmBench, promptShare: Double): Double {
        val ref = REF_LLM.getValue(id)
        return promptShare * ref.promptTps / b.promptTps + (1 - promptShare) * ref.genTps / b.genTps
    }

    private fun minutesText(sec: Double) = if (sec < 90) "약 ${sec.toInt()}초" else "약 ${(sec / 60).toInt().coerceAtLeast(1)}분"

    private fun benchItems(b: Bench?): List<Item> {
        val items = mutableListOf<Item>()
        val notRun = "기기 점검을 실행하면 확인할 수 있습니다."
        // 실시간 녹취
        val rtf = b?.sttRtf
        items += when {
            rtf == null -> Item("실시간 녹취", Level.UNKNOWN,
                if (b == null) notRun else "음성인식 모델을 받은 뒤 다시 점검해 주세요.")
            rtf <= 0.4 -> Item("실시간 녹취", Level.OK, "원활 · 말한 뒤 약 ${(1 + rtf * 10).toInt() + 1}초 후 표시")
            rtf <= 0.8 -> Item("실시간 녹취", Level.SLOW, "가능 · 말한 뒤 약 ${(1 + rtf * 10).toInt() + 1}초 후 표시 (긴 발언에서 밀릴 수 있음)")
            else -> Item("실시간 녹취", Level.NO,
                "처리가 말 속도를 따라가지 못합니다 (10초 음성에 ${"%.1f".format(rtf * 10)}초). 녹음 후 일괄 인식은 가능합니다.")
        }
        // 실시간 녹취에 정밀 모델(turbo)을 쓸 수 있는지 (느려도 small로 녹취하므로 문제 항목은 아님)
        b?.turboRtf?.let { t ->
            items += Item("실시간 녹취 · 정밀 모델", Level.OK,
                if (t <= TURBO_LIVE_MAX_RTF) "사용 · 10초 음성에 ${"%.1f".format(t * 10)}초 (통역 중에는 기본 모델)"
                else "느려서 기본 모델 사용 (10초 음성에 ${"%.1f".format(t * 10)}초)")
        }
        // 실시간 통역 (항상 Qwen3)
        val q = ModelCatalog.INTERPRETER
        val qb = b?.llm?.get(q.id)
        items += when {
            !ramOkFor(q) -> Item("실시간 통역", Level.NO, "메모리가 부족합니다 (RAM 6GB 이상 필요).")
            qb == null || rtf == null -> Item("실시간 통역", Level.UNKNOWN,
                if (b == null) notRun else "${q.title.substringBefore(" (")}와 음성인식 모델을 받은 뒤 다시 점검해 주세요.")
            else -> {
                val sec = REF_TRANSLATE_SEC * llmFactor(q.id, qb, 0.42)
                // 약 8초에 한 문장이 나온다고 보고, 음성인식과 번역이 한 스레드를 나눠 쓰는 부하
                val load = rtf + sec / 8.0
                when {
                    load <= 0.85 -> Item("실시간 통역", Level.OK, "가능 · 문장당 약 ${"%.0f".format(sec)}초")
                    load <= 1.2 -> Item("실시간 통역", Level.SLOW, "느림 · 문장당 약 ${"%.0f".format(sec)}초 (말이 빠르면 번역이 밀림)")
                    else -> Item("실시간 통역", Level.NO, "번역이 말 속도를 따라가지 못합니다 (문장당 약 ${"%.0f".format(sec)}초).")
                }
            }
        }
        // 회의록 (모델별)
        ModelCatalog.llms.forEach { spec ->
            val name = "회의록 · ${spec.title.substringBefore(" (").substringBefore(" Instruct")}"
            val lb = b?.llm?.get(spec.id)
            items += when {
                !ramOkFor(spec) -> Item(name, Level.NO, "메모리가 부족합니다 (RAM ${if (spec == ModelCatalog.QWEN3_4B) "6" else "4"}GB 이상 필요).")
                lb == null -> Item(name, Level.UNKNOWN, if (b == null) notRun else "모델을 받은 뒤 다시 점검해 주세요.")
                else -> {
                    val perHour = REF_MINUTES_SEC_PER_10MIN.getValue(spec.id) * 6 * llmFactor(spec.id, lb, 0.7)
                    Item(name, if (perHour <= 40 * 60) Level.OK else Level.SLOW,
                        "1시간 회의 기준 ${minutesText(perHour)} (말이 많을수록 길어짐)")
                }
            }
        }
        return items
    }

    fun evaluate(): List<Item> = staticItems() + benchItems(bench)

    /** 홈 화면 경고: 사용 불가·느림 항목 (점검 전 항목은 제외) */
    fun problems(): List<Item> = evaluate().filter { it.level == Level.NO || it.level == Level.SLOW }

    /** 경고를 닫은 상태 (같은 문제 조합이면 다시 띄우지 않음) */
    fun isDismissed(problems: List<Item>) = prefs.getString("dismissed", null) == signature(problems)
    fun dismiss(problems: List<Item>) = prefs.edit().putString("dismissed", signature(problems)).apply()
    private fun signature(p: List<Item>) = p.joinToString("|") { "${it.title}:${it.level}" }

    /** 점검을 권할지: 필요한 모델은 있는데 아직 한 번도 점검하지 않음 */
    fun shouldSuggestBench() = bench == null && !prefs.getBoolean("suggest_dismissed", false) &&
        MeetingEngine.missingSttModels().isEmpty() && ModelCatalog.llms.any { MeetingEngine.models.isReady(it) }
    fun dismissSuggestion() = prefs.edit().putBoolean("suggest_dismissed", true).apply()

    /** 오류 보고용 한 줄 요약 */
    fun summaryLine(): String {
        val b = bench
        val dot = (if (hasDotprod) "dotprod" else "no-dotprod") +
            " · 엔진 ${WhisperNative.loaded ?: (if (WhisperNative.supportsAccelerated) "dp" else "base")}"
        val i8 = if (hasI8mm) " i8mm" else ""
        val bt = b?.let { x ->
            " · 실측(스레드 ${x.threads}): STT ${x.sttRtf?.let { "%.2f".format(it) } ?: "-"}x" +
                " turbo ${x.turboRtf?.let { "%.2f".format(it) } ?: "-"}x, " +
                x.llm.entries.joinToString(", ") { (k, v) -> "$k ${"%.0f".format(v.promptTps)}/${"%.1f".format(v.genTps)} tok/s" }
        } ?: " · 실측 없음"
        return "$dot$i8$bt"
    }

    // ------------------------------------------------------------ 실측 자료

    /** 점검용 한국어 음성 10초 (16kHz mono PCM16) */
    fun loadSample(): FloatArray {
        val bytes = app.assets.open("bench_ko.pcm").use { it.readBytes() }
        return FloatArray(bytes.size / 2) { i ->
            ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
        }
    }

    /** 요약 모델 점검용 입력 (실제 회의록 작업과 비슷한 길이·언어) */
    const val LLM_BENCH_TEXT = """다음 회의 녹취를 세 문장으로 요약해 주세요.
김팀장: 자, 그럼 주간 회의 시작하겠습니다. 오늘 안건은 신제품 출시 일정과 마케팅 예산입니다.
이대리: 네, 개발 쪽은 베타 테스트가 지난주에 끝났고, 발견된 버그 열두 건 중 아홉 건을 수정했습니다. 나머지 세 건은 다음 주 수요일까지 처리할 예정입니다.
김팀장: 그러면 출시일은 예정대로 십이월 일일로 가도 될까요?
박과장: 마케팅 입장에서는 십이월 일일이 좋습니다. 다만 광고 예산이 현재 이천만 원인데, 온라인 광고를 늘리려면 오백만 원 정도 추가가 필요합니다.
김팀장: 추가 예산은 제가 이번 주 금요일까지 본부장님께 보고해서 승인 받겠습니다.
이대리: 그리고 출시 전에 고객 지원팀 대상 교육이 필요할 것 같습니다.
박과장: 교육 자료는 제가 다음 주 월요일까지 만들어서 공유하겠습니다."""
}
