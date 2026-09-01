package rs.masumi.app.library

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
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
    fun `cache uses a random UUID filename and persists the original key in metadata`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val key = "content://library/漫画/cover?size=large"
            val payload = ByteArray(32) { 9 }
            LibraryImageDiskCache(directory, maxBytes = 1_024L).write(
                key,
                LibraryImageAssetKind.COVER,
                payload,
            )

            val asset = Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().endsWith(LibraryImageDiskCache.ASSET_SUFFIX) }
                    .findFirst()
                    .orElseThrow()
            }
            val stem = asset.fileName.toString().removeSuffix(LibraryImageDiskCache.ASSET_SUFFIX)
            assertEquals(stem, UUID.fromString(stem).toString())
            val metadata = directory.resolve("$stem${LibraryImageDiskCache.METADATA_SUFFIX}")
            val encodedKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.toByteArray(StandardCharsets.UTF_8))
            assertTrue(String(Files.readAllBytes(metadata), StandardCharsets.UTF_8).contains(encodedKey))
            assertArrayEquals(payload, LibraryImageDiskCache(directory, 1_024L).read(key))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `instances sharing a directory observe entries written after construction`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val first = LibraryImageDiskCache(directory, maxBytes = 1_024L)
            val second = LibraryImageDiskCache(directory, maxBytes = 1_024L)
            val payload = ByteArray(32) { 5 }

            second.write("reader-entry", LibraryImageAssetKind.READER_PAGE, payload)

            assertArrayEquals(payload, first.read("reader-entry"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `instances sharing a directory enforce one combined byte budget`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val maxBytes = 260L
            val first = LibraryImageDiskCache(directory, maxBytes)
            val second = LibraryImageDiskCache(directory, maxBytes)

            first.write("shelf-entry", LibraryImageAssetKind.COVER, ByteArray(120) { 1 })
            second.write("reader-entry", LibraryImageAssetKind.READER_PAGE, ByteArray(120) { 2 })

            assertTrue("shared cache directory exceeded its byte budget", directorySizeBytes(directory) <= maxBytes)
        } finally {
            directory.toFile().deleteRecursively()
        }
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
    fun `restart discards legacy cache entries that have no persisted key`() {
        val directory = Files.createTempDirectory("masumi-library-image-cache")
        try {
            val legacyStem = "a".repeat(64)
            val legacyAsset = directory.resolve("$legacyStem${LibraryImageDiskCache.ASSET_SUFFIX}")
            val legacyMetadata = directory.resolve("$legacyStem${LibraryImageDiskCache.METADATA_SUFFIX}")
            Files.write(legacyAsset, ByteArray(32) { 1 })
            Files.write(legacyMetadata, "v2|COVER|123".toByteArray(StandardCharsets.UTF_8))

            LibraryImageDiskCache(directory, maxBytes = 600L)

            assertTrue(Files.notExists(legacyAsset))
            assertTrue(Files.notExists(legacyMetadata))
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
            val maxBytes = 170L
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

    private fun directorySizeBytes(directory: Path): Long =
        Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter(Files::isRegularFile)
                .sumOf(Files::size)
        }
}
