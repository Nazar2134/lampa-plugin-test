import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.taptranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.taptranslate"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        // Public, non-secret key (standard Android debug credentials) so every build,
        // local or CI, can be installed over the previous one.
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // "lite" has no accessibility service: Play Protect blocks sideloaded apps that declare one.
    // "full" adds the floating tap-any-word button.
    flavorDimensions += "features"
    productFlavors {
        create("lite") {
            dimension = "features"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "TAP_TO_TRANSLATE", "false")
        }
        create("full") {
            dimension = "features"
            buildConfigField("boolean", "TAP_TO_TRANSLATE", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
