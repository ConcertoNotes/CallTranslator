plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.calltranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.calltranslator"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
        ndk {
            // 只保留手机用的 CPU 架构，减小安装包
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 固定签名：以后每次更新都能直接覆盖安装，不用卸载旧版
    signingConfigs {
        create("lulu") {
            storeFile = file("luluvoice.p12")
            storeType = "pkcs12"
            storePassword = "e3ea4982af9edff5fbd8cac9"
            keyAlias = "lulu"
            keyPassword = "e3ea4982af9edff5fbd8cac9"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lulu")
        }
        debug {
            signingConfig = signingConfigs.getByName("lulu")
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
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Google ML Kit 离线翻译：免费、在手机本地运行
    implementation("com.google.mlkit:translate:17.0.3")
    // Vosk 离线语音识别：App 自己录音识别，不依赖 Google 或系统语音服务
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
