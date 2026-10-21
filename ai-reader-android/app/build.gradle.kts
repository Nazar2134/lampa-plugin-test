plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aireader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aireader.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded personal app: sign release builds with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric downloads its Android runtime; use the same Maven Central mirror as the build.
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.maxHeapSize = "2g"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Unit tests run on the JVM, where Android's XML parser and org.json are stubs.
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20240303")
    // Android framework (SQLite, preferences, views) on the JVM, for persistence tests and screenshots.
    testImplementation("org.robolectric:robolectric:4.17")
}
