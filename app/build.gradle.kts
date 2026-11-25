import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.g41us.movio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.g41us.movio"
        minSdk = 23
        targetSdk = 36
        // Bump version.properties on main to publish a new release.
        val version = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
        versionCode = version.getProperty("versionCode").toInt()
        versionName = version.getProperty("versionName")
        // TMDB key: CI secret TMDB_API_KEY, or tmdb.key in local.properties for local builds.
        val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
        val tmdb = System.getenv("TMDB_API_KEY") ?: local.getProperty("tmdb.key") ?: ""
        buildConfigField("String", "TMDB_KEY", "\"$tmdb\"")
    }

    // One fixed key for every release so updates install over each other.
    // Supplied by CI from repository secrets; never committed.
    val keystorePath = System.getenv("MOVIO_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MOVIO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MOVIO_KEY_ALIAS")
                keyPassword = System.getenv("MOVIO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
