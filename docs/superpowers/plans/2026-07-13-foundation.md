# Masumi Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an installable Android application that atomically imports an image folder into an immutable, content-addressed project and writes deterministic manifest and report artifacts.

**Architecture:** Keep Android Storage Access Framework code in `app` and all ordering, hashing, staging, publication, serialization, and reporting rules in the pure Kotlin `pipeline-core` module. Publish a complete project by atomically moving one staged directory, so no partially imported project becomes visible.

**Tech Stack:** Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.3.0, Android SDK 36, JDK 17, kotlinx.serialization JSON 1.9.0, JUnit 4.

---

### Task 1: Create the Gradle and Android project skeleton

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `pipeline-core/build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/debug/res/values/strings.xml`
- Generate: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Declare the modules and dependency repositories**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "masumi"
include(":app", ":pipeline-core")
```

- [ ] **Step 2: Declare pinned build plugins**

```kotlin
// build.gradle.kts
plugins {
    id("com.android.application") version "8.13.2" apply false
    kotlin("android") version "2.3.0" apply false
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.serialization") version "2.3.0" apply false
}
```

- [ ] **Step 3: Configure the pure Kotlin core module**

```kotlin
// pipeline-core/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    useJUnit()
}
```

- [ ] **Step 4: Configure the Android module and debug isolation**

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "rs.masumi.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "rs.masumi.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":pipeline-core"))
}
```

- [ ] **Step 5: Generate and verify the wrapper**

Run:

```bash
tmp_gradle="$(mktemp -d)"
curl -fsSL https://services.gradle.org/distributions/gradle-8.13-bin.zip -o "$tmp_gradle/gradle.zip"
unzip -q "$tmp_gradle/gradle.zip" -d "$tmp_gradle"
"$tmp_gradle/gradle-8.13/bin/gradle" wrapper --gradle-version 8.13 --distribution-type bin
rm -rf "$tmp_gradle"
./gradlew projects
```

Expected: Gradle lists `:app` and `:pipeline-core` and exits with status 0.

- [ ] **Step 6: Commit the skeleton**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle app pipeline-core
git commit -m "build: scaffold Android foundation"
```

### Task 2: Select supported pages in deterministic order

**Files:**
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/importer/SourceSelectorTest.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/importer/SourceCandidate.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/importer/PageMediaType.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/importer/NaturalFileNameComparator.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/importer/SourceSelector.kt`

- [ ] **Step 1: Write the failing ordering and filtering test**

```kotlin
@Test
fun `selects supported direct files in natural order`() {
    val result = SourceSelector.select(
        listOf(
            candidate("10.jpg", "image/jpeg"),
            candidate("2.PNG", "image/png"),
            candidate(".hidden.webp", "image/webp"),
            candidate("notes.txt", "text/plain"),
            candidate("chapter", null, isDirectory = true),
            candidate("01.webp", "application/octet-stream"),
        ),
    )

    assertEquals(listOf("01.webp", "2.PNG", "10.jpg"), result.accepted.map { it.source.displayName })
    assertEquals(3, result.skippedCount)
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :pipeline-core:test --tests '*SourceSelectorTest'`

Expected: compilation fails because `SourceSelector` and its domain types do not exist.

- [ ] **Step 3: Implement source selection**

```kotlin
interface SourceCandidate {
    val displayName: String
    val mediaType: String?
    val isDirectory: Boolean
    fun openStream(): InputStream
}

enum class PageMediaType(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");
}

data class SelectedSource(val source: SourceCandidate, val mediaType: PageMediaType)
data class SourceSelection(val accepted: List<SelectedSource>, val skippedCount: Int)
```

Implement `NaturalFileNameComparator` by comparing numeric runs by significant length and value, text runs with `Locale.ROOT` case folding, and the original complete name as the final tie-breaker. `SourceSelector` rejects directories, hidden names, and unsupported image types before sorting accepted entries.

- [ ] **Step 4: Run the test and verify GREEN**

Run: `./gradlew :pipeline-core:test --tests '*SourceSelectorTest'`

Expected: the ordering/filtering test passes.

- [ ] **Step 5: Commit source selection**

```bash
git add pipeline-core/src
git commit -m "feat: select chapter source pages"
```

### Task 3: Define serializable manifests and reports

**Files:**
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/serialization/ProjectJsonTest.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/model/ProjectManifest.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/model/ImportReport.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/serialization/ProjectJson.kt`

- [ ] **Step 1: Write the failing manifest round-trip test**

```kotlin
@Test
fun `manifest round trip preserves stable page identity`() {
    val manifest = ProjectManifest(
        projectId = "project-1",
        createdAtEpochMillis = 1000,
        pages = listOf(
            PageRecord(0, "abc", "abc", "001.jpg", "image/jpeg", 3, "sources/abc.jpg"),
        ),
    )

    assertEquals(manifest, ProjectJson().decodeManifest(ProjectJson().encodeManifest(manifest)))
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :pipeline-core:test --tests '*ProjectJsonTest'`

Expected: compilation fails because the manifest and codec types do not exist.

- [ ] **Step 3: Implement the records and codec**

```kotlin
@Serializable
data class ProjectManifest(
    val schemaVersion: Int = 1,
    val projectId: String,
    val createdAtEpochMillis: Long,
    val pages: List<PageRecord>,
)

@Serializable
data class PageRecord(
    val order: Int,
    val pageId: String,
    val sourceSha256: String,
    val originalName: String,
    val mediaType: String,
    val byteLength: Long,
    val storedPath: String,
)
```

Add `ImportReport`, `ImportStatus`, `ImportError`, and `ProjectJson` using a configured `Json` instance with `prettyPrint`, `encodeDefaults`, and `ignoreUnknownKeys = false`. Provide encode/decode methods for both manifest and report.

- [ ] **Step 4: Run the test and verify GREEN**

Run: `./gradlew :pipeline-core:test --tests '*ProjectJsonTest'`

Expected: JSON round-trip tests pass.

- [ ] **Step 5: Commit serializable records**

```bash
git add pipeline-core/src
git commit -m "feat: define project artifact schemas"
```

### Task 4: Import and atomically publish an immutable project

**Files:**
- Create: `pipeline-core/src/test/kotlin/rs/masumi/core/importer/ProjectImporterTest.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/importer/IdSource.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/importer/ProjectImportException.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/importer/ProjectImporter.kt`
- Create: `pipeline-core/src/main/kotlin/rs/masumi/core/io/ProjectFileSystem.kt`

- [ ] **Step 1: Write the failing successful-import test**

```kotlin
@Test
fun `imports ordered pages and reuses duplicate source objects`() {
    val importer = importer(ids = listOf("project-1", "job-1"))
    val outcome = importer.importProject(
        listOf(
            bytes("2.jpg", "same"),
            bytes("1.jpg", "same"),
            bytes("10.png", "other", "image/png"),
        ),
    )

    assertEquals(listOf("1.jpg", "2.jpg", "10.png"), outcome.manifest.pages.map { it.originalName })
    assertEquals(outcome.manifest.pages[0].pageId, outcome.manifest.pages[1].pageId)
    assertEquals(2, Files.list(outcome.projectDirectory.resolve("sources")).use { it.count() })
    assertTrue(Files.exists(outcome.projectDirectory.resolve("manifest.json")))
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :pipeline-core:test --tests '*ProjectImporterTest.imports*'`

Expected: compilation fails because `ProjectImporter` does not exist.

- [ ] **Step 3: Implement staged import and publication**

```kotlin
class ProjectImporter(
    private val workspaceRoot: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val idSource: IdSource = UuidIdSource,
    private val json: ProjectJson = ProjectJson(),
    private val fileSystem: ProjectFileSystem = NioProjectFileSystem(),
) {
    fun importProject(sources: List<SourceCandidate>): ImportOutcome
}
```

The method obtains project and job IDs, selects sources, streams each accepted source to a temporary file while updating SHA-256, moves completed files to `sources/<digest>.<extension>`, writes manifest and reports inside staging, and publishes by atomically moving the staged directory to `projects/<projectId>`.

`ProjectFileSystem.publishDirectory` must request `StandardCopyOption.ATOMIC_MOVE` and fail if atomic directory replacement is unavailable. It must never silently fall back to a partially visible recursive copy.

- [ ] **Step 4: Run the successful-import test and verify GREEN**

Run: `./gradlew :pipeline-core:test --tests '*ProjectImporterTest.imports*'`

Expected: the project contains three ordered page entries, two source objects, a manifest, and both report formats.

- [ ] **Step 5: Commit immutable import**

```bash
git add pipeline-core/src
git commit -m "feat: import immutable manga projects"
```

### Task 5: Prove failure atomicity and safe reporting

**Files:**
- Modify: `pipeline-core/src/test/kotlin/rs/masumi/core/importer/ProjectImporterTest.kt`
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/importer/ProjectImporter.kt`
- Modify: `pipeline-core/src/main/kotlin/rs/masumi/core/importer/ProjectImportException.kt`

- [ ] **Step 1: Write the failing mid-copy failure test**

```kotlin
@Test
fun `failed source read publishes no project and writes sanitized reports`() {
    val importer = importer(ids = listOf("project-1", "job-1"))

    val error = assertFailsWith<ProjectImportException> {
        importer.importProject(listOf(failing("1.jpg", absolutePathInMessage = true)))
    }

    assertEquals(ImportErrorCode.IMPORT_IO_FAILED, error.code)
    assertFalse(Files.exists(root.resolve("projects/project-1")))
    val report = Files.readString(root.resolve("failed-reports/job-1.json"))
    assertFalse(report.contains(root.toString()))
    assertFalse(Files.exists(root.resolve("staging/project-1")))
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew :pipeline-core:test --tests '*ProjectImporterTest.failed*'`

Expected: the test fails until staging cleanup, stable error codes, and failure reports are implemented.

- [ ] **Step 3: Implement bounded failure behavior**

Map empty selections to `NO_SUPPORTED_PAGES` and unexpected stream/storage failures to `IMPORT_IO_FAILED`. Serialize fixed safe messages instead of raw exception messages. On failure, recursively remove the staged project, attempt to write `failed-reports/<jobId>.json` and `.txt`, then throw `ProjectImportException` with a project-relative report path.

- [ ] **Step 4: Run all core tests and verify GREEN**

Run: `./gradlew :pipeline-core:test`

Expected: every core test passes with no failed staging directories or leaked absolute paths.

- [ ] **Step 5: Commit failure semantics**

```bash
git add pipeline-core/src
git commit -m "feat: make project import failure atomic"
```

### Task 6: Connect Android folder selection to the core importer

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/java/rs/masumi/app/AndroidDocumentSource.kt`
- Create: `app/src/main/java/rs/masumi/app/DocumentTreeReader.kt`
- Create: `app/src/main/java/rs/masumi/app/MainActivity.kt`
- Create: `app/src/debug/AndroidManifest.xml`
- Create: `app/src/debug/java/rs/masumi/app/TestDocumentsProvider.kt`
- Create: `app/src/androidTest/java/rs/masumi/app/DocumentTreeReaderTest.kt`
- Create: `app/src/androidTest/java/rs/masumi/app/MainActivityLayoutTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Write failing device contract tests**

Register a debug-only `DocumentsProvider` that exposes two supported files, one unsupported file, and one directory. Write `DocumentTreeReaderTest` to assert that direct children are adapted with the provider names and MIME types and that a returned source stream yields the provider bytes. Write `MainActivityLayoutTest` to inflate the activity layout in the target context and assert that the import button, idle status, and idle progress state are correct. Keep real Activity launch verification as a separate ADB smoke test so OEM background-launch policy cannot make the instrumentation contract nondeterministic.

- [ ] **Step 2: Run the device tests and verify RED**

Run: `./gradlew :app:connectedDebugAndroidTest`

Expected: test compilation fails because `DocumentTreeReader`, `AndroidDocumentSource`, `MainActivity`, and the required view IDs do not exist.

- [ ] **Step 3: Implement the document adapter**

```kotlin
data class AndroidDocumentSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val displayName: String,
    override val mediaType: String?,
    override val isDirectory: Boolean,
) : SourceCandidate {
    override fun openStream(): InputStream =
        requireNotNull(resolver.openInputStream(uri)) { "Document stream unavailable" }
}
```

- [ ] **Step 4: Implement direct-child enumeration**

Use `DocumentsContract.buildChildDocumentsUriUsingTree` and query document ID, display name, MIME type, flags, and size. Convert each row to `AndroidDocumentSource`; do not recurse into child directories.

- [ ] **Step 5: Implement the minimal import screen**

`MainActivity` inflates `activity_main.xml`, which contains a title, import button, progress indicator, and status text. It launches `ACTION_OPEN_DOCUMENT_TREE`, persists read permission when granted, disables the button during import, runs `ProjectImporter` on a named background thread, and posts safe summary text back to the main thread.

- [ ] **Step 6: Run device tests and verify GREEN**

Run: `./gradlew :app:connectedDebugAndroidTest`

Expected: the provider and layout contracts pass on the connected device.

- [ ] **Step 7: Build and install the debug application**

Run:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n rs.masumi.app.dev/rs.masumi.app.MainActivity
```

Expected: the debug APK installs alongside any future release build, the main screen launches without a crash, and a UI hierarchy dump contains the import button and idle status.

- [ ] **Step 8: Commit the Android adapter**

```bash
git add app
git commit -m "feat: add Android chapter import"
```

### Task 7: Document, verify, and publish the foundation slice

**Files:**
- Modify: `README.md`
- Create: `docs/architecture/foundation.md`

- [ ] **Step 1: Document only public project behavior**

Describe the two modules, the current folder-import capability, build commands, output artifacts, and explicit non-features. Do not include local corpus names, device identifiers, account names, absolute paths, provider keys, or private benchmarks.

- [ ] **Step 2: Run the complete verification suite**

Run:

```bash
./gradlew clean :pipeline-core:test :app:assembleDebug
git diff --check
git status --short
private_path_pattern='/''Users/|/''home/[^/]+/|api[_ -]?key'
git grep -I -n -i -E "$private_path_pattern"
```

Expected: tests and build pass; diff check is clean; the sensitive-content scan returns no matches.

- [ ] **Step 3: Inspect the final public tree and APK metadata**

Run:

```bash
git ls-files
"$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" manifest application-id app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" manifest version-name app/build/outputs/apk/debug/app-debug.apk
```

Expected: no private images or local manifests are tracked; application ID is `rs.masumi.app.dev`; version name is `0.1.0`.

- [ ] **Step 4: Commit documentation and push the branch**

```bash
git add README.md docs
git commit -m "docs: explain the foundation slice"
git push -u origin codex/foundation
```

Expected: the remote branch points to the verified local commit and `main` remains unchanged.
