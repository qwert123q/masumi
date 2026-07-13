package rs.masumi.core.io

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

interface ProjectFileSystem {
    fun createDirectories(path: Path)
    fun newOutputStream(path: Path): OutputStream
    fun exists(path: Path): Boolean
    fun deleteIfExists(path: Path)
    fun moveFile(source: Path, target: Path)
    fun writeUtf8(path: Path, content: String)
    fun publishDirectory(stagingDirectory: Path, projectDirectory: Path)
    fun deleteRecursively(path: Path)
}

class NioProjectFileSystem : ProjectFileSystem {
    override fun createDirectories(path: Path) {
        Files.createDirectories(path)
    }

    override fun newOutputStream(path: Path): OutputStream = Files.newOutputStream(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )

    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }

    override fun moveFile(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun writeUtf8(path: Path, content: String) {
        Files.writeString(
            path,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
    }

    override fun publishDirectory(stagingDirectory: Path, projectDirectory: Path) {
        Files.move(stagingDirectory, projectDirectory, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return

        Files.walk(path).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
