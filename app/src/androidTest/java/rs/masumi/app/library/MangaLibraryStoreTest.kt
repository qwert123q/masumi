package rs.masumi.app.library

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rs.masumi.app.TestDocumentsProvider
import rs.masumi.core.model.PageRecord

@RunWith(AndroidJUnit4::class)
class MangaLibraryStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        TestDocumentsProvider.AUTHORITY,
        TestDocumentsProvider.ROOT_ID,
    )

    @Before
    fun setUp() {
        TestDocumentsProvider.clearDynamicDocuments(context)
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.clearDynamicDocuments(context)
    }

    @Test
    fun projectMetadataAndOutputPagesSurviveAStoreReload() {
        val store = MangaLibraryStore(context.contentResolver, treeUri)
        val project = store.ensureProject(
            projectId = "project-one",
            title = "第一话 / 开始",
            createdAtEpochMillis = 123L,
            sourceTreeUri = treeUri,
        )
        assertNotNull(project.outputDirectoryUri)
        val output = requireNotNull(project.outputDirectoryUri)
        createOutput(output, "0002.png", "two")
        createOutput(output, "0001.png", "one")
        // Older chapters may still contain WebP; both formats remain readable.
        createOutput(output, "0003.webp", "three")
        createOutput(output, "notes.txt", "ignored")

        val reloaded = MangaLibraryStore(context.contentResolver, treeUri)
        val projects = reloaded.projects()

        assertEquals(1, projects.size)
        assertEquals("project-one", projects.single().metadata.projectId)
        assertEquals(3, projects.single().metadata.schemaVersion)
        assertEquals("第一话 开始", projects.single().metadata.title)
        assertEquals("第一话 开始", reloaded.documentDisplayName(project.mangaDirectoryUri))
        assertEquals("生肉", reloaded.documentDisplayName(project.sourceDirectoryUri!!))
        assertEquals("翻译后", reloaded.documentDisplayName(project.outputDirectoryUri!!))
        assertNotEquals(project.outputDirectoryUri, project.directoryUri)
        assertEquals(3, projects.single().outputPageCount)
        assertEquals(
            listOf("0001.png", "0002.png", "0003.webp"),
            reloaded.outputPages("project-one").map { it.name },
        )
    }

    @Test
    fun ensuringTheSameProjectReusesItsDirectory() {
        val store = MangaLibraryStore(context.contentResolver, treeUri)
        val first = store.ensureProject("project-one", "第一话", 123L, treeUri)
        val second = store.ensureProject("project-one", "ignored replacement", 456L, treeUri)

        assertEquals(first.directoryUri, second.directoryUri)
        assertEquals(1, store.projects().size)
        assertEquals("第一话", second.metadata.title)
    }

    @Test
    fun repeatImportsCreateIndependentFoldersThatCanBeRenamedAndDeleted() {
        val fingerprint = "a".repeat(64)
        val store = MangaLibraryStore(context.contentResolver, treeUri)
        val older = store.ensureProject(
            "project-old",
            "铃井",
            1_000L,
            treeUri,
            sourceFingerprint = fingerprint,
        )
        val newer = store.ensureProject(
            "project-new",
            "铃井",
            2_000L,
            treeUri,
            sourceFingerprint = fingerprint,
        )

        assertNotEquals(older.mangaDirectoryUri, newer.mangaDirectoryUri)
        assertNotEquals(older.sourceDirectoryUri, newer.sourceDirectoryUri)
        assertNotEquals(older.outputDirectoryUri, newer.outputDirectoryUri)
        assertEquals("铃井", store.documentDisplayName(older.directoryUri))
        assertEquals("铃井 (2)", store.documentDisplayName(newer.directoryUri))
        assertEquals(
            listOf("project-new", "project-old"),
            store.refreshProjects().map { it.metadata.projectId },
        )

        val renamed = store.renameProject("project-new", "铃井 新译")
        assertEquals("铃井 新译", renamed.metadata.title)
        assertEquals("铃井 新译", store.documentDisplayName(renamed.directoryUri))

        assertTrue(store.deleteProject("project-old"))
        assertFalse(store.deleteProject("project-old"))
        assertEquals(
            listOf("project-new"),
            store.refreshProjects().map { it.metadata.projectId },
        )
    }

    @Test
    fun reusesByteIdenticalLegacyArchiveWithoutCreatingAStableNameCopy() {
        val bytes = "raw-jpeg-content".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val cacheDirectory = instrumentation.targetContext.cacheDir.toPath()
        Files.createDirectories(cacheDirectory)
        val privateProject = Files.createTempDirectory(cacheDirectory, "library-source-")
        try {
            val source = privateProject.resolve("sources/$digest.jpg")
            Files.createDirectories(source.parent)
            Files.write(source, bytes)
            val page = PageRecord(
                order = 0,
                pageId = digest,
                sourceSha256 = digest,
                originalName = "第 01 页.jpg",
                mediaType = "image/jpeg",
                byteLength = bytes.size.toLong(),
                storedPath = "sources/$digest.jpg",
            )
            val store = MangaLibraryStore(context.contentResolver, treeUri)
            val project = store.ensureProject(
                "source-project",
                "铃井",
                123L,
                treeUri,
                sourceFingerprint = mangaSourceFingerprint(listOf(page)),
            )
            createOutput(requireNotNull(project.sourceDirectoryUri), "第 01 页.jpg", bytes.decodeToString())

            store.archiveSourcePages("source-project", privateProject, listOf(page))
            // Repeating the archive must reuse the existing document.
            store.archiveSourcePages("source-project", privateProject, listOf(page))

            val archived = store.sourcePages("source-project").single()
            assertEquals("第 01 页.jpg", archived.name)
            assertTrue(context.contentResolver.openInputStream(archived.uri)!!.use {
                it.readBytes().contentEquals(bytes)
            })
        } finally {
            privateProject.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyCollisionIsReusedOnlyByThePageWhoseContentMatches() {
        val firstBytes = "first-content".toByteArray()
        val secondBytes = "other-content".toByteArray()
        assertEquals(firstBytes.size, secondBytes.size)
        val firstDigest = sha256(firstBytes)
        val secondDigest = sha256(secondBytes)
        val cacheDirectory = instrumentation.targetContext.cacheDir.toPath()
        Files.createDirectories(cacheDirectory)
        val privateProject = Files.createTempDirectory(cacheDirectory, "library-source-collision-")
        try {
            val firstSource = privateProject.resolve("sources/$firstDigest.jpg")
            val secondSource = privateProject.resolve("sources/$secondDigest.jpg")
            Files.createDirectories(firstSource.parent)
            Files.write(firstSource, firstBytes)
            Files.write(secondSource, secondBytes)
            val firstPage = PageRecord(
                order = 0,
                pageId = firstDigest,
                sourceSha256 = firstDigest,
                originalName = "chapter/page.jpg",
                mediaType = "image/jpeg",
                byteLength = firstBytes.size.toLong(),
                storedPath = "sources/$firstDigest.jpg",
            )
            val secondPage = PageRecord(
                order = 1,
                pageId = secondDigest,
                sourceSha256 = secondDigest,
                originalName = "chapter\\page.jpg",
                mediaType = "image/jpeg",
                byteLength = secondBytes.size.toLong(),
                storedPath = "sources/$secondDigest.jpg",
            )
            val pages = listOf(firstPage, secondPage)
            val store = MangaLibraryStore(context.contentResolver, treeUri)
            val project = store.ensureProject(
                "collision-project",
                "碰撞",
                123L,
                treeUri,
                sourceFingerprint = mangaSourceFingerprint(pages),
            )
            createOutput(
                requireNotNull(project.sourceDirectoryUri),
                "chapter_page.jpg",
                secondBytes.decodeToString(),
            )

            store.archiveSourcePages("collision-project", privateProject, pages)

            val archived = store.sourcePages("collision-project")
            assertEquals(
                listOf("000001-$firstDigest-chapter_page.jpg", "chapter_page.jpg"),
                archived.map { it.name },
            )
            val archivedContent = archived.associate { page ->
                page.name to context.contentResolver.openInputStream(page.uri)!!.use { it.readBytes() }
            }
            assertTrue(
                requireNotNull(archivedContent["000001-$firstDigest-chapter_page.jpg"])
                    .contentEquals(firstBytes),
            )
            assertTrue(requireNotNull(archivedContent["chapter_page.jpg"]).contentEquals(secondBytes))
        } finally {
            privateProject.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyNameThatOccupiesAnotherPagesStableNameIsPreserved() {
        val firstBytes = "first-content".toByteArray()
        val secondBytes = "other-content".toByteArray()
        assertEquals(firstBytes.size, secondBytes.size)
        val firstDigest = sha256(firstBytes)
        val secondDigest = sha256(secondBytes)
        val cacheDirectory = instrumentation.targetContext.cacheDir.toPath()
        Files.createDirectories(cacheDirectory)
        val privateProject = Files.createTempDirectory(cacheDirectory, "library-stable-legacy-collision-")
        try {
            val firstSource = privateProject.resolve("sources/$firstDigest.jpg")
            val secondSource = privateProject.resolve("sources/$secondDigest.jpg")
            Files.createDirectories(firstSource.parent)
            Files.write(firstSource, firstBytes)
            Files.write(secondSource, secondBytes)
            val firstPage = PageRecord(
                order = 0,
                pageId = firstDigest,
                sourceSha256 = firstDigest,
                originalName = "page.jpg",
                mediaType = "image/jpeg",
                byteLength = firstBytes.size.toLong(),
                storedPath = "sources/$firstDigest.jpg",
            )
            val occupiedStableName = mangaArchiveSourceFileName(firstPage)
            val secondPage = PageRecord(
                order = 1,
                pageId = secondDigest,
                sourceSha256 = secondDigest,
                originalName = occupiedStableName,
                mediaType = "image/jpeg",
                byteLength = secondBytes.size.toLong(),
                storedPath = "sources/$secondDigest.jpg",
            )
            val pages = listOf(firstPage, secondPage)
            val store = MangaLibraryStore(context.contentResolver, treeUri)
            val project = store.ensureProject(
                "stable-legacy-collision-project",
                "稳定名碰撞",
                123L,
                treeUri,
                sourceFingerprint = mangaSourceFingerprint(pages),
            )
            createOutput(
                requireNotNull(project.sourceDirectoryUri),
                occupiedStableName,
                secondBytes.decodeToString(),
            )

            store.archiveSourcePages("stable-legacy-collision-project", privateProject, pages)
            store.archiveSourcePages("stable-legacy-collision-project", privateProject, pages)

            val firstAlternateName = mangaArchiveAlternativeSourceFileName(firstPage, 2)
            val secondStableName = mangaArchiveSourceFileName(secondPage)
            val archived = store.sourcePages("stable-legacy-collision-project")
            assertEquals(3, archived.size)
            assertEquals(
                setOf(occupiedStableName, firstAlternateName, secondStableName),
                archived.mapTo(mutableSetOf()) { it.name },
            )
            assertTrue(archived.groupingBy { it.name }.eachCount().values.all { it == 1 })
            val archivedContent = archived.associate { page ->
                page.name to context.contentResolver.openInputStream(page.uri)!!.use { it.readBytes() }
            }
            assertTrue(requireNotNull(archivedContent[occupiedStableName]).contentEquals(secondBytes))
            assertTrue(requireNotNull(archivedContent[firstAlternateName]).contentEquals(firstBytes))
            assertTrue(requireNotNull(archivedContent[secondStableName]).contentEquals(secondBytes))
        } finally {
            privateProject.toFile().deleteRecursively()
        }
    }

    @Test
    fun differentLengthStableOccupantIsPreservedAndUsesDeterministicAlternate() {
        val sourceBytes = "longer-source-content".toByteArray()
        val occupiedBytes = "short".toByteArray()
        val digest = sha256(sourceBytes)
        val cacheDirectory = instrumentation.targetContext.cacheDir.toPath()
        Files.createDirectories(cacheDirectory)
        val privateProject = Files.createTempDirectory(cacheDirectory, "library-stable-length-collision-")
        try {
            val source = privateProject.resolve("sources/$digest.jpg")
            Files.createDirectories(source.parent)
            Files.write(source, sourceBytes)
            val page = PageRecord(
                order = 0,
                pageId = digest,
                sourceSha256 = digest,
                originalName = "page.jpg",
                mediaType = "image/jpeg",
                byteLength = sourceBytes.size.toLong(),
                storedPath = "sources/$digest.jpg",
            )
            val stableName = mangaArchiveSourceFileName(page)
            val alternateName = mangaArchiveAlternativeSourceFileName(page, 2)
            val store = MangaLibraryStore(context.contentResolver, treeUri)
            val project = store.ensureProject(
                "stable-length-collision-project",
                "长度碰撞",
                123L,
                treeUri,
                sourceFingerprint = mangaSourceFingerprint(listOf(page)),
            )
            createOutput(
                requireNotNull(project.sourceDirectoryUri),
                stableName,
                occupiedBytes.decodeToString(),
            )

            store.archiveSourcePages("stable-length-collision-project", privateProject, listOf(page))
            store.archiveSourcePages("stable-length-collision-project", privateProject, listOf(page))

            val archived = store.sourcePages("stable-length-collision-project")
            assertEquals(2, archived.size)
            assertEquals(setOf(stableName, alternateName), archived.mapTo(mutableSetOf()) { it.name })
            assertTrue(archived.groupingBy { it.name }.eachCount().values.all { it == 1 })
            val archivedContent = archived.associate { archivedPage ->
                archivedPage.name to context.contentResolver.openInputStream(archivedPage.uri)!!.use {
                    it.readBytes()
                }
            }
            assertTrue(requireNotNull(archivedContent[stableName]).contentEquals(occupiedBytes))
            assertTrue(requireNotNull(archivedContent[alternateName]).contentEquals(sourceBytes))
        } finally {
            privateProject.toFile().deleteRecursively()
        }
    }

    @Test
    fun occupiedPrimaryAndFirstAlternateArePreservedBeforeReusingSecondAlternate() {
        val sourceBytes = "expected-content".toByteArray()
        val primaryBytes = "occupied-primary".toByteArray()
        val firstAlternateBytes = "occupied-alternate".toByteArray()
        val digest = sha256(sourceBytes)
        val cacheDirectory = instrumentation.targetContext.cacheDir.toPath()
        Files.createDirectories(cacheDirectory)
        val privateProject = Files.createTempDirectory(cacheDirectory, "library-stable-multi-collision-")
        try {
            val source = privateProject.resolve("sources/$digest.jpg")
            Files.createDirectories(source.parent)
            Files.write(source, sourceBytes)
            val page = PageRecord(
                order = 0,
                pageId = digest,
                sourceSha256 = digest,
                originalName = "page.jpg",
                mediaType = "image/jpeg",
                byteLength = sourceBytes.size.toLong(),
                storedPath = "sources/$digest.jpg",
            )
            val stableName = mangaArchiveSourceFileName(page)
            val firstAlternateName = mangaArchiveAlternativeSourceFileName(page, 2)
            val secondAlternateName = mangaArchiveAlternativeSourceFileName(page, 3)
            val store = MangaLibraryStore(context.contentResolver, treeUri)
            val project = store.ensureProject(
                "stable-multi-collision-project",
                "连续碰撞",
                123L,
                treeUri,
                sourceFingerprint = mangaSourceFingerprint(listOf(page)),
            )
            val sourceDirectory = requireNotNull(project.sourceDirectoryUri)
            createOutput(sourceDirectory, stableName, primaryBytes.decodeToString())
            createOutput(sourceDirectory, firstAlternateName, firstAlternateBytes.decodeToString())

            store.archiveSourcePages("stable-multi-collision-project", privateProject, listOf(page))
            store.archiveSourcePages("stable-multi-collision-project", privateProject, listOf(page))

            val archived = store.sourcePages("stable-multi-collision-project")
            assertEquals(3, archived.size)
            assertEquals(
                setOf(stableName, firstAlternateName, secondAlternateName),
                archived.mapTo(mutableSetOf()) { it.name },
            )
            assertTrue(archived.groupingBy { it.name }.eachCount().values.all { it == 1 })
            val archivedContent = archived.associate { archivedPage ->
                archivedPage.name to context.contentResolver.openInputStream(archivedPage.uri)!!.use {
                    it.readBytes()
                }
            }
            assertTrue(requireNotNull(archivedContent[stableName]).contentEquals(primaryBytes))
            assertTrue(
                requireNotNull(archivedContent[firstAlternateName]).contentEquals(firstAlternateBytes),
            )
            assertTrue(requireNotNull(archivedContent[secondAlternateName]).contentEquals(sourceBytes))
        } finally {
            privateProject.toFile().deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun createOutput(parent: android.net.Uri, name: String, content: String): android.net.Uri {
        val uri = requireNotNull(
            DocumentsContract.createDocument(context.contentResolver, parent, "image/png", name),
        )
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(content.toByteArray()) }
        return uri
    }
}
