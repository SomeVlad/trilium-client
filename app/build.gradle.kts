import java.util.Properties
import java.util.zip.ZipInputStream
import java.security.MessageDigest
import groovy.json.JsonOutput
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseRuntime = providers.provider { configurations.getByName("releaseRuntimeClasspath") }
val dependencyManifest = layout.buildDirectory.file("release-metadata/runtime-dependencies.json")
tasks.register("writeReleaseDependencyManifest") {
    outputs.file(dependencyManifest)
    outputs.upToDateWhen { false }
    doLast {
        val commit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
        val dependencies = releaseRuntime.get().resolvedConfiguration.resolvedArtifacts.map {
            val id = it.moduleVersion.id
            mapOf("group" to id.group, "name" to id.name, "version" to id.version, "artifact" to it.file.name)
        }.distinct().sortedBy { "${it["group"]}:${it["name"]}" }
        val target = dependencyManifest.get().asFile
        target.parentFile.mkdirs()
        target.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
            "schemaVersion" to 1, "configuration" to "releaseRuntimeClasspath",
            "gitCommit" to commit, "versionName" to android.defaultConfig.versionName,
            "dependencies" to dependencies,
        ))))
    }
}

val legalAssets = layout.buildDirectory.dir("generated/legalAssets")
val generateLegalAssets = tasks.register("generateLegalAssets") {
    inputs.files(rootProject.files("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"))
    inputs.files(fileTree("src/main/assets/editor/vendor") { include("*LICENSE*", "NOTICE.txt", "COPYING.GPL") })
    inputs.files(fileTree("src/main/assets/diagrams/vendor") { include("*LICENSE*", "*NOTICE*") })
    inputs.files(releaseRuntime)
    outputs.dir(legalAssets)
    doLast {
        val directory = legalAssets.get().dir("licenses").asFile
        directory.deleteRecursively()
        directory.mkdirs()
        val index = mutableListOf<String>()
        for ((source, title) in mapOf("NOTICE" to "Авторские права и условия", "LICENSE" to "GNU GPL 3.0", "THIRD_PARTY_NOTICES.md" to "Сторонние компоненты")) {
            rootProject.file(source).copyTo(directory.resolve("$source.txt"), overwrite = true)
            index += "licenses/$source.txt\t$title"
        }
        val noticeCounts = mutableMapOf<String, Int>()
        val seenNotices = mutableSetOf<Pair<String, String>>()
        for (artifact in releaseRuntime.get().resolvedConfiguration.resolvedArtifacts.sortedBy { "${it.moduleVersion.id}:${it.file.name}" }) {
            val id = artifact.moduleVersion.id.toString()
            fun extract(input: java.io.InputStream, nested: Boolean = false) {
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.substringAfterLast('/').lowercase()
                        if (!entry.isDirectory && (name.startsWith("license") || name.startsWith("notice") || name.startsWith("copying"))) {
                            val bytes = zip.readBytes()
                            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                            if (seenNotices.add(id to digest)) {
                                val count = noticeCounts.getOrDefault(id, 0) + 1
                                noticeCounts[id] = count
                                val file = id.replace(':', '_') + "-$count.txt"
                                directory.resolve(file).writeBytes(bytes)
                                index += "licenses/$file\t$id — ${entry.name.substringAfterLast('/')}"
                            }
                        } else if (!nested && entry.name == "classes.jar") {
                            extract(zip.readBytes().inputStream(), nested = true)
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            artifact.file.inputStream().use { extract(it) }
        }
        index += "editor/vendor/NOTICE.txt\tCKEditor — происхождение и изменения"
        index += "editor/vendor/LICENSE.md\tCKEditor — лицензии компонентов"
        index += "editor/vendor/COPYING.GPL\tCKEditor — GNU GPL"
        if (file("src/main/assets/editor/vendor/THIRD_PARTY_LICENSES.txt").isFile)
            index += "editor/vendor/THIRD_PARTY_LICENSES.txt\tCKEditor — уведомления зависимостей"
        index += "diagrams/vendor/THIRD_PARTY_NOTICES.txt\tOffline diagrams — dependency notices"
        index += "diagrams/vendor/UPSTREAM_LICENSES.txt\tExcalidraw and local fonts — upstream licenses"
        directory.resolve("index.txt").writeText(index.joinToString("\n"))
    }
}
android.sourceSets["main"].assets.srcDir(legalAssets)
tasks.named("preBuild").configure { dependsOn(generateLegalAssets) }

// Kept for existing CI commands: demoBuild now only disables local release signing.
// Server addresses and credentials are always entered on the device.
val demoBuild = providers.gradleProperty("demoBuild").map {
    it.toBooleanStrictOrNull() ?: throw GradleException("demoBuild must be true or false")
}.getOrElse(false)
val signingPropertiesFile = rootProject.file("signing.properties")
val signingEnvironment = mapOf(
    "storeFile" to providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull,
    "storePassword" to providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull,
    "keyAlias" to providers.environmentVariable("ANDROID_KEY_ALIAS").orNull,
    "keyPassword" to providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull,
)
val useEnvironmentSigning = !demoBuild && signingEnvironment.values.any { it != null }
if (useEnvironmentSigning && signingEnvironment.values.any { it.isNullOrBlank() }) {
    throw GradleException("Release signing requires ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD")
}
val hasReleaseSigning = !demoBuild && (useEnvironmentSigning || signingPropertiesFile.isFile)
val signingProperties = Properties().apply {
    if (hasReleaseSigning && !useEnvironmentSigning) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(name: String): String =
    (if (useEnvironmentSigning) signingEnvironment[name] else signingProperties.getProperty(name))
        ?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Missing $name in signing.properties")

android {
    namespace = "ru.vlad.triliumnotes"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.vlad.triliumnotes"
        minSdk = 28
        targetSdk = 36
        versionCode = 22
        versionName = "0.6.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signingValue("storeFile"))
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("org.commonmark:commonmark:0.30.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.11.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
