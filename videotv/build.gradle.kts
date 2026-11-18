plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * OpenTune Videos for Android TV and Google TV: music videos only, full screen,
 * on the same :core as the other apps (its stream resolution, search and parsing).
 * Its version is the phone app's, read from app/build.gradle.kts.
 */
val appBuild = rootProject.file("app/build.gradle.kts").readText()
val appVersionName = Regex("""versionName = "([^"]+)"""").find(appBuild)!!.groupValues[1]
val appVersionCode = Regex("""versionCode = (\d+)""").find(appBuild)!!.groupValues[1].toInt()
// Each CI build counts up (-PvideotvBuild=<run number>), so every release installs over the last.
val videotvBuild = providers.gradleProperty("videotvBuild").map { it.toInt() }.getOrElse(0)

android {
    namespace = "com.opentune.videotv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.opentune.videotv"
        // Android 8.0, the same floor as :core.
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode * 10_000 + videotvBuild.coerceIn(0, 9_999)
        versionName = if (videotvBuild > 0) "$appVersionName-tv.$videotvBuild" else appVersionName
        // Boxes and sticks are ARM, most still 32-bit; x86_64 is for the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    val releaseKeystore = System.getenv("OPENTUNE_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("OPENTUNE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENTUNE_KEY_ALIAS")
                keyPassword = System.getenv("OPENTUNE_KEY_PASSWORD")
            }
        }
    }
    val unsignedRelease = providers.gradleProperty("unsignedRelease").map { it.toBoolean() }.getOrElse(false)

    buildTypes {
        release {
            // The phone app's rules: stream resolution reaches its libraries by reflection.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("app/proguard-rules.pro"),
            )
            signingConfig = if (unsignedRelease) null else (signingConfigs.findByName("release") ?: signingConfigs.getByName("debug"))
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        // A new module: report what lint finds without failing the whole build on it.
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // The video surface and its remote-friendly transport controls.
    implementation("androidx.media3:media3-ui:1.11.0")
    // The phone-remote pairing code on the screen: drawn from this, read by the phone.
    implementation("com.google.zxing:core:3.5.4")
}
