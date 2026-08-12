plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voiceping.offlinetranscription"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voiceping.transcribe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // This fork targets the physical OnePlus 13 (SM8750) and deliberately
            // ships just its production ABI rather than carrying emulator libraries.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

tasks.register<Copy>("packageOneplus13Debug") {
    group = "distribution"
    description = "Builds the arm64-v8a OnePlus 13 debug APK with a stable, installable name."
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug"))
    include("app-debug.apk")
    rename { "OfflineTranscribeLab-oneplus13-debug.apk" }
    into(layout.buildDirectory.dir("outputs/apk/oneplus13"))
}

dependencies {
    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)

    // DataStore
    implementation(libs.datastore)

    // Navigation
    implementation(libs.navigation)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)

    // Local searchable transcript history
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp
    implementation(libs.okhttp)

    // Coroutines
    implementation(libs.coroutines)

    // sherpa-onnx (offline ASR: Moonshine, SenseVoice)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Google ML Kit Translation (offline)
    implementation(libs.mlkit.translate)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)

    // Instrumentation testing
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
