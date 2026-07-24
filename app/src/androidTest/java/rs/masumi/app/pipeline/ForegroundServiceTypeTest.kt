package rs.masumi.app.pipeline

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.cleanup.CleanupForegroundService
import rs.masumi.app.detection.DetectionForegroundService
import rs.masumi.app.exporting.ExportForegroundService
import rs.masumi.app.ocr.OcrForegroundService
import rs.masumi.app.quality.QualityForegroundService
import rs.masumi.app.translation.TranslationForegroundService
import rs.masumi.app.typesetting.TypesettingForegroundService

@RunWith(AndroidJUnit4::class)
class ForegroundServiceTypeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun persistentLocalProcessingDoesNotConsumeDataSyncQuota() {
        listOf(
            PipelineSchedulerService::class.java,
            DetectionForegroundService::class.java,
            OcrForegroundService::class.java,
            CleanupForegroundService::class.java,
            TypesettingForegroundService::class.java,
            QualityForegroundService::class.java,
        ).forEach { service ->
            assertEquals(
                service.name,
                ServiceInfoCompat.SPECIAL_USE,
                foregroundServiceType(service),
            )
        }
    }

    @Test
    fun networkTranslationAndExportRemainDataSyncServices() {
        listOf(
            TranslationForegroundService::class.java,
            ExportForegroundService::class.java,
        ).forEach { service ->
            assertEquals(
                service.name,
                ServiceInfoCompat.DATA_SYNC,
                foregroundServiceType(service),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun foregroundServiceType(service: Class<out Service>): Int =
        context.packageManager.getServiceInfo(
            ComponentName(context, service),
            PackageManager.GET_META_DATA,
        ).foregroundServiceType

    private object ServiceInfoCompat {
        const val DATA_SYNC = 1
        const val SPECIAL_USE = 1 shl 30
    }
}
