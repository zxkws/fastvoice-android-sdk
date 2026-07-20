plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zxkws.fastvoice.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zxkws.fastvoice.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = providers.gradleProperty("VERSION_NAME").get()

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    if (providers.gradleProperty("usePublishedSdk").isPresent) {
        implementation(
            "com.github.zxkws:fastvoice-android-sdk:${providers.gradleProperty("VERSION_NAME").get()}",
        )
    } else {
        implementation(project(":fastvoice-sdk"))
    }
}
