package rs.masumi.core.typesetting

import kotlin.test.Test
import kotlin.test.assertNotEquals

class TypesettingIdentityTest {
    @Test
    fun `identity changes with cleanup source order and every visible policy family`() {
        val base = TypesettingFixtures.dependencies
        val baseline = TypesettingIdentity.pageArtifactKey(0, "1".repeat(64), "2".repeat(64), base)

        assertNotEquals(baseline, TypesettingIdentity.pageArtifactKey(1, "1".repeat(64), "2".repeat(64), base))
        assertNotEquals(baseline, TypesettingIdentity.pageArtifactKey(0, "3".repeat(64), "2".repeat(64), base))
        assertNotEquals(
            baseline,
            TypesettingIdentity.pageArtifactKey(
                0,
                "1".repeat(64),
                "2".repeat(64),
                base.copy(policy = base.policy.copy(fontWeight = 600)),
            ),
        )
        assertNotEquals(
            baseline,
            TypesettingIdentity.pageArtifactKey(
                0,
                "1".repeat(64),
                "2".repeat(64),
                base.copy(policy = base.policy.copy(verticalAspectThreshold = 1.3)),
            ),
        )
    }
}
