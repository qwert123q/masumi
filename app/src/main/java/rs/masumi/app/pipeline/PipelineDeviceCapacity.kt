package rs.masumi.app.pipeline

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

internal object PipelineDeviceCapacity {
    data class OcrPlan(
        val engineCount: Int,
        val threadsPerEngine: Int,
    ) {
        init {
            require(engineCount in 1..2)
            require(threadsPerEngine in 1..6)
        }
    }

    fun ocrPlan(context: Context): OcrPlan {
        val memoryInfo = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        val thermalSevere = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.getSystemService(PowerManager::class.java).currentThermalStatus >=
            PowerManager.THERMAL_STATUS_SEVERE
        return ocrPlan(
            totalMemoryBytes = memoryInfo.totalMem,
            processorCount = Runtime.getRuntime().availableProcessors(),
            thermalSevere = thermalSevere,
        )
    }

    fun ocrPlan(
        totalMemoryBytes: Long,
        processorCount: Int,
        thermalSevere: Boolean,
    ): OcrPlan {
        val dualEngine = !thermalSevere &&
            totalMemoryBytes >= MINIMUM_MEMORY_FOR_TWO_OCR_ENGINES &&
            processorCount >= MINIMUM_PROCESSORS_FOR_TWO_OCR_ENGINES
        return if (dualEngine) {
            OcrPlan(
                engineCount = 2,
                threadsPerEngine = (processorCount / 2)
                    .coerceIn(1, MAXIMUM_THREADS_PER_DUAL_OCR_ENGINE),
            )
        } else {
            OcrPlan(
                engineCount = 1,
                threadsPerEngine = processorCount.coerceIn(1, MAXIMUM_THREADS_PER_SINGLE_OCR_ENGINE),
            )
        }
    }

    fun translationSlots(context: Context): Int {
        val memoryInfo = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        val thermalSevere = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.getSystemService(PowerManager::class.java).currentThermalStatus >=
            PowerManager.THERMAL_STATUS_SEVERE
        return translationSlots(
            totalMemoryBytes = memoryInfo.totalMem,
            processorCount = Runtime.getRuntime().availableProcessors(),
            thermalSevere = thermalSevere,
        )
    }

    fun translationSlots(
        totalMemoryBytes: Long,
        processorCount: Int,
        thermalSevere: Boolean,
    ): Int = if (
        !thermalSevere &&
        totalMemoryBytes >= MINIMUM_MEMORY_FOR_TWO_TRANSLATIONS &&
        processorCount >= MINIMUM_PROCESSORS_FOR_TWO_TRANSLATIONS
    ) {
        2
    } else {
        1
    }

    private const val MINIMUM_MEMORY_FOR_TWO_TRANSLATIONS = 6L * 1_024L * 1_024L * 1_024L
    private const val MINIMUM_PROCESSORS_FOR_TWO_TRANSLATIONS = 6
    private const val MINIMUM_MEMORY_FOR_TWO_OCR_ENGINES = 7L * 1_024L * 1_024L * 1_024L
    private const val MINIMUM_PROCESSORS_FOR_TWO_OCR_ENGINES = 8
    private const val MAXIMUM_THREADS_PER_DUAL_OCR_ENGINE = 4
    private const val MAXIMUM_THREADS_PER_SINGLE_OCR_ENGINE = 6
}
