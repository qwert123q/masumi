package rs.masumi.app.library

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryImageDiskCacheTest {
    @Test
    fun `default disk budget is 256 MiB`() {
        assertEquals(256L * 1024L * 1024L, LibraryImageDiskCache.MAX_BYTES)
    }

    @Test
    fun `evicts least valuable oldest entry before recent reader page`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val cache = LibraryImageDiskCache(directory, maxBytes = 600L)
            cache.write("ordinary-cover", LibraryImageAssetKind.COVER, ByteArray(256) { 1 })
            cache.write("recent-page", LibraryImageAssetKind.READER_PAGE, ByteArray(256) { 2 })
            assertArrayEquals(ByteArray(256) { 2 }, cache.read("recent-page"))

            cache.write("new-cover", LibraryImageAssetKind.COVER, ByteArray(256) { 3 })

            assertNull(cache.read("ordinary-cover"))
            assertArrayEquals(ByteArray(256) { 2 }, cache.read("recent-page"))
            assertArrayEquals(ByteArray(256) { 3 }, cache.read("new-cover"))
            assertTrue(cache.sizeBytes() <= 600L)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `first viewport covers are retained before ordinary covers`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val cache = LibraryImageDiskCache(directory, maxBytes = 600L)
            cache.write("first", LibraryImageAssetKind.FIRST_VIEWPORT_COVER, ByteArray(256) { 1 })
            cache.write("ordinary", LibraryImageAssetKind.COVER, ByteArray(256) { 2 })
            cache.write("replacement", LibraryImageAssetKind.COVER, ByteArray(256) { 3 })

            assertArrayEquals(ByteArray(256) { 1 }, cache.read("first"))
            assertNull(cache.read("ordinary"))
            assertArrayEquals(ByteArray(256) { 3 }, cache.read("replacement"))
            assertTrue(cache.sizeBytes() <= 600L)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cached ordinary cover is promoted when it enters the first viewport`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val cache = LibraryImageDiskCache(directory, maxBytes = 600L)
            cache.write("now-visible", LibraryImageAssetKind.COVER, ByteArray(256) { 1 })
            cache.write("ordinary", LibraryImageAssetKind.COVER, ByteArray(256) { 2 })
            assertArrayEquals(
                ByteArray(256) { 1 },
                cache.read("now-visible", promoteTo = LibraryImageAssetKind.FIRST_VIEWPORT_COVER),
            )

            LibraryImageDiskCache(directory, maxBytes = 600L)
                .write("replacement", LibraryImageAssetKind.COVER, ByteArray(256) { 3 })

            assertArrayEquals(ByteArray(256) { 1 }, cache.read("now-visible"))
            assertNull(cache.read("ordinary"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `touch after process restart wins over legacy monotonic timestamps`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val cache = LibraryImageDiskCache(directory, maxBytes = 600L)
            cache.write("touched-after-restart", LibraryImageAssetKind.COVER, ByteArray(256) { 1 })
            val firstMetadata = onlyNewMetadata(directory, emptySet())
            cache.write("untouched-legacy", LibraryImageAssetKind.COVER, ByteArray(256) { 2 })
            val secondMetadata = onlyNewMetadata(directory, setOf(firstMetadata))

            // Simulate metadata written before a reboot, when System.nanoTime()
            // belonged to a different clock epoch and cannot be ordered against
            // access values from the new process.
            Files.write(firstMetadata, "COVER|9000000000000000".toByteArray(StandardCharsets.UTF_8))
            Files.write(secondMetadata, "COVER|9000000000000001".toByteArray(StandardCharsets.UTF_8))

            val restarted = LibraryImageDiskCache(directory, maxBytes = 600L)
            assertArrayEquals(ByteArray(256) { 1 }, restarted.read("touched-after-restart"))
            restarted.write("replacement", LibraryImageAssetKind.COVER, ByteArray(256) { 3 })

            assertArrayEquals(ByteArray(256) { 1 }, restarted.read("touched-after-restart"))
            assertNull(restarted.read("untouched-legacy"))
            assertArrayEquals(ByteArray(256) { 3 }, restarted.read("replacement"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `oversized or externally corrupted entry is removed before allocation`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val cache = LibraryImageDiskCache(directory, maxBytes = 300L)
            cache.write("corrupted", LibraryImageAssetKind.READER_PAGE, ByteArray(120) { 1 })
            val asset = Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().endsWith(LibraryImageDiskCache.ASSET_SUFFIX) }
                    .findFirst()
                    .orElseThrow()
            }
            Files.write(asset, ByteArray(301))

            assertNull(cache.read("corrupted"))
            assertTrue(Files.notExists(asset))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `restart removes an orphaned atomic-write temp file`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val maxBytes = 512L
            val payload = ByteArray(128) { 7 }
            LibraryImageDiskCache(directory, maxBytes).write(
                "surviving-entry",
                LibraryImageAssetKind.READER_PAGE,
                payload,
            )
            val asset = Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().endsWith(LibraryImageDiskCache.ASSET_SUFFIX) }
                    .findFirst()
                    .orElseThrow()
            }
            val orphanedTemp = Files.createTempFile(directory, asset.fileName.toString(), ".tmp")
            Files.write(orphanedTemp, ByteArray(400))
            assertTrue(directorySizeBytes(directory) > maxBytes)

            val restarted = LibraryImageDiskCache(directory, maxBytes)

            assertTrue("orphaned cache temp survived restart", Files.notExists(orphanedTemp))
            assertTrue(directorySizeBytes(directory) <= maxBytes)
            assertArrayEquals(payload, restarted.read("surviving-entry"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `trim removes an orphaned atomic-write temp file created after startup`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val maxBytes = 512L
            val cache = LibraryImageDiskCache(directory, maxBytes)
            cache.write("first-entry", LibraryImageAssetKind.COVER, ByteArray(128) { 1 })
            val asset = Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().endsWith(LibraryImageDiskCache.ASSET_SUFFIX) }
                    .findFirst()
                    .orElseThrow()
            }
            val orphanedTemp = Files.createTempFile(directory, asset.fileName.toString(), ".tmp")
            Files.write(orphanedTemp, ByteArray(400))
            assertTrue(directorySizeBytes(directory) > maxBytes)

            cache.write("second-entry", LibraryImageAssetKind.COVER, ByteArray(32) { 2 })

            assertTrue("orphaned cache temp survived trim", Files.notExists(orphanedTemp))
            assertTrue(directorySizeBytes(directory) <= maxBytes)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `trim counts metadata toward the actual directory budget`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val maxBytes = 260L
            val cache = LibraryImageDiskCache(directory, maxBytes)
            cache.write("old-entry", LibraryImageAssetKind.COVER, ByteArray(120) { 1 })
            cache.write("new-entry", LibraryImageAssetKind.COVER, ByteArray(120) { 2 })

            assertTrue("cache directory exceeded its byte budget", directorySizeBytes(directory) <= maxBytes)
            assertNull(cache.read("old-entry"))
            assertArrayEquals(ByteArray(120) { 2 }, cache.read("new-entry"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `restart trims complete entries to the actual directory budget`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val initial = LibraryImageDiskCache(directory, maxBytes = 1_024L)
            initial.write("old-entry", LibraryImageAssetKind.COVER, ByteArray(120) { 1 })
            initial.write("new-entry", LibraryImageAssetKind.COVER, ByteArray(120) { 2 })
            assertTrue(directorySizeBytes(directory) > 260L)

            val restarted = LibraryImageDiskCache(directory, maxBytes = 260L)

            assertTrue("restart left the cache over budget", directorySizeBytes(directory) <= 260L)
            assertNull(restarted.read("old-entry"))
            assertArrayEquals(ByteArray(120) { 2 }, restarted.read("new-entry"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `metadata promotion cannot push the directory over budget`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val maxBytes = 150L
            val payload = ByteArray(120) { 4 }
            val cache = LibraryImageDiskCache(directory, maxBytes)
            cache.write("promoted-entry", LibraryImageAssetKind.COVER, payload)

            assertArrayEquals(
                payload,
                cache.read("promoted-entry", promoteTo = LibraryImageAssetKind.FIRST_VIEWPORT_COVER),
            )

            assertTrue("metadata promotion exceeded the cache budget", directorySizeBytes(directory) <= maxBytes)
            assertNull(cache.read("promoted-entry"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun onlyNewMetadata(directory: Path, previous: Set<Path>): Path =
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(LibraryImageDiskCache.METADATA_SUFFIX) }
                .filter { it !in previous }
                .findFirst()
                .orElseThrow()
        }

    private fun directorySizeBytes(directory: Path): Long =
        Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter(Files::isRegularFile)
                .sumOf(Files::size)
        }
}
