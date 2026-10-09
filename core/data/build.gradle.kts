plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
    namespace = "com.pravahax.portalx.core.data"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:net"))
    // Room (single source of truth for cached reads + the offline outbox) and WorkManager (outbox sender).
    api("androidx.room:room-runtime:2.7.1")
    api("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    api("androidx.work:work-runtime-ktx:2.9.1")
}
