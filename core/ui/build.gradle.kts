plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pravahax.portalx.core.ui"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    api(project(":core:data"))
    // The design system every feature builds on (theme, shared components, resource ViewModels).
    val bom = platform("androidx.compose:compose-bom:2024.09.03")
    api(bom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.activity:activity-compose:1.9.2")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
}
