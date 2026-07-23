package rs.masumi.app.library

import java.nio.file.Path
import rs.masumi.core.modelpackage.PinnedComicDetector
import rs.masumi.core.modelpackage.PinnedPaddleOcrVl

/**
 * Copies already-installed pinned models into the user-selected manga library.
 * This never downloads a model; incomplete or invalid local packages are skipped.
 */
class MangaLibraryInstalledModelSynchronizer(
    private val workspaceRoot: Path,
    private val cache: MangaLibraryModelCache,
) {
    fun sync() {
        val normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize()
        val ocr = PinnedPaddleOcrVl.descriptor
        runCatching {
            cache.backup(
                PersistentModelPackage(
                    cacheKey = "文字识别",
                    version = ocr.packageSha256,
                    files = listOf(
                        PersistentModelFile(
                            ocr.model.fileName,
                            ocr.model.byteLength,
                            ocr.model.installedSha256,
                        ),
                        PersistentModelFile(
                            ocr.projector.fileName,
                            ocr.projector.byteLength,
                            ocr.projector.installedSha256,
                        ),
                    ),
                ),
                normalizedWorkspace
                    .resolve("models")
                    .resolve(ocr.storageKey)
                    .resolve(ocr.packageSha256),
            ) { _, _ -> }
        }

        val detector = PinnedComicDetector.descriptor
        runCatching {
            cache.backup(
                PersistentModelPackage(
                    cacheKey = "漫画检测",
                    version = detector.sha256,
                    files = listOf(
                        PersistentModelFile(
                            "model.onnx",
                            detector.byteLength,
                            detector.sha256,
                        ),
                    ),
                ),
                normalizedWorkspace
                    .resolve("models")
                    .resolve(detector.storageKey)
                    .resolve(detector.sha256),
            ) { _, _ -> }
        }
    }
}
