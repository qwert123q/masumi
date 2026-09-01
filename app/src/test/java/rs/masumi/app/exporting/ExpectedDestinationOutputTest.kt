package rs.masumi.app.exporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedDestinationOutputTest {
    @Test
    fun `generation manifest carries names without content metadata`() {
        val output = ExpectedDestinationOutput("0001.png")

        assertEquals("0001.png", output.outputName)
    }

    @Test
    fun `generation filename check rejects duplicate actual documents`() {
        assertTrue(
            destinationOutputNamesMatch(
                actualNames = listOf("0001.png", "0002.png"),
                expectedNames = setOf("0001.png", "0002.png"),
            ),
        )
        assertFalse(
            destinationOutputNamesMatch(
                actualNames = listOf("0001.png", "0001.png"),
                expectedNames = setOf("0001.png", "0002.png"),
            ),
        )
    }

    @Test
    fun `rename fallback discards the returned renamed document when uri changes`() {
        assertEquals(
            "renamed-uri",
            unusableRenameDocument("temporary-uri", "renamed-uri"),
        )
        assertEquals(
            "temporary-uri",
            unusableRenameDocument("temporary-uri", null),
        )
    }
}
