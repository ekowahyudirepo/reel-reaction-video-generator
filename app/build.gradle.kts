plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versi diisi otomatis oleh CI (GitHub Actions); default untuk build lokal.
val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val appVersionName = System.getenv("VERSION_NAME") ?: "1.0.0"

// Signing opsional: kalau secret keystore tersedia di CI, APK ditandatangani dengan key tetap
// (supaya update bisa menimpa versi lama). Kalau tidak, dipakai debug key.
val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
val hasReleaseKeystore = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

android {
    namespace = "com.ekowahyudi.reelreaction"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ekowahyudi.reelreaction"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // ffmpeg-kit hanya menyediakan build arm64-v8a (hampir semua HP Android modern).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 dimatikan: library native (yt-dlp, ffmpeg-kit) memakai JNI/refleksi.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    }

    packaging {
        jniLibs {
            // Wajib untuk youtubedl-android: binary di-extract ke disk lalu dieksekusi.
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Download: yt-dlp untuk Android (+ ffmpeg bawaan untuk menggabung video & audio).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // Edit video: FFmpegKit (fork yang masih dirawat), varian GPL karena butuh libx264.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl:6.0.3")

    testImplementation("junit:junit:4.13.2")
}
