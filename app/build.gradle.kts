import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 1
val appVersionName = providers.gradleProperty("appVersionName").orNull ?: "1.0"

// National Rail (Rail Data Marketplace) key. Never committed: comes from
// -PrdmApiKey, the RDM_API_KEY env var (CI secret), or local.properties "rdm.apiKey".
val rdmApiKey: String = providers.gradleProperty("rdmApiKey").orNull
    ?: providers.environmentVariable("RDM_API_KEY").orNull
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }.getProperty("rdm.apiKey")
    }
    ?: ""

android {
    namespace = "com.tflwidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tflwidget"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "RDM_API_KEY", "\"${rdmApiKey}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.core:core-remoteviews:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
