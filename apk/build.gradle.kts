import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// 与宿主共用同一套本地签名属性或环境变量，不提交私钥与口令。
val releaseStoreFile = providers.gradleProperty("lifehub.release.storeFile").orNull
    ?: System.getenv("LIFEHUB_RELEASE_STORE_FILE")
val releaseStorePassword = providers.gradleProperty("lifehub.release.storePassword").orNull
    ?: System.getenv("LIFEHUB_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.gradleProperty("lifehub.release.keyAlias").orNull
    ?: System.getenv("LIFEHUB_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.gradleProperty("lifehub.release.keyPassword").orNull
    ?: System.getenv("LIFEHUB_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.lifehub.plugin.memo.apk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lifehub.plugin.memo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":plugins:memo"))
    implementation(project(":plugin-api"))
    implementation(project(":plugin-runtime"))
    implementation(libs.kotlinx.serialization.json)
}
