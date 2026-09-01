package rs.masumi.app.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingLocationTest {
    @Test
    fun `clamps anchor page and normalized in page fraction`() {
        assertEquals(ReadingLocation(pageIndex = 0, intraPageFraction = 0f), ReadingLocation(-2, -0.4f).clamped(pageCount = 4))
        assertEquals(ReadingLocation(pageIndex = 3, intraPageFraction = 0.9999f), ReadingLocation(9, 2f).clamped(pageCount = 4))
    }

    @Test
    fun `non finite fractions restore safely at the page start`() {
        assertEquals(ReadingLocation(pageIndex = 2, intraPageFraction = 0f), ReadingLocation(2, Float.NaN).clamped(pageCount = 4))
        assertEquals(null, ReadingLocation.fromStored("2|NaN"))
    }

    @Test
    fun `reset location always starts at the first page`() {
        assertEquals(ReadingLocation.START, ReadingLocation(pageIndex = 6, intraPageFraction = 0.6f).reset())
    }

    @Test
    fun `stored anchor round trips without changing its page or fraction`() {
        val location = ReadingLocation(pageIndex = 12, intraPageFraction = 0.625f)

        assertEquals(location, ReadingLocation.fromStored(location.toStored()))
    }
}
