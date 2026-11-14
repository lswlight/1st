plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lswlight.moneybook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lswlight.moneybook"
        minSdk = 26
        targetSdk = 36
        versionCode = 34
        versionName = "3.0"
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
        // 플레이스토어 업로드용 키. 저장소에 두지 않고 GitHub 비밀값(UPLOAD_KEYSTORE_*)으로만 받는다
        val uploadStore = System.getenv("UPLOAD_KEYSTORE_FILE")
        if (!uploadStore.isNullOrBlank()) {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 플레이스토어 버전(release)은 회사 패키지 이름으로 낸다.
// 직접 설치하는 APK(debug)는 지금 쓰는 사람들이 데이터 그대로 업데이트하도록 예전 이름을 유지한다.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.applicationId.set("com.graftkorea.moneybook")
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
    androidTestImplementation("androidx.test:core:1.6.1")
}
