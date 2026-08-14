plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun gitCommitCount(): Int = try {
    val p = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(projectDir)
        .redirectErrorStream(true)
        .start()
    val out = p.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
    p.waitFor()
    out
} catch (e: Exception) {
    1
}

android {
    namespace = "com.soskitty.myday"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.soskitty.myday"
        minSdk = 26
        targetSdk = 34
        versionCode = gitCommitCount()
        versionName = "1.0.${gitCommitCount()}"
    }
    signingConfigs {
        create("release") {
            storeFile = file("myday.keystore")
            storePassword = "123456"
            keyAlias = "myday"
            keyPassword = "123456"
        }
        getByName("debug") {
            storeFile = file("myday.keystore")
            storePassword = "123456"
            keyAlias = "myday"
            keyPassword = "123456"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
}