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
    fun `destination output requires a present exact nonzero byte length`() {
        assertTrue(destinationOutputLengthMatches(actualByteLength = 128L, expectedByteLength = 128L))
        assertFalse(destinationOutputLengthMatches(actualByteLength = null, expectedByteLength = 128L))
        assertFalse(destinationOutputLengthMatches(actualByteLength = 127L, expectedByteLength = 128L))
        assertFalse(destinationOutputLengthMatches(actualByteLength = 0L, expectedByteLength = 0L))
    }
}
