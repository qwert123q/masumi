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
