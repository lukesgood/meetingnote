// 빅코어(최대 클럭이 높은 코어) 선택 및 스레드 고정 유틸리티
// Snapdragon 870: cpu4-7(A77), Dimensity 8300: cpu4-7(A715) 등 SoC 구성에 맞춰 자동 선택
#pragma once
#include <sched.h>
#include <unistd.h>
#include <algorithm>
#include <cstdio>
#include <utility>
#include <vector>

namespace mn {

inline std::vector<int> fastest_cores(int count) {
    int n = (int) sysconf(_SC_NPROCESSORS_CONF);
    std::vector<std::pair<long, int>> freq;
    for (int c = 0; c < n; ++c) {
        char path[96];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
        long f = 0;
        if (FILE * fp = fopen(path, "r")) { if (fscanf(fp, "%ld", &f) != 1) f = 0; fclose(fp); }
        freq.emplace_back(f, c);
    }
    std::stable_sort(freq.begin(), freq.end(), [](auto & a, auto & b) { return a.first > b.first; });
    std::vector<int> out;
    for (int i = 0; i < std::min(count, n); ++i) out.push_back(freq[i].second);
    return out;
}

/** 현재 스레드(및 이후 생성되는 자식 스레드)를 빠른 코어에 고정 */
inline void pin_current_thread(int count) {
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c : fastest_cores(count)) CPU_SET(c, &set);
    sched_setaffinity(0, sizeof(set), &set);
}

} // namespace mn
