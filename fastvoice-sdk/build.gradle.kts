import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "com.github.zxkws"
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "com.zxkws.fastvoice"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        ndk {
            abiFilters += "arm64-v8a"
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.github.zxkws"
                artifactId = "fastvoice-android-sdk"
                version = project.version.toString()
                from(components["release"])

                pom {
                    name = "FastVoice Android SDK"
                    description = "Low-friction Android client SDK for the FastVoice voice assistant service."
                    url = "https://github.com/zxkws/fastvoice-android-sdk"
                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                            distribution = "repo"
                        }
                    }
                    developers {
                        developer {
                            id = "zxkws"
                            name = "zxkws"
                        }
                    }
                    scm {
                        connection = "scm:git:git://github.com/zxkws/fastvoice-android-sdk.git"
                        developerConnection = "scm:git:ssh://github.com/zxkws/fastvoice-android-sdk.git"
                        url = "https://github.com/zxkws/fastvoice-android-sdk"
                    }
                }
            }
        }
    }
}
