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
                base.copy(policy = base.policy.copy(dilationRadiusPixels = 2)),
            ),
        )
    }
}
