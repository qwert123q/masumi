package rs.masumi.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineSchedulePlannerTest {
    @Test
    fun `runs one local task and fills two translation slots`() {
        val projects = listOf(
            project("translate-a", 1, PipelineStage.TRANSLATION),
            project("translate-b", 2, PipelineStage.TRANSLATION),
            project("ocr-c", 3, PipelineStage.OCR),
        )

        val launches = PipelineSchedulePlanner.plan(projects, emptySet(), translationCapacity = 2)

        assertEquals(
            listOf(
                PipelineLaunch("translate-a", PipelineStage.TRANSLATION),
                PipelineLaunch("translate-b", PipelineStage.TRANSLATION),
                PipelineLaunch("ocr-c", PipelineStage.OCR),
            ),
            launches,
        )
    }

    @Test
    fun `never overlaps local image stages`() {
        val running = setOf(RunningPipelineTask("active-ocr", PipelineStage.OCR))
        val launches = PipelineSchedulePlanner.plan(
            projects = listOf(
                project("cleanup", 1, PipelineStage.CLEANUP),
                project("translation", 2, PipelineStage.TRANSLATION),
            ),
            running = running,
            translationCapacity = 2,
        )

        assertEquals(listOf(PipelineLaunch("translation", PipelineStage.TRANSLATION)), launches)
    }

    @Test
    fun `fills an empty translation lane before post processing`() {
        val launches = PipelineSchedulePlanner.plan(
            projects = listOf(
                project("cleanup", 1, PipelineStage.CLEANUP),
                project("detect", 2, PipelineStage.DETECTION),
                project("ocr", 3, PipelineStage.OCR),
            ),
            running = emptySet(),
            translationCapacity = 2,
        )

        assertEquals(listOf(PipelineLaunch("ocr", PipelineStage.OCR)), launches)
    }

    @Test
    fun `feeds a shorter chapter into the empty network lane first`() {
        val launches = PipelineSchedulePlanner.plan(
            projects = listOf(
                project("long", 1, PipelineStage.OCR, pageCount = 230),
                project("short", 2, PipelineStage.OCR, pageCount = 18),
            ),
            running = emptySet(),
            translationCapacity = 2,
        )

        assertEquals(listOf(PipelineLaunch("short", PipelineStage.OCR)), launches)
    }

    @Test
    fun `uses a single translation slot for the shorter ready chapter first`() {
        val launches = PipelineSchedulePlanner.plan(
            projects = listOf(
                project("long", 1, PipelineStage.TRANSLATION, pageCount = 230),
                project("short", 2, PipelineStage.TRANSLATION, pageCount = 18),
            ),
            running = emptySet(),
            translationCapacity = 1,
        )

        assertEquals(listOf(PipelineLaunch("short", PipelineStage.TRANSLATION)), launches)
    }

    @Test
    fun `finishes translated work while network lanes are occupied`() {
        val running = setOf(
            RunningPipelineTask("translation-a", PipelineStage.TRANSLATION),
            RunningPipelineTask("translation-b", PipelineStage.TRANSLATION),
        )
        val launches = PipelineSchedulePlanner.plan(
            projects = listOf(
                project("detect", 1, PipelineStage.DETECTION),
                project("cleanup", 2, PipelineStage.CLEANUP),
            ),
            running = running,
            translationCapacity = 2,
        )

        assertEquals(listOf(PipelineLaunch("cleanup", PipelineStage.CLEANUP)), launches)
    }

    @Test
    fun `skips blocked and settings waiting projects`() {
        val launches = PipelineSchedulePlanner.plan(
            projects = listOf(
                project("blocked", 1, PipelineStage.CLEANUP, blocked = true),
                project("settings", 2, PipelineStage.TRANSLATION, waitingForSettings = true),
            ),
            running = emptySet(),
            translationCapacity = 2,
        )

        assertTrue(launches.isEmpty())
    }

    private fun project(
        id: String,
        order: Long,
        stage: PipelineStage,
        waitingForSettings: Boolean = false,
        blocked: Boolean = false,
        pageCount: Int = 10,
    ) = ScheduledProject(
        projectId = id,
        queueOrder = order,
        pageCount = pageCount,
        nextStage = stage,
        waitingForSettings = waitingForSettings,
        blocked = blocked,
    )
}
