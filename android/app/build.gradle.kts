plugins {
    id("com.android.application")
}

android {
    namespace = "com.jiangshan.atlas"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jiangshan.atlas"
        minSdk = 24
        targetSdk = 37
        // 版本号规则：versionCode 每次发版 +1；versionName 用语义化版本
        // 1.0.0 离线封装 / 1.1.0 电脑端 MCP / 1.2.0 手机内 Agent / 1.3.0 工具栏入口
        versionCode = 4
        versionName = "1.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 签名，方便直接 adb install；正式发布请换成自己的签名
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // AGP 9 内置 Kotlin：kotlinOptions { } 已移除，改用 compilerOptions
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    // assets/www 里有大量 .zim 无关的二进制资源，保持原样打包即可
    androidResources {
        noCompress += listOf("webp", "woff2", "ogg", "mp3")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity-ktx:1.12.0")
}
