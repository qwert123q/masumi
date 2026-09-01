package rs.masumi.app.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputGenerationTest {
    @Test
    fun `published generation names are sortable and hidden staging never qualifies`() {
        val older = OutputGeneration.publishedName(10L, "export-run.10")
        val newer = OutputGeneration.publishedName(11L, "export-run.11")

        assertEquals("masumi-generation-0000000000010-export-run.10", older)
        assertEquals("masumi-generation-0000000000011-export-run.11", newer)
        assertTrue(OutputGeneration.isPublishedDirectory(older))
        assertTrue(OutputGeneration.isPublishedDirectory(newer))
        assertTrue(newer > older)
        assertFalse(OutputGeneration.isPublishedDirectory("${OutputGeneration.STAGING_PREFIX}job"))
    }

    @Test
    fun `legacy long safe key remains a valid opaque generation key`() {
        val name = OutputGeneration.publishedName(12L, "a".repeat(64))

        assertEquals("masumi-generation-0000000000012-${"a".repeat(16)}", name)
        assertTrue(OutputGeneration.isPublishedDirectory(name))
    }
}
