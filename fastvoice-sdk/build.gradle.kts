plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

group = "io.github.zxkws"
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "com.zxkws.fastvoice"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        targetSdk = 35

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Local JAR dependencies are embedded under libs/ in the produced AAR.
    implementation(files("libs/sherpa-onnx-1.13.2-classes.jar"))
    implementation(libs.okhttp)
    implementation(libs.concentus)

    testImplementation(libs.junit)
}

// Signing is enabled only when a key is supplied. This keeps local Maven
// verification usable while guaranteeing that Central CI releases are signed.
val hasSigningKey =
    providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.secretKeyRingFile").isPresent

mavenPublishing {
    coordinates(
        groupId = "io.github.zxkws",
        artifactId = "fastvoice-android-sdk",
        version = project.version.toString(),
    )

    publishToMavenCentral(automaticRelease = true)
    if (hasSigningKey) {
        signAllPublications()
    }

    pom {
        name.set("FastVoice Android SDK")
        description.set("Low-friction Android client SDK for the FastVoice voice assistant service.")
        inceptionYear.set("2026")
        url.set("https://github.com/zxkws/fastvoice-android-sdk")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("zxkws")
                name.set("zxkws")
                url.set("https://github.com/zxkws")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/zxkws/fastvoice-android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/zxkws/fastvoice-android-sdk.git")
            url.set("https://github.com/zxkws/fastvoice-android-sdk")
        }
    }
}
