package rs.masumi.app.quality

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.quality.QualityJobStatus

@RunWith(AndroidJUnit4::class)
class QualityStatusBroadcastTest {
    @Test
    fun roundTripContainsOnlySafeProgressFields() {
        val progress = QualityProgress(
            projectId = "project-1",
            jobId = "quality-job",
            runArtifactKey = "a".repeat(64),
            status = QualityJobStatus.SUCCEEDED_WITH_WARNINGS,
            terminalPageCount = 3,
            totalPageCount = 3,
            warningPageCount = 1,
            blockedPageCount = 0,
            warningCount = 2,
            blockingCount = 0,
        )

        val intent = QualityStatusBroadcast.create("rs.masumi.app.dev", progress)

        assertEquals(QualityStatusBroadcast.SAFE_EXTRA_KEYS, intent.extras?.keySet())
        assertEquals(progress, QualityStatusBroadcast.parse(intent))
    }

    @Test
    fun rejectsUnexpectedFields() {
        val intent = QualityStatusBroadcast.create(
            "rs.masumi.app.dev",
            QualityProgress(
                "project-1",
                "quality-job",
                "a".repeat(64),
                QualityJobStatus.RUNNING,
                0,
                1,
                0,
                0,
                0,
                0,
            ),
        ).putExtra("private_value", "not allowed")

        assertNull(QualityStatusBroadcast.parse(intent))
    }
}
