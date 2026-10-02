import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.niguangowo.applistblocker"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.niguangowo.applistblocker"
        minSdk = 35
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            merges += listOf("META-INF/xposed/*")
            excludes += listOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
        dex {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            // 关闭 R8 会让 Compose / Miuix 全家桶原样打进 APK（实测 20.95 MB）。
            // 开启后配合 app/proguard-rules.pro 的 keep 规则，实测 1.76 MB。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)

    implementation(libs.androidx.annotation)

    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix.ui)
}
