package rs.masumi.app.detection

import rs.masumi.core.detection.ModelQuery
import java.nio.file.Path

interface ComicDetector : AutoCloseable {
    fun detect(page: DecodedPage): List<ModelQuery>
}

fun interface ComicDetectorFactory {
    fun open(modelFile: Path): ComicDetector
}

fun interface DetectorModelProvider {
    fun acquire(
        installId: String,
        progress: (downloaded: Long, total: Long) -> Unit,
    ): Path
}
