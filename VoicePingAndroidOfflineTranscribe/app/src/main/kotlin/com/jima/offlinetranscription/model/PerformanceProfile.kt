package com.voiceping.offlinetranscription.model

/** User-visible execution policy. Thread counts are derived from the device, never hard-coded. */
enum class PerformanceProfile {
    ECO,
    BALANCED,
    MAX_PERFORMANCE,
    BENCHMARK;

    fun recommendedCpuThreads(availableProcessors: Int): Int {
        val cores = availableProcessors.coerceAtLeast(1)
        return when (this) {
            ECO -> (cores / 2).coerceAtLeast(1)
            BALANCED -> (cores * 3 / 4).coerceIn(1, cores)
            MAX_PERFORMANCE, BENCHMARK -> cores
        }
    }
}
