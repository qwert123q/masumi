package rs.masumi.core.typesetting

import kotlin.test.Test
import kotlin.test.assertEquals

class TypesettingIdentityTest {
    @Test
    fun `page identity is a structural child of the persisted run`() {
        assertEquals("typesetting-run.page.0009", TypesettingIdentity.pageArtifactKey("typesetting-run", 9))
    }
}
