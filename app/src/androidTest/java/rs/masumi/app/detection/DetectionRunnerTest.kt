package rs.masumi.app.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.core.detection.DetectionJobStatus
import rs.masumi.core.detection.DetectionPageState
import rs.masumi.core.detection.DetectorModelRef
import rs.masumi.core.detection.ModelQuery
import rs.masumi.core.importer.IdSource
import rs.masumi.core.model.PageRecord
import rs.masumi.core.model.ProjectManifest
import rs.masumi.core.serialization.DetectionJson
import rs.masumi.core.serialization.ProjectJson
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DetectionRunnerTest {
    @Test
    fun duplicateSourceIsInferredOnceAndPublishedForEveryOrder() {
        withWorkspace { workspace ->
            val project = createProject(workspace, listOf(Color.RED), orderToSource = listOf(0, 0))
            var detectionCount = 0
            val runner = runner(workspace) { page ->
                detectionCount += 1
                acceptedQueries(page)
            }
            val source = project.resolve("sources").toFile().listFiles()!!.single().toPath()
            val before = sha256(Files.readAllBytes(source))

            val result = runner.run(PROJECT_ID, { false }) { }

            assertEquals(1, detectionCount)
            assertEquals(DetectionJobStatus.SUCCEEDED, result.job.status)
            assertEquals(2, result.runArtifact!!.entries.size)
            assertEquals(before, sha256(Files.readAllBytes(source)))
            assertNotNull(result.publishedDirectory)
            val published = result.publishedDirectory!!
            assertTrue(Files.exists(published.resolve("pages/${before}/regions.json")))
            val artifact = DetectionJson().decodePageArtifact(
                Files.newBufferedReader(published.resolve("pages/${before}/regions.json")).use { it.readText() },
            )
            assertEquals(300, artifact.rawQueries.size)
            assertEquals(1, artifact.textRegions.size)
            listOf(0, 1).forEach { order ->
                val preview = published.resolve("previews/${order.toString().padStart(4, '0')}.webp")
                assertTrue(Files.exists(preview))
                val bitmap = BitmapFactory.decodeFile(preview.toString())
                assertNotNull(bitmap)
                bitmap.recycle()
            }
        }
    }

    @Test
    fun cancelledJobResumesWithoutReinferringCommittedPage() {
        withWorkspace { workspace ->
            createProject(workspace, listOf(Color.RED, Color.BLUE), orderToSource = listOf(0, 1))
            var detectionCount = 0
            val runner = runner(workspace) { page ->
                detectionCount += 1
                acceptedQueries(page)
            }
            val cancel = AtomicBoolean(false)

            val cancelled = runner.run(PROJECT_ID, cancel::get) { progress ->
                if (progress.committedPageCount == 1) cancel.set(true)
            }
            assertEquals(DetectionJobStatus.CANCELLED, cancelled.job.status)
            assertEquals(1, detectionCount)

            val resumed = runner.run(PROJECT_ID, { false }) { }

            assertEquals(DetectionJobStatus.SUCCEEDED, resumed.job.status)
            assertEquals(2, detectionCount)
            assertTrue(resumed.job.pages.all { it.state == DetectionPageState.COMMITTED })
        }
    }

    @Test
    fun retriesOnceThenPreservesFailedPageAndContinues() {
        withWorkspace { workspace ->
            createProject(workspace, listOf(Color.RED, Color.GREEN), orderToSource = listOf(0, 1))
            val attempts = mutableMapOf<Int, Int>()
            var factoryOpenCount = 0
            val runner = runner(
                workspace = workspace,
                onFactoryOpen = { factoryOpenCount += 1 },
            ) { page ->
                val color = page.bitmap.getPixel(0, 0)
                val attempt = attempts.getOrDefault(color, 0) + 1
                attempts[color] = attempt
                if (color == Color.GREEN || (color == Color.RED && attempt == 1)) {
                    throw ComicDetectorException("SYNTHETIC_FAILURE")
                }
                acceptedQueries(page)
            }

            val result = runner.run(PROJECT_ID, { false }) { }

            assertEquals(DetectionJobStatus.SUCCEEDED_WITH_PRESERVED_PAGES, result.job.status)
            assertEquals(1, result.report!!.preservedPageCount)
            assertEquals(2, result.report.retryCount)
            assertEquals(
                listOf(DetectionPageState.COMMITTED, DetectionPageState.PRESERVED_SOURCE),
                result.job.pages.map { it.state },
            )
            assertEquals(3, factoryOpenCount)
        }
    }

    @Test
    fun publishedRunIsReusedWithoutOpeningAnotherDetector() {
        withWorkspace { workspace ->
            createProject(workspace, listOf(Color.RED), orderToSource = listOf(0))
            var detectionCount = 0
            val runner = runner(workspace) { page ->
                detectionCount += 1
                acceptedQueries(page)
            }

            val first = runner.run(PROJECT_ID, { false }) { }
            val second = runner.run(PROJECT_ID, { false }) { }

            assertEquals(DetectionJobStatus.SUCCEEDED, first.job.status)
            assertEquals(first.job.jobId, second.job.jobId)
            assertEquals(1, detectionCount)
            assertNotNull(second.report)
            assertNotNull(second.publishedDirectory)
        }
    }

    @Test
    fun sourceWithRecordedLengthRunsWhenContentsDoNotMatchManifestHash() {
        withWorkspace { workspace ->
            val project = createProject(workspace, listOf(Color.RED), orderToSource = listOf(0))
            val source = project.resolve("sources").toFile().listFiles()!!.single().toPath()
            val json = ProjectJson()
            val manifestPath = project.resolve("manifest.json")
            val manifest = Files.newBufferedReader(manifestPath).use { reader ->
                json.decodeManifest(reader.readText())
            }
            val recordedSha = "0".repeat(64)
            assertTrue(sha256(Files.readAllBytes(source)) != recordedSha)
            Files.newBufferedWriter(manifestPath).use { writer ->
                writer.write(
                    json.encodeManifest(
                        manifest.copy(
                            pages = manifest.pages.map { page ->
                                page.copy(pageId = recordedSha, sourceSha256 = recordedSha)
                            },
                        ),
                    ),
                )
            }
            var detectionCount = 0
            val runner = runner(workspace) { page ->
                detectionCount += 1
                acceptedQueries(page)
            }

            val result = runner.run(PROJECT_ID, { false }) { }

            assertEquals(DetectionJobStatus.SUCCEEDED, result.job.status)
            assertEquals(1, detectionCount)
            assertNotNull(result.publishedDirectory)
        }
    }

    private fun runner(
        workspace: Path,
        onFactoryOpen: () -> Unit = {},
        detect: (DecodedPage) -> List<ModelQuery>,
    ): DetectionRunner {
        val modelFile = workspace.resolve("synthetic-model.onnx")
        Files.write(modelFile, byteArrayOf(1))
        val ids = ArrayDeque(listOf("job-1", "job-2", "job-3"))
        return DetectionRunner(
            workspaceRoot = workspace,
            modelProvider = DetectorModelProvider { _, progress ->
                progress(1, 1)
                modelFile
            },
            detectorFactory = ComicDetectorFactory {
                onFactoryOpen()
                object : ComicDetector {
                    override fun detect(page: DecodedPage): List<ModelQuery> = detect(page)
                    override fun close() = Unit
                }
            },
            decoder = PageBitmapDecoder(),
            previewRenderer = DetectionPreviewRenderer(labelTextSizePx = 8f, strokeWidthPx = 2f),
            model = model(),
            clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
            idSource = IdSource { ids.removeFirst() },
        )
    }

    private fun acceptedQueries(page: DecodedPage): List<ModelQuery> = List(300) { index ->
        if (index == 0) {
            ModelQuery(
                queryIndex = 0,
                label = 2,
                score = 0.9f,
                box = floatArrayOf(1f, 1f, page.bitmap.width - 1f, page.bitmap.height - 1f),
            )
        } else {
            ModelQuery(index, 0, 0f, floatArrayOf(1f, 1f, 2f, 2f))
        }
    }

    private fun createProject(
        workspace: Path,
        colors: List<Int>,
        orderToSource: List<Int>,
    ): Path {
        val project = workspace.resolve("projects/$PROJECT_ID")
        val sources = project.resolve("sources")
        Files.createDirectories(sources)
        val sourceRecords = colors.mapIndexed { index, color ->
            val bytes = png(color)
            val sha = sha256(bytes)
            Files.write(sources.resolve("$sha.png"), bytes)
            Triple(index, sha, bytes.size.toLong())
        }
        val pages = orderToSource.mapIndexed { order, sourceIndex ->
            val (_, sha, byteLength) = sourceRecords[sourceIndex]
            PageRecord(
                order = order,
                pageId = sha,
                sourceSha256 = sha,
                originalName = order.toString().padStart(3, '0') + ".png",
                mediaType = "image/png",
                byteLength = byteLength,
                storedPath = "sources/$sha.png",
            )
        }
        Files.newBufferedWriter(project.resolve("manifest.json")).use { writer ->
            writer.write(
                ProjectJson().encodeManifest(
                    ProjectManifest(
                        projectId = PROJECT_ID,
                        createdAtEpochMillis = 1,
                        pages = pages,
                    ),
                ),
            )
        }
        return project
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun model(): DetectorModelRef = DetectorModelRef(
        modelId = "test/detector",
        repository = "test/detector",
        revision = "revision-1",
        fileName = "detector.onnx",
        sha256 = "c".repeat(64),
        byteLength = 1,
        license = "Apache-2.0",
        opset = 18,
        runtimeRevision = "runtime:1",
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun withWorkspace(block: (Path) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = Files.createTempDirectory(context.cacheDir.toPath(), "runner-")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val PROJECT_ID = "project-test"
    }
}
