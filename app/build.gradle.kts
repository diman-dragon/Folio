plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Проверьте актуальную версию: https://maven.ghostscript.com/com/artifex/mupdf/fitz/
val mupdfVersion = "1.25.2"

// Подпись релиза: если заданы переменные окружения (в CI из секретов), используется ваш keystore, иначе — debug-ключ.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "app.folio"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.folio"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    signingConfigs {
        if (keystorePath != null && File(keystorePath).exists()) {
            create("release") {
                storeFile = File(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            // R8 выключен намеренно: надёжная сборка важнее размера (MuPDF/junrar тянут много рефлексии).
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            buildConfigField("String", "SOURCE_URL", "\"https://github.com/alexandrufolea/folio\"")
        }
        debug {
            buildConfigField("String", "SOURCE_URL", "\"https://github.com/alexandrufolea/folio\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true // генерировать app.folio.BuildConfig (нужен SettingsDialog.kt)
    }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("com.artifex.mupdf:fitz:$mupdfVersion")   // AGPL-3.0
    implementation("com.github.junrar:junrar:7.5.5")          // CBR (unrar license, open source)

    testImplementation("junit:junit:4.13.2")
}
