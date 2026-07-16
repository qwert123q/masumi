package rs.masumi.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QualityIdentityTest {
    @Test
    fun `identity is deterministic and covers the rendered image and policy`() {
        val base = QualityIdentity.pageArtifactKey(0, "a".repeat(64), "b".repeat(64), "c".repeat(64), QualityFixtures.dependencies)
        val same = QualityIdentity.pageArtifactKey(0, "a".repeat(64), "b".repeat(64), "c".repeat(64), QualityFixtures.dependencies)
        val changedImage = QualityIdentity.pageArtifactKey(0, "a".repeat(64), "b".repeat(64), "d".repeat(64), QualityFixtures.dependencies)
        val changedPolicy = QualityIdentity.pageArtifactKey(
            0,
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            QualityFixtures.dependencies.copy(policy = QualityPolicy(maximumChangedPixelsOutsideLayout = 0)),
        )

        assertEquals(base, same)
        assertNotEquals(base, changedImage)
        assertNotEquals(base, changedPolicy)
    }
}
