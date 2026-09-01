package rs.masumi.core.cleanup

import kotlin.test.Test
import kotlin.test.assertNotEquals

class CleanupIdentityTest {
    @Test
    fun `identity changes with translation source geometry policy and order`() {
        val base = CleanupFixtures.dependencies
        val baseline = CleanupIdentity.pageArtifactKey(0, "b".repeat(64), "c".repeat(64), base)

        assertNotEquals(baseline, CleanupIdentity.pageArtifactKey(1, "b".repeat(64), "c".repeat(64), base))
        assertNotEquals(baseline, CleanupIdentity.pageArtifactKey(0, "f".repeat(64), "c".repeat(64), base))
        assertNotEquals(
            baseline,
            CleanupIdentity.pageArtifactKey(
                0,
                "b".repeat(64),
                "c".repeat(64),
                base.copy(policy = base.policy.copy(dilationRadiusPixels = 3)),
            ),
        )
        assertNotEquals(
            baseline,
            CleanupIdentity.pageArtifactKey(
                0,
                "b".repeat(64),
                "c".repeat(64),
                base.copy(policy = base.policy.copy(maximumNeuralFallbackAttempts = 7)),
            ),
        )
        assertNotEquals(
            baseline,
            CleanupIdentity.pageArtifactKey(
                0,
                "b".repeat(64),
                "c".repeat(64),
                base.copy(policy = base.policy.copy(maximumNeuralFallbackMillis = 119_999L)),
            ),
        )
        assertNotEquals(
            baseline,
            CleanupIdentity.pageArtifactKey(
                0,
                "b".repeat(64),
                "c".repeat(64),
                base.copy(
                    maskModel = CleanupMaskModelRef(
                        modelId = "comic-text-segmenter",
                        repository = "example/repository",
                        revision = "revision-1",
                        fileName = "segmenter.onnx",
                        sha256 = "e".repeat(64),
                        byteLength = 123,
                        license = "GPL-3.0-only",
                        opset = 11,
                        runtimeRevision = "onnxruntime-android:1.27.0",
                    ),
                ),
            ),
        )
        assertNotEquals(
            baseline,
            CleanupIdentity.pageArtifactKey(
                0,
                "b".repeat(64),
                "c".repeat(64),
                base.copy(
                    neuralModel = CleanupNeuralModelRef(
                        modelId = "aot-gan-inpainting",
                        repository = "example/aot-inpainting",
                        revision = "revision-1",
                        fileName = "aot-inpainting.onnx",
                        sha256 = "d".repeat(64),
                        byteLength = 456,
                        license = "GPL-3.0-only",
                        opset = 11,
                        runtimeRevision = "onnxruntime-android:1.27.0",
                    ),
                ),
            ),
        )
    }
}
