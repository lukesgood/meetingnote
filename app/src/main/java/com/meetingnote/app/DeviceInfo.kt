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

    /** 고성능 코어 수: 가장 낮은 클럭 그룹(효율 코어)을 제외한 코어. 정보를 못 읽으면 전체의 절반 */
    val bigCores: Int by lazy {
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
        )
        friendly[model.uppercase()] ?: if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} $model" else model
    }

    /** 실측 기기 여부 (벤치마크 수치를 그대로 안내할 수 있는지) */
    val isBenchmarkedDevice: Boolean get() = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.equals("SM8250", true)
}
