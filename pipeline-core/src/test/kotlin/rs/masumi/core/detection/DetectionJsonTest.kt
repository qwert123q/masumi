package rs.masumi.core.detection

import kotlinx.serialization.SerializationException
import rs.masumi.core.serialization.DetectionJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DetectionJsonTest {
    @Test
    fun `free text protection survives json round trip`() {
        val artifact = fixturePageArtifact()
        val codec = DetectionJson()

        assertEquals(
            artifact,
            codec.decodePageArtifact(codec.encodePageArtifact(artifact)),
        )
    }

    @Test
    fun `unknown page artifact field is rejected`() {
        val codec = DetectionJson()
        val encoded = codec.encodePageArtifact(fixturePageArtifact())
        val changed = encoded.replaceFirst("{", "{\"unexpected\":true,")

        assertFailsWith<SerializationException> {
            codec.decodePageArtifact(changed)
        }
    }

    @Test
    fun `job run and report documents round trip`() {
        val pageArtifact = fixturePageArtifact()
        val page = DetectionJobPage(
            order = 0,
            pageId = pageArtifact.pageId,
            pageArtifactKey = pageArtifact.pageArtifactKey,
        )
        val job = DetectionJobRecord(
            jobId = "job-1",
            projectId = "project-1",
            runArtifactKey = "d".repeat(64),
            startedAtEpochMillis = 100,
            updatedAtEpochMillis = 100,
            model = pageArtifact.model,
            preprocessing = pageArtifact.preprocessing,
            thresholds = pageArtifact.thresholds,
            pages = listOf(page),
        )
        val run = DetectionRunArtifact(
            runArtifactKey = job.runArtifactKey,
            projectId = job.projectId,
            createdAtEpochMillis = 200,
            model = job.model,
            preprocessing = job.preprocessing,
            thresholds = job.thresholds,
            entries = listOf(
                DetectionRunEntry(
                    order = 0,
                    pageId = page.pageId,
                    pageArtifactKey = page.pageArtifactKey,
                    state = DetectionPageState.COMMITTED,
                    regionsPath = "pages/${page.pageId}/regions.json",
                    previewPath = "previews/0000.png",
                ),
            ),
        )
        val report = DetectionReport(
            jobId = job.jobId,
            projectId = job.projectId,
            runArtifactKey = job.runArtifactKey,
            startedAtEpochMillis = 100,
            finishedAtEpochMillis = 200,
            status = DetectionJobStatus.SUCCEEDED,
            totalPageCount = 1,
            committedPageCount = 1,
            preservedPageCount = 0,
            retryCount = 0,
            classCounts = mapOf("TEXT_FREE" to 1),
            confidenceBuckets = mapOf("0.75-1.00" to 1),
        )
        val codec = DetectionJson()

        assertEquals(job, codec.decodeJob(codec.encodeJob(job)))
        assertEquals(run, codec.decodeRun(codec.encodeRun(run)))
        assertEquals(report, codec.decodeReport(codec.encodeReport(report)))
        assertTrue(codec.encodeReport(report).contains("\"durationMillis\": 100"))
    }

    private fun fixturePageArtifact(): PageDetectionArtifact {
        val sourceSha = "a".repeat(64)
        val artifactKey = "b".repeat(64)
        val model = DetectorModelRef(
            modelId = "public/model",
            repository = "public/model",
            revision = "revision-1",
            fileName = "model.onnx",
            sha256 = "c".repeat(64),
            byteLength = 100,
            license = "Apache-2.0",
            opset = 18,
            runtimeRevision = "runtime:1",
        )
        val preprocessing = DetectionPreprocessingConfig()
        val thresholds = DetectionThresholdConfig()

        return PageDetectionArtifact(
            pageId = sourceSha,
            sourceSha256 = sourceSha,
            pageArtifactKey = artifactKey,
            visibleWidth = 100,
            visibleHeight = 200,
            orientation = VisibleOrientation.NORMAL,
            model = model,
            preprocessing = preprocessing,
            thresholds = thresholds,
            rawQueries = listOf(
                RawQueryRecord(
                    queryIndex = 2,
                    label = 2,
                    score = 0.75,
                    rawBox = listOf(1.0, 2.0, 30.0, 40.0),
                    validation = RawQueryValidation.ACCEPTED,
                ),
            ),
            bubbleCandidates = listOf(
                DetectedRegion(
                    regionId = "region-bubble",
                    queryIndex = 0,
                    detectorClass = DetectorClass.BUBBLE,
                    confidence = 0.8,
                    box = PixelBox(0.0, 0.0, 50.0, 60.0),
                    semanticStatus = RegionSemanticStatus.BUBBLE_CANDIDATE,
                    protectionPolicy = RegionProtectionPolicy.NONE,
                ),
            ),
            textRegions = listOf(
                DetectedRegion(
                    regionId = "region-text",
                    queryIndex = 1,
                    detectorClass = DetectorClass.TEXT_IN_BUBBLE,
                    confidence = 0.9,
                    box = PixelBox(5.0, 6.0, 40.0, 50.0),
                    semanticStatus = RegionSemanticStatus.TEXT_IN_BUBBLE,
                    protectionPolicy = RegionProtectionPolicy.NONE,
                ),
                DetectedRegion(
                    regionId = "region-free",
                    queryIndex = 2,
                    detectorClass = DetectorClass.TEXT_FREE,
                    confidence = 0.75,
                    box = PixelBox(1.0, 2.0, 30.0, 40.0),
                    semanticStatus = RegionSemanticStatus.UNRESOLVED_FREE_TEXT,
                    protectionPolicy = RegionProtectionPolicy.PRESERVE_UNTIL_CLASSIFIED,
                ),
            ),
        )
    }
}
