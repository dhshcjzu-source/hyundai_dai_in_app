import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // SK open API(TMAP) 앱 키는 git에 올리지 않는 local.properties의 tmap.appKey에서 읽는다.
        val localProperties = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "TMAP_APP_KEY", "\"${localProperties.getProperty("tmap.appKey", "")}\"")
        // 공공데이터포털 한국환경공단 전기자동차 충전소 정보 서비스 키
        buildConfigField("String", "DATA_GO_KR_KEY", "\"${localProperties.getProperty("datagokr.serviceKey", "")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.fused.location)
    testImplementation(libs.junit)
    // 단위 테스트에서 실제 JSON 파서를 쓴다. (안드로이드 기본 org.json은 JVM 테스트에서 동작하지 않는다)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}