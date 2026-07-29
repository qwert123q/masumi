package rs.masumi.app.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaLibraryOrderStoreTest {
    @Test
    fun `saved order wins for existing finished projects`() {
        assertEquals(
            listOf("c", "a", "b"),
            mergeLibraryOrder(
                defaultProjectIds = listOf("a", "b", "c"),
                savedProjectIds = listOf("c", "a", "b"),
            ),
        )
    }

    @Test
    fun `new projects stay above the previously arranged shelf`() {
        assertEquals(
            listOf("new", "c", "a", "b"),
            mergeLibraryOrder(
                defaultProjectIds = listOf("new", "a", "b", "c"),
                savedProjectIds = listOf("c", "a", "b"),
            ),
        )
    }

    @Test
    fun `removed and duplicate projects cannot corrupt the shelf`() {
        assertEquals(
            listOf("b", "a"),
            mergeLibraryOrder(
                defaultProjectIds = listOf("a", "b"),
                savedProjectIds = listOf("removed", "b", "b", "a"),
            ),
        )
    }
}
