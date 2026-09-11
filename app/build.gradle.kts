plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


android {
    namespace = "com.sc2tmg.soundboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sc2tmg.soundboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 108
        versionName = "1.0.0"
        // Stable public Drive JSON manifest. Make this file public before the V1 APK is published.
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://drive.google.com/uc?export=download&id=1BWnDNDpEcA0hFMs9oPbjpa1iHBGKi1us\"")
    }

    // Compile both Java and Kotlin bytecode to Java 17 without requesting a separate
    // JDK 17 toolchain. Android Studio can run Gradle on its Embedded JDK (17/21);
    // javac/Kotlin will still emit JVM 17 bytecode, avoiding both the 1.8-vs-21
    // mismatch and the "No locally installed toolchains match languageVersion=17" error.
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

    androidResources {
        noCompress += listOf("ogg", "wav", "mp3")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
