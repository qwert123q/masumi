package rs.masumi.app.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputGenerationTest {
    @Test
    fun `published generation names are sortable and hidden staging never qualifies`() {
        val older = OutputGeneration.publishedName(10L, "a".repeat(64))
        val newer = OutputGeneration.publishedName(11L, "b".repeat(64))

        assertTrue(OutputGeneration.isPublishedDirectory(older))
        assertTrue(OutputGeneration.isPublishedDirectory(newer))
        assertTrue(newer > older)
        assertFalse(OutputGeneration.isPublishedDirectory("${OutputGeneration.STAGING_PREFIX}job"))
    }
}
