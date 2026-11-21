import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.closepaw.rescue"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.closepaw.rescue"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Endpoint + shared secret are injected at build time so they never live in the repo.
        // Defaults are empty → the client refuses to start and says so in logcat.
        val endpoint = (project.findProperty("rescue.endpoint") as String?) ?: System.getenv("RESCUE_ENDPOINT") ?: ""
        val secret = (project.findProperty("rescue.secret") as String?) ?: System.getenv("RESCUE_SECRET") ?: ""
        val pin = (project.findProperty("rescue.pin") as String?) ?: System.getenv("RESCUE_PIN") ?: ""
        buildConfigField("String", "RESCUE_ENDPOINT", "\"$endpoint\"")
        buildConfigField("String", "RESCUE_SECRET", "\"$secret\"")
        buildConfigField("String", "RESCUE_PIN", "\"$pin\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin 2.3 compilerOptions DSL (kotlinOptions.jvmTarget is an error now)
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
