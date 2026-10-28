plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lswlight.moneybook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lswlight.moneybook"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 어디서 빌드해도 같은 서명이 되도록 저장소의 키를 쓴다 (업데이트 설치 시 데이터 유지)
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // 실제 안드로이드(에뮬레이터)에서 정규식 등을 검증하는 테스트용. 앱 APK 에는 들어가지 않는다
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
