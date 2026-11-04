import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
}

abstract class GenerateAiDebugNetworkConfig : DefaultTask() {
    @get:Input
    abstract val serverHost: Property<String>

    @get:Input
    abstract val httpsMode: Property<Boolean>

    @get:InputFile
    @get:Optional
    abstract val customCaFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val host = serverHost.get()
        val https = httpsMode.get()
        val caFile = if (https && customCaFile.isPresent) customCaFile.get().asFile else null
        val customTrust = if (caFile != null) """
        <trust-anchors>
            <certificates src="system" />
            <certificates src="@raw/ai_stage47_debug_ca" />
        </trust-anchors>""" else ""
        val xml = outputDirectory.file("xml/ai_debug_network_security.xml").get().asFile
        xml.parentFile.mkdirs()
        val content = if (https) """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="false">$host</domain>$customTrust
    </domain-config>
</network-security-config>
""" else """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">$host</domain>
    </domain-config>
</network-security-config>
"""
        xml.writeText(content, Charsets.UTF_8)
        if (caFile != null) {
            val caPem = caFile.readText(Charsets.US_ASCII).trim()
            require(caPem.startsWith("-----BEGIN CERTIFICATE-----") &&
                    caPem.endsWith("-----END CERTIFICATE-----") &&
                    !caPem.contains("PRIVATE KEY") &&
                    caPem.length in 256..16384) {
                "aiDebugCaPemFile must contain only PEM certificates, never a private key"
            }
            val caResource = outputDirectory.file("raw/ai_stage47_debug_ca.pem").get().asFile
            caResource.parentFile.mkdirs()
            caResource.writeText(caPem + "\n", Charsets.US_ASCII)
        }
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
// HTTPS is an explicit debug-only opt-in; existing emulator/LAN HTTP builds are unchanged.
val aiDebugScheme = providers.gradleProperty("aiServerScheme").orElse("http").get()
require(aiDebugScheme == "http" || aiDebugScheme == "https") {
    "aiServerScheme must be http or https"
}
val aiDebugPortString = providers.gradleProperty("aiServerPort")
    .orElse(if (aiDebugScheme == "https") "18443" else "8000").get()
val aiDebugPort = aiDebugPortString.toIntOrNull()
require(aiDebugPort != null && aiDebugPort in 1..65535) {
    "aiServerPort must be 1..65535"
}
val aiCustomCaPath = providers.gradleProperty("aiDebugCaPemFile").orNull
require(aiCustomCaPath == null || aiDebugScheme == "https") {
    "Custom debug CA is allowed only in HTTPS mode"
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
            buildConfigField("String", "AI_SERVER_BASE_URL", "\"$aiDebugScheme://$aiDebugServerHost:$aiDebugPort/\"")
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

if (aiLanDebug || aiDebugScheme == "https") {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        val configTask = tasks.register<GenerateAiDebugNetworkConfig>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}AiNetworkConfig"
        ) {
            serverHost.set(aiDebugServerHost)
            httpsMode.set(aiDebugScheme == "https")
            if (aiCustomCaPath != null) {
                val ca = file(aiCustomCaPath)
                require(ca.isFile && ca.canonicalFile.parentFile != null) {
                    "aiDebugCaPemFile must refer to an existing public CA PEM file"
                }
                require(!ca.canonicalFile.toPath().startsWith(rootProject.rootDir.canonicalFile.toPath())) {
                    "Debug CA PEM must be kept outside the Git repository"
                }
                customCaFile.set(ca)
            }
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
