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
    }

    buildTypes {
        release {
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

    implementation(libs.appcompat)

    implementation(libs.material)

    implementation(libs.recyclerview)

    implementation(libs.room.runtime)

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
}