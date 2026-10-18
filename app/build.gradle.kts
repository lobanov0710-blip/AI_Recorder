import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
}

abstract class GenerateAiDebugNetworkConfig : DefaultTask() {
    @get:Input
    abstract val serverHost: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val xml = outputDirectory.file("xml/ai_debug_network_security.xml").get().asFile
        xml.parentFile.mkdirs()
        xml.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">${serverHost.get()}</domain>
    </domain-config>
</network-security-config>
""",
            Charsets.UTF_8
        )
    }
}

// A project property is tracked by the configuration cache; no LAN address is committed.
val aiDebugServerHost = providers.gradleProperty("aiServerHost").orElse("10.0.2.2").get()
val aiHostOctets = aiDebugServerHost.split('.').map { part ->
    require(part.matches(Regex("0|[1-9][0-9]{0,2}"))) {
        "aiServerHost must be a private IPv4 address without a scheme, port or path"
    }
    part.toInt().also { require(it in 0..255) { "Invalid aiServerHost IPv4 octet" } }
}
require(aiHostOctets.size == 4 && (aiHostOctets[0] == 10 ||
        (aiHostOctets[0] == 172 && aiHostOctets[1] in 16..31) ||
        (aiHostOctets[0] == 192 && aiHostOctets[1] == 168))) {
    "aiServerHost must belong to 10/8, 172.16/12 or 192.168/16"
}
val aiLanDebug = aiDebugServerHost != "10.0.2.2"

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
            buildConfigField("String", "AI_SERVER_BASE_URL", "\"http://$aiDebugServerHost:8000/\"")
            buildConfigField("boolean", "AI_ALLOW_LAN_HTTP", aiLanDebug.toString())
        }
        release {
            buildConfigField("String", "AI_SERVER_BASE_URL", "\"\"")
            buildConfigField("boolean", "AI_ALLOW_LAN_HTTP", "false")
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

if (aiLanDebug) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        val configTask = tasks.register<GenerateAiDebugNetworkConfig>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}AiNetworkConfig"
        ) {
            serverHost.set(aiDebugServerHost)
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            configTask, GenerateAiDebugNetworkConfig::outputDirectory
        )
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
