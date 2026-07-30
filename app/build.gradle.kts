plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

android {
    namespace = "ru.srr.safari"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "ru.srr.safari"
        minSdk = 26
        targetSdk = 35

        val appVersionName = "1.2.9"
        versionCode = 27
        versionName = appVersionName
        resValue("string", "app_name", "Safari")
        resValue("string", "app_version", appVersionName)

        // Авторство (видно в BuildConfig / APK)
        buildConfigField("String", "AUTHOR", "\"СпустяРуковаРекордс\"")
        buildConfigField("String", "AUTHOR_ID", "\"srr-safari-2026\"")
        buildConfigField("String", "AUTHOR_MARK", "\"© СпустяРуковаРекордс\"")
        buildConfigField(
            "String",
            "SIGNING_SHA256",
            "\"DF:39:AC:CA:E8:5C:8F:73:48:44:0B:2B:84:3C:19:9D:27:F0:1A:A8:F8:11:29:22:52:67:65:AF:6C:E4:16:50\""
        )
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    }

    signingConfigs {
        getByName("debug") {
            val debugStore = rootProject.file("keystore/debug.keystore")
            if (debugStore.exists()) {
                storeFile = debugStore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

android.applicationVariants.configureEach {
    val vName = versionName
    outputs.configureEach {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
            .outputFileName = "safari-${vName}.apk"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
