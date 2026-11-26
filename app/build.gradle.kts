import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 업로드 키스토어(Play App Signing) — 저장소 밖(projects/keystores)에 두고 keystore.properties 로만 읽는다.
// 파일이 없으면 release 빌드는 서명 없이 만들어지고(로컬 배포용 아님), 저장소에는 아무 키도 들어가지 않는다.
val keystorePropertiesFile: File? = listOf(
    rootProject.file("../keystores/keystore.properties"),
    rootProject.file("keystore.properties")
).firstOrNull { it.exists() }
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile != null) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.youngsu.slimvideo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.youngsu.slimvideo"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        // AdMob 앱 ID — 기본(debug)은 Google 공식 테스트 ID, release 는 buildTypes 에서 실제 ID 로 바꾼다.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"

        // 광고 단위 ID — debug 는 Google 공식 테스트 ID. release 는 buildTypes 에서 실제 ID 로 바꾼다.
        // 코드 상수가 아니라 BuildConfig 로 넣어야 release dex 에 테스트 ID 문자열이 남지 않는다.
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_UNIT_ID", "\"ca-app-pub-3940256099942544/1033173712\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile != null) {
            create("release") {
                val storeFileName = keystoreProperties.getProperty("storeFile")
                val candidates = listOf(
                    rootProject.file(storeFileName),
                    keystorePropertiesFile.parentFile.resolve(storeFileName)
                )
                storeFile = candidates.firstOrNull { it.exists() }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Play 스토어 업로드용 서명 — keystore.properties 가 있을 때만 붙는다.
            signingConfigs.findByName("release")?.let { signingConfig = it }

            // release 빌드는 실제 AdMob ID (슬림 비디오) 사용
            manifestPlaceholders["admobAppId"] = "ca-app-pub-4832055633016363~6566300855"
            buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"ca-app-pub-4832055633016363/8566738692\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_UNIT_ID", "\"ca-app-pub-4832055633016363/4299743512\"")
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.play.services.ads)
    implementation(libs.guava)
}
