package rs.masumi.app.ocr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.detection.ComicDetector
import rs.masumi.app.detection.ComicDetectorFactory
import rs.masumi.app.detection.DecodedPage
import rs.masumi.app.detection.DetectionPreviewRenderer
import rs.masumi.app.detection.DetectionRunner
import rs.masumi.app.detection.DetectorModelProvider
import rs.masumi.app.detection.PageBitmapDecoder
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.ModelQuery
import rs.masumi.core.importer.IdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.modelpackage.InstalledOcrModelPackage
import rs.masumi.core.modelpackage.OcrModelCapabilities
import rs.masumi.core.modelpackage.OcrModelPackageMetadata
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl
import rs.masumi.core.ocr.OcrJobStatus
import rs.masumi.core.ocr.OcrRegionState
import rs.masumi.core.serialization.OcrJson
import rs.masumi.core.serialization.ProjectJson

@RunWith(AndroidJUnit4::class)
class OcrRunnerTest {
    @Test
    fun cancelledRunReusesTerminalRegionAndDuplicatePageWorkOnResume() {
        withWorkspace { workspace ->
            val source = createProjectAndDetection(workspace, candidateCount = 2, duplicatePage = true)
            val before = sha256(Files.readAllBytes(source))
            val requestCount = intArrayOf(0)
            val cancellation = AtomicBoolean(false)
            val runner = runner(workspace) {
                requestCount[0] += 1
                result("今日は", 0.9)
            }

            val cancelled = runner.run(PROJECT_ID, cancellation::get) { progress ->
                if (progress.terminalRegionCount == 1) cancellation.set(true)
            }

            assertEquals(OcrJobStatus.CANCELLED, cancelled.job.status)
            assertEquals(1, requestCount[0])
            assertEquals(before, sha256(Files.readAllBytes(source)))

            val resumed = runner.run(PROJECT_ID, { false }) { }

            assertEquals(OcrJobStatus.SUCCEEDED, resumed.job.status)
            assertEquals(2, requestCount[0])
            assertEquals(2, resumed.runArtifact?.entries?.size)
            assertNotNull(resumed.publishedDirectory)
            assertEquals(before, sha256(Files.readAllBytes(source)))
        }
    }

    @Test
    fun disagreementUsesThirdCropAndPublishesRecognizedRegion() {
        withWorkspace { workspace ->
            createProjectAndDetection(workspace, candidateCount = 1, duplicatePage = false)
            val outputs = ArrayDeque(
                listOf(
                    result("甲", 0.4),
                    result("乙", 0.4),
                    result("乙", 0.4),
                ),
            )
            val runner = runner(workspace) { outputs.removeFirst() }

            val completed = runner.run(PROJECT_ID, { false }) { }

            assertEquals(OcrJobStatus.SUCCEEDED, completed.job.status)
            assertTrue(outputs.isEmpty())
            val pagePath = completed.publishedDirectory!!.resolve("pages/${completed.job.pages.first().pageId}/ocr.json")
            val page = OcrJson().decodePageArtifact(
                Files.newBufferedReader(pagePath).use { it.readText() },
            )
            assertEquals(3, page.regions.single().attempts.size)
            assertEquals(OcrRegionState.RECOGNIZED, page.regions.single().state)
        }
    }

    @Test
    fun repeatedFailurePreservesOneRegionAndContinuesWithTheNext() {
        withWorkspace { workspace ->
            createProjectAndDetection(workspace, candidateCount = 2, duplicatePage = false)
            var calls = 0
            val runner = runner(workspace) {
                calls += 1
                if (calls <= 3) throw OcrEngineException(OcrEngineErrorCode.DECODE)
                result("次の台詞", 0.9)
            }

            val completed = runner.run(PROJECT_ID, { false }) { }

            assertEquals(OcrJobStatus.SUCCEEDED_WITH_PRESERVED_REGIONS, completed.job.status)
            assertEquals(4, calls)
            assertEquals(1, completed.report?.preservedRegionCount)
            assertEquals(1, completed.report?.recognizedRegionCount)
        }
    }

    private fun runner(
        workspace: Path,
        recognize: (OcrEngineRequest) -> OcrEngineResult,
    ): OcrRunner {
        val descriptor = PinnedPaddleOcrVl.descriptor
        val model = workspace.resolve("model.gguf").also { Files.write(it, byteArrayOf(1)) }
        val projector = workspace.resolve("projector.gguf").also { Files.write(it, byteArrayOf(2)) }
        val installed = InstalledOcrModelPackage(
            model = model,
            projector = projector,
            metadata = OcrModelPackageMetadata(
                modelPackage = descriptor.toRef(),
                runtime = descriptor.runtime.toRef(),
                prompt = descriptor.prompt,
                capabilities = OcrModelCapabilities(true, true, "paddleocr"),
                acquiredAtEpochMillis = 1L,
            ),
        )
        return OcrRunner(
            workspaceRoot = workspace,
            modelProvider = OcrModelProvider { _, progress ->
                progress(2, 2)
                installed
            },
            engineFactory = OcrEngineFactory { _, _ ->
                object : OcrEngine {
                    override fun recognize(
                        request: OcrEngineRequest,
                        cancellation: () -> Boolean,
                    ): OcrEngineResult {
                        if (cancellation()) throw OcrEngineException(OcrEngineErrorCode.CANCELLED)
                        return recognize(request)
                    }

                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            },
            decoder = PageBitmapDecoder(),
            cropRenderer = OcrCropRenderer(),
            previewRenderer = OcrPreviewRenderer(),
            clock = Clock.fixed(Instant.ofEpochMilli(200), ZoneOffset.UTC),
            idSource = IdSource { "ocr-job" },
        )
    }

    private fun createProjectAndDetection(
        workspace: Path,
        candidateCount: Int,
        duplicatePage: Boolean,
    ): Path {
        val project = workspace.resolve("projects/$PROJECT_ID")
        val sources = project.resolve("sources")
        Files.createDirectories(sources)
        val bytes = png()
        val pageId = sha256(bytes)
        val source = sources.resolve("$pageId.png")
        Files.write(source, bytes)
        val page = PageRecord(
            order = 0,
            pageId = pageId,
            sourceSha256 = pageId,
            originalName = "page.png",
            mediaType = "image/png",
            byteLength = bytes.size.toLong(),
            storedPath = "sources/$pageId.png",
        )
        val pages = if (duplicatePage) listOf(page, page.copy(order = 1)) else listOf(page)
        Files.newBufferedWriter(project.resolve("manifest.json")).use { writer ->
            writer.write(
                ProjectJson().encodeManifest(
                    ProjectManifest(projectId = PROJECT_ID, createdAtEpochMillis = 1L, pages = pages),
                ),
            )
        }
        val detectorModel = workspace.resolve("detector.onnx").also { Files.write(it, byteArrayOf(3)) }
        val detection = DetectionRunner(
            workspaceRoot = workspace,
            modelProvider = DetectorModelProvider { _, progress ->
                progress(1, 1)
                detectorModel
            },
            detectorFactory = ComicDetectorFactory {
                object : ComicDetector {
                    override fun detect(page: DecodedPage): List<ModelQuery> =
                        detectionQueries(candidateCount)

                    override fun close() = Unit
                }
            },
            decoder = PageBitmapDecoder(),
            previewRenderer = DetectionPreviewRenderer(labelTextSizePx = 8f, strokeWidthPx = 2f),
            clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
            idSource = IdSource { "detection-job" },
        ).run(PROJECT_ID, { false }) { }
        assertEquals(DetectionJobStatus.SUCCEEDED, detection.job.status)
        return source
    }

    private fun detectionQueries(candidateCount: Int): List<ModelQuery> = List(300) { index ->
        when {
            index < candidateCount -> {
                val left = 8f + index * 48f
                ModelQuery(index, 1, 0.9f, floatArrayOf(left, 12f, left + 32f, 60f))
            }
            else -> ModelQuery(index, 0, 0f, floatArrayOf(1f, 1f, 2f, 2f))
        }
    }

    private fun result(text: String, probability: Double): OcrEngineResult = OcrEngineResult(
        rawText = text,
        tokenIds = listOf(1),
        tokenProbabilities = listOf(probability),
        sourceWidth = 32,
        sourceHeight = 48,
        processedWidth = 32,
        processedHeight = 48,
        visualTokenCount = 12,
        generatedTokenCount = 1,
        reachedEos = true,
        truncated = false,
        repetitionStopped = false,
        promptEvaluationMillis = 10,
        generationMillis = 1,
    )

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        return try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun withWorkspace(block: (Path) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = Files.createTempDirectory(context.cacheDir.toPath(), "ocr-runner-")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val PROJECT_ID = "ocr-project-test"
    }
}
