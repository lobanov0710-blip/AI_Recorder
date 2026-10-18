plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nicko.airecorder"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.nicko.airecorder"

        minSdk = 24
        targetSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.schemaLocation" to "$projectDir/schemas"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "AI_SERVER_BASE_URL", "\"http://10.0.2.2:8000/\"")
        }
        release {
            buildConfigField("String", "AI_SERVER_BASE_URL", "\"\"")
            optimization {
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.gson)
    testImplementation(libs.mockwebserver)

    implementation(libs.appcompat)

    implementation(libs.core)

    implementation(libs.material)

    implementation(libs.recyclerview)

    implementation(libs.room.runtime)

    implementation(libs.work.runtime)

    implementation(libs.core.splashscreen)

    annotationProcessor(
        libs.room.compiler
    )

    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        libs.espresso.core
    )

    androidTestImplementation(
        libs.ext.junit
    )

    androidTestImplementation(
        libs.room.testing
    )

    androidTestImplementation(libs.work.testing)
}
