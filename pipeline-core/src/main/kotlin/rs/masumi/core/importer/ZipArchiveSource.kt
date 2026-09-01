package rs.masumi.core.importer

import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Exposes supported comic pages from a ZIP/CBZ without extracting entry paths
 * onto the filesystem. Callers own [archivePath] and must delete it separately.
 */
class ZipArchiveSource private constructor(
    private val archive: ZipFile,
    val sources: List<SourceCandidate>,
) : Closeable {
    override fun close() {
        archive.close()
    }

    companion object {
        const val MAXIMUM_PAGE_COUNT = 5_000
        const val MAXIMUM_PAGE_BYTES = 200L * 1024 * 1024
        const val MAXIMUM_TOTAL_PAGE_BYTES = 4L * 1024 * 1024 * 1024

        fun open(archivePath: Path): ZipArchiveSource {
            val archive = ZipFile(archivePath.toFile())
            return try {
                val accepted = mutableListOf<SourceCandidate>()
                val seenNames = mutableSetOf<String>()
                var declaredBytes = 0L
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val displayName = normalizedEntryName(entry.name)
                    validateSafeEntryName(displayName)
                    if (displayName.pathComponents().any(::isHiddenArchiveComponent)) continue
                    if (PageMediaType.detect(displayName, null) == null) continue
                    if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
                        throw IOException("ZIP entry uses an unsupported compression method")
                    }
                    if (entry.size > MAXIMUM_PAGE_BYTES) {
                        throw IOException("ZIP page exceeds the size limit")
                    }
                    if (entry.size >= 0L) {
                        declaredBytes = Math.addExact(declaredBytes, entry.size)
                        if (declaredBytes > MAXIMUM_TOTAL_PAGE_BYTES) {
                            throw IOException("ZIP pages exceed the total size limit")
                        }
                    }
                    if (!seenNames.add(displayName.lowercase())) {
                        throw IOException("ZIP contains duplicate page paths")
                    }
                    accepted += ZipEntrySource(archive, entry, displayName)
                    if (accepted.size > MAXIMUM_PAGE_COUNT) {
                        throw IOException("ZIP contains too many pages")
                    }
                }
                ZipArchiveSource(archive, accepted)
            } catch (failure: Throwable) {
                archive.close()
                throw failure
            }
        }

        private fun normalizedEntryName(value: String): String {
            var normalized = value.replace('\\', '/').trim()
            while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
            return normalized
        }

        private fun validateSafeEntryName(value: String) {
            val components = value.pathComponents()
            if (
                value.isBlank() ||
                value.startsWith('/') ||
                value.any(Char::isISOControl) ||
                components.isEmpty() ||
                components.any { it.isBlank() || it == "." || it == ".." }
            ) {
                throw IOException("ZIP contains an unsafe entry path")
            }
        }

        private fun String.pathComponents(): List<String> = split('/')

        private fun isHiddenArchiveComponent(value: String): Boolean =
            value.startsWith('.') || value.equals("__MACOSX", ignoreCase = true)
    }

    private class ZipEntrySource(
        private val archive: ZipFile,
        private val entry: ZipEntry,
        override val displayName: String,
    ) : SourceCandidate {
        override val mediaType: String? = null
        override val isDirectory: Boolean = false

        override fun openStream(): InputStream =
            SizeLimitedInputStream(archive.getInputStream(entry), MAXIMUM_PAGE_BYTES)
    }

    private class SizeLimitedInputStream(
        input: InputStream,
        private val maximumBytes: Long,
    ) : FilterInputStream(input) {
        private var consumed = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) recordBytes(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) recordBytes(count)
            return count
        }

        private fun recordBytes(count: Int) {
            consumed += count
            if (consumed > maximumBytes) throw IOException("ZIP page exceeds the size limit")
        }
    }
}
