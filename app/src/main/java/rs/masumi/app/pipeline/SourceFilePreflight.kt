package rs.masumi.app.pipeline

import java.nio.file.Files
import java.nio.file.Path
import rs.masumi.core.model.PageRecord

internal object SourceFilePreflight {
    fun resolve(projectDirectory: Path, page: PageRecord): Path {
        require(page.storedPath.isNotBlank()) { "source path must not be blank" }
        val relativePath = projectDirectory.fileSystem.getPath(page.storedPath)
        require(!relativePath.isAbsolute) { "source path must be relative" }
        require(relativePath.none { it.toString() == ".." }) { "source path is unsafe" }

        val root = projectDirectory.toAbsolutePath().normalize()
        val source = root.resolve(relativePath).normalize()
        require(source.startsWith(root)) { "source path escaped project" }
        if (!Files.isRegularFile(source)) throw SourceFilePreflightException("SOURCE_MISSING")
        if (Files.size(source) != page.byteLength) {
            throw SourceFilePreflightException("SOURCE_LENGTH_MISMATCH")
        }
        return source
    }
}

internal class SourceFilePreflightException(
    val code: String,
) : RuntimeException(code)
