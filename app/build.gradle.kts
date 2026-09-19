import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Google Maps Platform key, read from the gitignored local.properties (never hardcoded /
// committed) and exposed both to the manifest (Maps SDK for Android reads it from there) and
// to Kotlin code (BuildConfig, for the Places/Directions REST calls).
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(FileInputStream(file))
}
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY") ?: ""
val openAiApiKey: String = localProperties.getProperty("OPENAI_API_KEY") ?: ""

android {
    namespace = "com.rakshika.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rakshika.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-demo"

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("com.google.android.gms:play-services-maps:19.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
