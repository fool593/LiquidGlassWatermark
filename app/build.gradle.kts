import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Release signing material lives outside version control; see keystore.properties.example. */
val keystorePropertiesFile = rootProject.file("keystore.properties")
// `java` alone would resolve to the Java plugin extension in a Gradle Kotlin script, hence the import.
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.liquidglass.watermark"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.liquidglass.watermark"
        minSdk = 26
        targetSdk = 37
        versionCode = 1518
        versionName = "15.18"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Kotlin/JVM target is aligned with compileOptions by AGP's built-in Kotlin support, so no
// separate `kotlin { compilerOptions { ... } }` block is needed (or honoured) here.

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Liquid glass material.
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.materials)
    implementation(libs.haze.glass)

    // Video preview + video export. The transformer/effect/ui artefacts are deliberately absent: the
    // exporters talk to MediaCodec/MediaMuxer directly, because the watermark is rasterised on the CPU
    // and a Media3 effect would have to reimplement the same material as a shader.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
