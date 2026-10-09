import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
val ksProps = Properties().also { p -> rootProject.file("keystore.properties").inputStream().use { p.load(it) } }

android {
    namespace = "com.pravahax.portalx"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.pravahax.portalx.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.6.0"
    }
    signingConfigs {
        create("release") {
            storeFile = file(ksProps.getProperty("storeFile"))
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias")
            keyPassword = ksProps.getProperty("keyPassword")
            enableV1Signing = true; enableV2Signing = true; enableV3Signing = true
        }
    }
    buildTypes {
        release {
            // R8 on: our own package is kept whole (see proguard-rules.pro), libraries are shrunk.
            // Build with -Pportalx.minify=false to produce an un-shrunk APK if R8 ever misbehaves.
            val minify = (project.findProperty("portalx.minify") as String?)?.toBoolean() ?: true
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// v0.3.0: record each unit-test task's runtime classpath so ScreenshotTest can be run outside Gradle
// (on an x86_64 JVM, where Robolectric's native graphics exist).
tasks.withType<Test>().configureEach {
    doFirst { layout.buildDirectory.file("testcp-$name.txt").get().asFile.writeText(classpath.asPath) }
}
