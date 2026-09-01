package rs.masumi.core.cleanup

import kotlin.test.Test
import kotlin.test.assertEquals

class CleanupIdentityTest {
    @Test
    fun `page identity is a structural child of the persisted run`() {
        assertEquals("cleanup-run.page.0001", CleanupIdentity.pageArtifactKey("cleanup-run", 1))
    }
}
