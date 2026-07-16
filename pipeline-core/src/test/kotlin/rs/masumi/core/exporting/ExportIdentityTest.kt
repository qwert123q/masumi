package rs.masumi.core.exporting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ExportIdentityTest {
    @Test
    fun `identity binds destination exact typesetting and quality runs and visible naming policy`() {
        val firstDestination = ExportIdentity.destinationKey("content://provider/tree/first")
        val secondDestination = ExportIdentity.destinationKey("content://provider/tree/second")
        val dependencies = ExportFixtures.dependencies
        val baseline = ExportIdentity.exportKey(firstDestination, dependencies)

        assertNotEquals(baseline, ExportIdentity.exportKey(secondDestination, dependencies))
        assertNotEquals(
            baseline,
            ExportIdentity.exportKey(
                firstDestination,
                dependencies.copy(typesettingRunArtifactKey = "9".repeat(64)),
            ),
        )
        assertNotEquals(
            baseline,
            ExportIdentity.exportKey(
                firstDestination,
                dependencies.copy(qualityRunArtifactKey = "8".repeat(64)),
            ),
        )
        assertNotEquals(
            baseline,
            ExportIdentity.exportKey(
                firstDestination,
                dependencies.copy(policy = dependencies.policy.copy(minimumPageNumberDigits = 5)),
            ),
        )
        assertEquals("0001.png", ExportIdentity.outputName(0, 15, ExportPolicy()))
        assertEquals("10000.png", ExportIdentity.outputName(9_999, 10_000, ExportPolicy()))
    }
}
