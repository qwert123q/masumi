package rs.masumi.core.io

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

interface ProjectFileSystem {
    fun createDirectories(path: Path)
    fun createDirectory(path: Path)
    fun newOutputStream(path: Path): OutputStream
    fun exists(path: Path): Boolean
    fun deleteIfExists(path: Path)
    fun moveFile(source: Path, target: Path)
    fun replaceFile(source: Path, target: Path)
    fun writeUtf8(path: Path, content: String)
    fun replaceUtf8(path: Path, content: String)
    fun readUtf8(path: Path): String
    fun list(path: Path): List<Path>
    fun publishDirectory(stagingDirectory: Path, projectDirectory: Path)
    fun deleteRecursively(path: Path)
}

class NioProjectFileSystem : ProjectFileSystem {
    override fun createDirectories(path: Path) {
        Files.createDirectories(path)
    }

    override fun createDirectory(path: Path) {
        Files.createDirectory(path)
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

    override fun replaceFile(source: Path, target: Path) {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    override fun writeUtf8(path: Path, content: String) {
        newOutputStream(path).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(content)
        }
    }

    override fun replaceUtf8(path: Path, content: String) {
        val temporary = path.resolveSibling("${path.fileName}.new")
        deleteIfExists(temporary)
        try {
            writeUtf8(temporary, content)
            replaceFile(temporary, path)
        } catch (failure: Throwable) {
            runCatching { deleteIfExists(temporary) }
            throw failure
        }
    }

    override fun readUtf8(path: Path): String = Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
        reader.readText()
    }

    override fun list(path: Path): List<Path> {
        if (!Files.exists(path)) return emptyList()
        return Files.list(path).use { entries -> entries.iterator().asSequence().toList() }
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
