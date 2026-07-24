package rs.masumi.app.typesetting

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.cleanup.CleanupTarget
import rs.masumi.app.cleanup.SourceCleanupEngine
import rs.masumi.app.detection.PageBitmapDecoder
import rs.masumi.app.detection.ProjectCatalog
import rs.masumi.core.cleanup.CleanupPolicy
import rs.masumi.core.cleanup.CleanupRegionState
import rs.masumi.core.cleanup.CleanupStrategy
import rs.masumi.core.detection.DetectorClass
import rs.masumi.core.ocr.OcrArtifactStore
import rs.masumi.core.ocr.OcrPageState
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.serialization.TranslationJson
import rs.masumi.core.typesetting.TypesettingPolicy
import rs.masumi.core.typesetting.TypesettingRegionState
import rs.masumi.core.typesetting.TypesettingStyle

@RunWith(AndroidJUnit4::class)
class RealTypesettingSmokeTest {
    @Test
    fun rendersFirstCommittedOcrPageWithPrivateTranslationResponse() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("realTypesettingSmoke").toBoolean())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val responsePath = context.cacheDir.toPath().resolve(RESPONSE_FILE)
        assumeTrue(Files.isRegularFile(responsePath))
        val workspace = context.filesDir.toPath().resolve("workspace")
        val catalog = ProjectCatalog(workspace)
        val projects = mutableListOf<rs.masumi.app.detection.ProjectRef>()
        Files.newDirectoryStream(workspace.resolve("projects")).use { paths ->
            paths.forEach { path -> catalog.openProject(path.fileName.toString())?.let(projects::add) }
        }
        val project = projects.maxByOrNull { it.manifest.createdAtEpochMillis }
        assumeTrue(project != null)
        val selectedProject = requireNotNull(project)
        val job = OcrArtifactStore(selectedProject.directory).findResumableJob()
        assumeTrue(job != null)
        val selectedJob = requireNotNull(job)
        val pageCheckpoint = selectedJob.pages
            .filter { it.state == OcrPageState.COMMITTED }
            .minByOrNull { it.order }
        assumeTrue(pageCheckpoint != null)
        val selectedCheckpoint = requireNotNull(pageCheckpoint)
        val ocrPage = requireNotNull(
            OcrArtifactStore(selectedProject.directory).readCommittedPageArtifact(
                selectedJob,
                selectedCheckpoint,
            ),
        )
        val translations = TranslationJson()
            .decodeModelResponse(Files.newBufferedReader(responsePath).use { it.readText() })
            .items
            .filter { !it.translation.isNullOrBlank() }
            .associateBy { it.id }
        assertTrue(translations.isNotEmpty())
        val pageRecord = selectedProject.manifest.pages.single { it.pageId == selectedCheckpoint.pageId }
        val decoded = PageBitmapDecoder().decode(selectedProject.directory.resolve(pageRecord.storedPath))
        try {
            val recognized = ocrPage.regions.filter { it.state == OcrRegionState.RECOGNIZED }
            val cleanupTargets = recognized.mapNotNull { region ->
                val translated = translations[region.candidate.ocrRegionId]?.translation ?: return@mapNotNull null
                CleanupTarget(
                    translationRegionId = region.candidate.ocrRegionId,
                    ocrRegionId = region.candidate.ocrRegionId,
                    box = region.candidate.box,
                    strategy = if (region.candidate.sourceClass == DetectorClass.TEXT_IN_BUBBLE ||
                        region.candidate.associatedBubbleBox != null
                    ) {
                        CleanupStrategy.FLAT_LOCAL_FILL
                    } else {
                        CleanupStrategy.LOCAL_BOUNDARY_INPAINT
                    },
                )
            }
            val cleaned = SourceCleanupEngine().clean(decoded.bitmap, cleanupTargets, CleanupPolicy())
            try {
                val cleanedIds = cleaned.regions
                    .filter { it.state == CleanupRegionState.CLEANED }
                    .mapTo(mutableSetOf()) { it.ocrRegionId }
                val cleanupPreservedByReason = cleaned.regions
                    .filter { it.state != CleanupRegionState.CLEANED }
                    .groupingBy { it.preserveReason?.name ?: "UNKNOWN" }
                    .eachCount()
                assertEquals(cleanupTargets.size, cleanedIds.size + cleanupPreservedByReason.values.sum())
                assertTrue(cleanedIds.isNotEmpty())
                val targets = recognized.mapNotNull { region ->
                    val candidate = region.candidate
                    val translated = translations[candidate.ocrRegionId]?.translation ?: return@mapNotNull null
                    if (candidate.ocrRegionId !in cleanedIds) return@mapNotNull null
                    val inBubble = candidate.sourceClass == DetectorClass.TEXT_IN_BUBBLE ||
                        candidate.associatedBubbleBox != null
                    TypesettingTarget(
                        translationRegionId = candidate.ocrRegionId,
                        ocrRegionId = candidate.ocrRegionId,
                        translatedText = translated,
                        textBox = candidate.box,
                        bubbleBox = candidate.associatedBubbleBox,
                        style = if (inBubble) TypesettingStyle.BUBBLE else TypesettingStyle.FREE_TEXT,
                    )
                }
                val rendered = ChineseTypesetter().render(cleaned.bitmap, targets, TypesettingPolicy())
                try {
                    assertTrue(rendered.regions.any { it.state == TypesettingRegionState.TYPESET })
                    println(
                        "REAL_TYPESETTING_METRICS translated=${translations.size} " +
                            "cleanupTargets=${cleanupTargets.size} cleaned=${cleanedIds.size} " +
                            "cleanupPreserved=${cleanupPreservedByReason.values.sum()} " +
                            "typeset=${rendered.regions.count { it.state == TypesettingRegionState.TYPESET }} " +
                            "preserved=${rendered.regions.count { it.state != TypesettingRegionState.TYPESET }}",
                    )
                    val output = requireNotNull(context.getExternalFilesDir("smoke"))
                        .toPath()
                        .resolve("typesetting-smoke.png")
                    Files.newOutputStream(output).use {
                        check(rendered.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    val metrics = JSONObject()
                        .put("translated", translations.size)
                        .put("cleanupTargets", cleanupTargets.size)
                        .put("cleaned", cleanedIds.size)
                        .put("cleanupPreserved", cleanupPreservedByReason.values.sum())
                        .put(
                            "cleanupPreservedByReason",
                            JSONObject(cleanupPreservedByReason.mapValues { it.value as Any }),
                        )
                        .put("typeset", rendered.regions.count { it.state == TypesettingRegionState.TYPESET })
                        .put(
                            "preservedByReason",
                            JSONObject(
                                rendered.regions
                                    .filter { it.state != TypesettingRegionState.TYPESET }
                                    .groupingBy { it.preserveReason?.name ?: "UNKNOWN" }
                                    .eachCount()
                                    .mapValues { it.value as Any },
                            ),
                        )
                    Files.newBufferedWriter(output.resolveSibling("typesetting-smoke-metrics.json")).use {
                        it.write(metrics.toString())
                    }
                } finally {
                    rendered.bitmap.recycle()
                }
            } finally {
                cleaned.bitmap.recycle()
            }
        } finally {
            decoded.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private companion object {
        const val RESPONSE_FILE = "real-typesetting-response.json"
    }
}
