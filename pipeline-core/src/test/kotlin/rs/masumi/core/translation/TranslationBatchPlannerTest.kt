package rs.masumi.core.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranslationBatchPlannerTest {
    @Test
    fun `planner preserves chapter order and carries bounded preceding context`() {
        val inputs = (0 until 4).map { rank ->
            TranslationFixtures.input("id-$rank", rank, source = "あ".repeat(60))
        }
        val promptBuilder = TranslationPromptBuilder()
        val threeItemEstimate = promptBuilder.estimateInputTokens(
            TranslationFixtures.window(inputs.take(3)),
        )
        val fourItemEstimate = promptBuilder.estimateInputTokens(
            TranslationFixtures.window(inputs),
        )
        assertTrue(fourItemEstimate > threeItemEstimate)
        val planner = TranslationBatchPlanner(
            config = TranslationBatchingConfig(
                maximumEstimatedInputTokens = threeItemEstimate,
                maximumContextItems = 3,
            ),
            promptBuilder = promptBuilder,
        )

        val windows = planner.plan(
            pages = listOf(
                TranslationFixtures.page(1, listOf(inputs[3])),
                TranslationFixtures.page(0, inputs.take(3).reversed()),
            ),
        )

        assertEquals(2, windows.size)
        assertEquals(listOf("id-0", "id-1", "id-2"), windows[0].items.map { it.input.translationRegionId })
        assertEquals(listOf("id-3"), windows[1].items.map { it.input.translationRegionId })
        assertEquals(listOf("id-1", "id-2"), windows[1].contextItems.map { it.input.translationRegionId })
        assertTrue(windows.all { it.estimatedInputTokens <= threeItemEstimate })
        assertTrue(windows.none(TranslationBatchWindow::exceedsBudget))
    }

    @Test
    fun `glossary and prompt are stable regardless of map insertion order`() {
        val page = TranslationFixtures.page(0, listOf(TranslationFixtures.input("id", 0)))
        val first = linkedMapOf("B" to "乙", "A" to "甲")
        val second = linkedMapOf("A" to "甲", "B" to "乙")

        val firstWindow = TranslationBatchPlanner().plan(listOf(page), first).single()
        val secondWindow = TranslationBatchPlanner().plan(listOf(page), second).single()

        assertEquals(firstWindow.glossary, secondWindow.glossary)
        assertEquals(
            TranslationPromptBuilder().build(firstWindow),
            TranslationPromptBuilder().build(secondWindow),
        )
    }

    @Test
    fun `one oversized item is isolated and explicitly marked`() {
        val page = TranslationFixtures.page(
            0,
            listOf(TranslationFixtures.input("large", 0, source = "あ".repeat(1_000))),
        )

        val window = TranslationBatchPlanner(
            TranslationBatchingConfig(maximumEstimatedInputTokens = 1),
        ).plan(listOf(page)).single()

        assertEquals(listOf("large"), window.items.map { it.input.translationRegionId })
        assertTrue(window.exceedsBudget)
    }

    @Test
    fun `planner caps short items per window while preserving stable order and context`() {
        val inputs = (0 until 35).map { rank ->
            TranslationFixtures.input("id-$rank", rank, source = "短文")
        }
        val windows = TranslationBatchPlanner(
            TranslationBatchingConfig(
                maximumEstimatedInputTokens = 100_000,
                maximumItemsPerWindow = 12,
                maximumContextItems = 3,
            ),
        ).plan(listOf(TranslationFixtures.page(0, inputs)))

        assertEquals(listOf(12, 12, 11), windows.map { it.items.size })
        assertEquals(inputs.map { it.translationRegionId }, windows.flatMap { window ->
            window.items.map { it.input.translationRegionId }
        })
        assertEquals(listOf("id-21", "id-22", "id-23"), windows.last().contextItems.map {
            it.input.translationRegionId
        })
    }

    @Test
    fun `planner rejects duplicate ids and inconsistent chapter policy`() {
        val duplicate = TranslationFixtures.input("same", 0)
        assertFailsWith<IllegalArgumentException> {
            TranslationBatchPlanner().plan(
                listOf(TranslationFixtures.page(0, listOf(duplicate, duplicate.copy(readingOrderRank = 1)))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TranslationBatchPlanner().plan(
                listOf(
                    TranslationFixtures.page(0, emptyList()),
                    TranslationFixtures.page(1, emptyList(), TranslationPolicy(translateSoundEffects = true)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TranslationBatchingConfig(maximumItemsPerWindow = 0)
        }
    }
}
