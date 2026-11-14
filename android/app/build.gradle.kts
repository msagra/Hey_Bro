import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}

val envProps = Properties()
val rootDirFile = rootDir.parentFile ?: rootDir
val envFile = File(rootDirFile, ".env")
val envExampleFile = File(rootDirFile, ".env.example")
val targetEnv = if (envFile.exists()) envFile else envExampleFile

if (targetEnv.exists()) {
    targetEnv.bufferedReader().use { reader ->
        reader.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val idx = trimmed.indexOf("=")
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                envProps.setProperty(key, value)
            }
        }
    }
}

val geminiApiKey = envProps.getProperty("GEMINI_API_KEY", "")
val geminiLiveModel = envProps.getProperty("GEMINI_LIVE_MODEL", "models/gemini-3.8-live")
val geminiRestModel = envProps.getProperty("GEMINI_REST_MODEL", "gemini-3.8-flash")
val groqApiKey = envProps.getProperty("GROQ_API_KEY", "")

android {
    namespace  = "com.example.hey_bro"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.example.hey_bro"
        // Minimum Android 8 (Oreo) – required for TYPE_APPLICATION_OVERLAY, VibrationEffect, startForegroundService
        minSdk        = 26
        targetSdk     = 35
        versionCode   = flutter.versionCode
        versionName   = flutter.versionName

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GEMINI_LIVE_MODEL", "\"$geminiLiveModel\"")
        buildConfigField("String", "GEMINI_REST_MODEL", "\"$geminiRestModel\"")
        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // Security for EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // JSON parsing
    implementation("org.json:json:20231013")
}
