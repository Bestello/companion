import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tools.fmc.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "tools.fmc.companion"
        // Android 12: BLUETOOTH_SCAN/CONNECT without the location permission.
        minSdk = 31
        targetSdk = 36
        // CI passes -PversionCode/-PversionName (.github/workflows/android-release.yml): every release counts up
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "0.1.0"
    }

    // The release key, outside the repo: android/keystore.properties (file, storePassword, keyAlias, keyPassword).
    // Every update must be signed with it; lose it and users reinstall.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    keys?.let { k ->
        signingConfigs.create("release") {
            storeFile = file(k.getProperty("file"))
            storePassword = k.getProperty("storePassword")
            keyAlias = k.getProperty("keyAlias")
            keyPassword = k.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            keys?.let { signingConfig = signingConfigs.getByName("release") }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions.unitTests.all {
        // The protocol vectors are shared with iOS, read in place: a changed vector fails both apps.
        it.systemProperty("vectors", rootDir.resolve("../ios/WireKit/Tests/WireKitTests").path)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.markdown.m3)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    // android.jar's org.json is stubs on the JVM
    testImplementation(libs.json)
}
