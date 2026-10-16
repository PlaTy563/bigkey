plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// リリース署名は環境変数（CIではGitHub Secrets）から受け取る。
// 4つのうち1つでも未設定ならデバッグ署名にフォールバックし、常にビルドが通るようにする。
val releaseKeystorePath: String? = System.getenv("KEYSTORE_PATH")
val releaseStorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("KEY_PASSWORD")
val hasReleaseKey: Boolean =
    listOf(releaseKeystorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
        .all { !it.isNullOrBlank() } && File(releaseKeystorePath!!).exists()

android {
    namespace = "com.example.bigkey"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bigkey"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "0.11.0"
    }

    signingConfigs {
        // AGPの自動生成keystoreに任せず、リポジトリ内の固定鍵を使う。
        // CIのビルドごとに署名が変わって上書きインストールできなくなるのを防ぐ。
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
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

    lint {
        // lintの警告でビルドが止まらないようにする
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.autofill:autofill:1.1.0")
}
