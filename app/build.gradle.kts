import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "rs.masumi.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "rs.masumi.app"
        // Vulkan OCR uses Vulkan 1.1 core entry points exposed by Android API 28+.
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }
    }

    signingConfigs {
        // Release installs must work from a bare APK download, which requires a
        // real signature. The keystore stays outside the repository; point
        // MASUMI_RELEASE_STORE_FILE (plus passwords) at it from
        // ~/.gradle/gradle.properties. Without the properties the release build
        // still assembles, just unsigned — debug builds are unaffected.
        providers.gradleProperty("MASUMI_RELEASE_STORE_FILE").orNull?.let { storePath ->
            create("release") {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("MASUMI_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("MASUMI_RELEASE_KEY_ALIAS").getOrElse("masumi")
                keyPassword = providers.gradleProperty("MASUMI_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            externalNativeBuild {
                cmake {
                    // AGP defaults debug native builds to -O0, which makes the
                    // ggml/llama inference kernels 10-30x slower than release.
                    // OCR speed must be representative in every install.
                    arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
                }
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":pipeline-core"))
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

val verifyBundledModels by tasks.registering {
    group = "verification"
    description = "Verifies the exact bundled ONNX model assets before building."
    val specifications = listOf(
        Triple(
            file("src/main/assets/models/comic-text-segmenter-512.onnx"),
            65_568_382L,
            "688cb2b55bc14e29957bb4dad768e7420a4b1f740b84ffadc83ecaac63846485",
        ),
        Triple(
            file("src/main/assets/models/aot-inpainting.onnx"),
            23_009_155L,
            "e0d8f438ca9567eccc9d358963427601b6f64a650cbe6189ec82fc43830a0390",
        ),
    )
    inputs.files(specifications.map { it.first })
    doLast {
        specifications.forEach { (model, expectedBytes, expectedSha256) ->
            check(model.isFile) {
                "Missing bundled model ${model.name}; run tools/prepare-bundled-models.sh"
            }
            check(model.length() == expectedBytes) {
                "Bundled model ${model.name} has an unexpected byte length"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            model.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualSha256 == expectedSha256) {
                "Bundled model ${model.name} has an unexpected SHA-256"
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyBundledModels)
}
