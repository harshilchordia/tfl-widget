import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Versioning ----
// Releases (CI) pass -PappVersionName / -PappVersionCode from the git tag.
// Local builds use the latest vX.Y.Z tag, so an Android Studio build has the same
// version code as the newest release and can be installed over it (and vice versa)
// without Android rejecting it as a downgrade.
fun versionCodeOf(name: String): Int? {
    val m = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?$""").find(name.trim()) ?: return null
    val (major, minor, patch) = m.destructured
    return major.toInt() * 1_000_000 + minor.toInt() * 1_000 + (patch.ifEmpty { "0" }).toInt()
}

val latestTag: String? = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

val appVersionName = providers.gradleProperty("appVersionName").orNull
    ?: latestTag?.removePrefix("v") ?: "1.0"
val appVersionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()
    ?: latestTag?.let(::versionCodeOf) ?: 1

// ---- Signing ----
// One permanent key for every build, so new versions install over old ones.
// Locally it's read from keystore.properties (git-ignored); in CI from env vars
// set from GitHub secrets. Without either, Android's default debug key is used.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val storeFilePath = signingValue("storeFile", "SIGNING_STORE_FILE")
val hasSigningKey = storeFilePath != null && file(storeFilePath).exists()

android {
    namespace = "com.tflwidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tflwidget"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasSigningKey) {
            create("app") {
                storeFile = file(storeFilePath!!)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Debug and release share the key, so Android Studio builds update installed releases
        getByName("debug") {
            if (hasSigningKey) signingConfig = signingConfigs.getByName("app")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasSigningKey) signingConfig = signingConfigs.getByName("app")
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
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.core:core-remoteviews:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
