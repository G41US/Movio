import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.webkit:webkit:1.14.0")
}
