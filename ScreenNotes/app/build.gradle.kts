plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Build info baked into the app (GitHub Actions fills it in) so the Updates tab knows which version
// is installed. Local builds just show "local".
fun buildEnv(name: String, default: String = ""): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }?.replace("\\", "")?.replace("\"", "") ?: default

android {
    namespace = "com.example.screennotes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screennotes"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2"

        buildConfigField("String", "GIT_SHA", "\"${buildEnv("APP_GIT_SHA")}\"")
        buildConfigField("String", "BUILD_LABEL", "\"${buildEnv("APP_BUILD_LABEL", "local")}\"")
        buildConfigField("long", "BUILD_TIME", "${buildEnv("APP_BUILD_TIME", "0").toLongOrNull() ?: 0}L")
        // The GitHub repository the Updates tab talks to (can be changed in the app).
        buildConfigField(
            "String", "DEFAULT_REPO",
            "\"${buildEnv("GITHUB_REPOSITORY", "rough12345678987654321-afk/Screen-notes-king")}\""
        )
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // History database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Free on-device text recognition (no internet, no cost)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Show saved screenshots
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Updates tab: icons, lifecycle-aware refresh, and background checks for AI progress (notifications)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
