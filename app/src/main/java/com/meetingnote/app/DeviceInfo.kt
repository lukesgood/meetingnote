package com.meetingnote.app

import android.os.Build
import java.io.File

/** 기기별 안내·기본값을 위한 하드웨어 정보 (고정 문구 대신 실제 기기 기준) */
object DeviceInfo {
    /** 코어별 최대 클럭 (kHz) */
    private val maxFreqs: List<Long> by lazy {
        (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
    }

    /**
     * 효율 코어 설계 (CPU part): ARM Cortex-A32/A35/A53/A55/A510/A520,
     * Qualcomm Kryo Silver 0x801(A53)·0x803(A55)·0x805(A55, SD855~870 — 태블릿 실측)
     */
    private val LITTLE_PARTS = setOf(0xd01, 0xd04, 0xd03, 0xd05, 0xd46, 0xd80, 0x801, 0x803, 0x805)

    /** 코어별 CPU part (/proc/cpuinfo). 읽지 못하면 빈 목록 */
    private val cpuParts: List<Int> by lazy {
        runCatching {
            File("/proc/cpuinfo").readLines().filter { it.startsWith("CPU part") }
                .map { Integer.decode(it.substringAfter(':').trim()) }
        }.getOrDefault(emptyList())
    }

    /**
     * 고성능 코어 수: 효율 코어 설계(A55 등)를 뺀 코어.
     * 클럭만으로 나누면 Snapdragon 8 Elite(4.3GHz 2개 + 3.5GHz 6개, 효율 코어 없음)를 2개로 잘못 셈 (2026-11-23 실제 기기).
     * CPU part를 못 읽으면 가장 낮은 클럭 그룹을 효율 코어로 보고, 그것도 없으면 전체의 절반.
     */
    val bigCores: Int by lazy {
        val parts = cpuParts
        if (parts.size == Runtime.getRuntime().availableProcessors()) {
            val big = parts.count { it !in LITTLE_PARTS }
            if (big > 0) return@lazy big
        }
        val f = maxFreqs
        if (f.isEmpty()) return@lazy (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        val min = f.min()
        val big = f.count { it > min * 1.1 }
        if (big == 0) f.size else big
    }

    /** 권장 스레드 = 고성능 코어 수 (효율 코어를 섞으면 오히려 느려짐: SD870에서 8스레드 < 4스레드 실측) */
    val recommendedThreads: Int get() = bigCores.coerceIn(2, 8)

    /** 사람이 읽을 수 있는 칩 이름 */
    val chipName: String by lazy {
        val model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        val friendly = mapOf(
            "SM8250" to "Snapdragon 865/870", "SM8350" to "Snapdragon 888", "SM8450" to "Snapdragon 8 Gen 1",
            "SM8475" to "Snapdragon 8+ Gen 1", "SM8550" to "Snapdragon 8 Gen 2", "SM8650" to "Snapdragon 8 Gen 3",
            "SM8750" to "Snapdragon 8 Elite", "SM7675" to "Snapdragon 7+ Gen 3", "SM7550" to "Snapdragon 7 Gen 3",
            "MT6897" to "Dimensity 8300", "MT6989" to "Dimensity 9300", "MT6991" to "Dimensity 9400",
            "MT6878" to "Dimensity 7300", "MT6896" to "Dimensity 8200",
            // MediaTek: SOC_MODEL(안드로이드 12+) 또는 HARDWARE(이전)는 MTxxxx 형태라 판매명으로 바꿔 표시
            "MT6762" to "Helio P22", "MT6765" to "Helio P35/G35/G37", "MT6768" to "Helio P65",
            "MT6769" to "Helio G70/G80/G85/G88", "MT6771" to "Helio P60/P70", "MT6779" to "Helio P90",
            "MT6781" to "Helio G96", "MT6785" to "Helio G90/G95", "MT6789" to "Helio G99",
            "MT6833" to "Dimensity 700/810/6020/6080", "MT6835" to "Dimensity 6100+/6300", "MT6853" to "Dimensity 720/800U",
            "MT6873" to "Dimensity 800", "MT6875" to "Dimensity 820", "MT6877" to "Dimensity 900/920/1080/7050",
            "MT6885" to "Dimensity 1000", "MT6886" to "Dimensity 7200", "MT6889" to "Dimensity 1000+",
            "MT6891" to "Dimensity 1100/8020", "MT6893" to "Dimensity 1200/1300/8050", "MT6895" to "Dimensity 8100",
            "MT6983" to "Dimensity 9000", "MT6985" to "Dimensity 9200",
        )
        val up = model.uppercase()
        // MediaTek은 "MT6789V/CD", "mt6771"처럼 뒤에 변형 표기가 붙거나 소문자로 오기도 함
        (friendly[up] ?: Regex("MT\\d{4}").find(up)?.value?.let { friendly[it] }) ?: if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} $model" else model
    }

    /** 실측 기기 여부 (벤치마크 수치를 그대로 안내할 수 있는지) */
    val isBenchmarkedDevice: Boolean get() = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.equals("SM8250", true)
}
