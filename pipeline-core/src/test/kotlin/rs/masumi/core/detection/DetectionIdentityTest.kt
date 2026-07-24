package rs.masumi.core.detection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DetectionIdentityTest {
    @Test
    fun `page key changes for every declared dependency`() {
        val sourceSha = "a".repeat(64)
        val model = model()
        val preprocessing = DetectionPreprocessingConfig()
        val thresholds = DetectionThresholdConfig()
        val baseline = DetectionIdentity.pageArtifactKey(
            sourceSha,
            DETECTION_SCHEMA_VERSION,
            model,
            preprocessing,
            thresholds,
        )

        val changedKeys = listOf(
            DetectionIdentity.pageArtifactKey("d".repeat(64), 1, model, preprocessing, thresholds),
            DetectionIdentity.pageArtifactKey(sourceSha, 2, model, preprocessing, thresholds),
            DetectionIdentity.pageArtifactKey(
                sourceSha,
                1,
                model.copy(revision = "revision-2"),
                preprocessing,
                thresholds,
            ),
            DetectionIdentity.pageArtifactKey(
                sourceSha,
                1,
                model.copy(sha256 = "e".repeat(64)),
                preprocessing,
                thresholds,
            ),
            DetectionIdentity.pageArtifactKey(
                sourceSha,
                1,
                model.copy(runtimeRevision = "runtime:2"),
                preprocessing,
                thresholds,
            ),
            DetectionIdentity.pageArtifactKey(
                sourceSha,
                1,
                model,
                preprocessing.copy(inputWidth = 320),
                thresholds,
            ),
            DetectionIdentity.pageArtifactKey(
                sourceSha,
                1,
                model,
                preprocessing.copy(revision = "detector-v4-rgb-stretch-orig-hw-v1"),
                thresholds,
            ),
            DetectionIdentity.pageArtifactKey(
                sourceSha,
                1,
                model,
                preprocessing,
                thresholds.copy(textFree = 0.3),
            ),
        )

        assertEquals(64, baseline.length)
        changedKeys.forEach { changed -> assertNotEquals(baseline, changed) }
        assertEquals(changedKeys.size, changedKeys.toSet().size)
    }

    @Test
    fun `run key preserves manifest order and rejects invalid order`() {
        val first = DetectionIdentity.runArtifactKey(
            listOf(0 to "a".repeat(64), 1 to "b".repeat(64)),
        )
        val repeated = DetectionIdentity.runArtifactKey(
            listOf(0 to "a".repeat(64), 1 to "b".repeat(64)),
        )
        val reversed = DetectionIdentity.runArtifactKey(
            listOf(0 to "b".repeat(64), 1 to "a".repeat(64)),
        )

        assertEquals(first, repeated)
        assertNotEquals(first, reversed)
        assertFailsWith<IllegalArgumentException> {
            DetectionIdentity.runArtifactKey(listOf(0 to "a".repeat(64), 0 to "b".repeat(64)))
        }
        assertFailsWith<IllegalArgumentException> {
            DetectionIdentity.runArtifactKey(listOf(1 to "a".repeat(64)))
        }
    }

    @Test
    fun `region identity depends on query and class only after page identity`() {
        val first = DetectionIdentity.regionId(
            pageId = "a".repeat(64),
            pageArtifactKey = "b".repeat(64),
            queryIndex = 7,
            detectorClass = DetectorClass.TEXT_FREE,
        )
        val repeated = DetectionIdentity.regionId(
            pageId = "a".repeat(64),
            pageArtifactKey = "b".repeat(64),
            queryIndex = 7,
            detectorClass = DetectorClass.TEXT_FREE,
        )

        assertEquals(first, repeated)
        assertNotEquals(
            first,
            DetectionIdentity.regionId("a".repeat(64), "b".repeat(64), 8, DetectorClass.TEXT_FREE),
        )
        assertNotEquals(
            first,
            DetectionIdentity.regionId(
                "a".repeat(64),
                "b".repeat(64),
                7,
                DetectorClass.TEXT_IN_BUBBLE,
            ),
        )
    }

    private fun model(): DetectorModelRef = DetectorModelRef(
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
}
