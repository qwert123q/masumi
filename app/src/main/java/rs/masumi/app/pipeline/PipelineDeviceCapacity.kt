package rs.masumi.app.pipeline

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

internal object PipelineDeviceCapacity {
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
}
