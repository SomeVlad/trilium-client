import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// An explicit demo build also ignores local files, so CI artifacts cannot use personal credentials.
val demoBuild = providers.gradleProperty("demoBuild").map {
    it.toBooleanStrictOrNull()
        ?: throw GradleException("demoBuild must be true or false")
}.getOrElse(false)
val privatePropertiesFile = rootProject.file("private.properties")
val usePrivateConfiguration = !demoBuild && privatePropertiesFile.isFile
val privateProperties = Properties().apply {
    if (usePrivateConfiguration) {
        privatePropertiesFile.inputStream().use { load(it) }
    }
}
val demoProperties = mapOf(
    "trilium.baseUrl" to "https://trilium.invalid",
    "trilium.token" to "dummy-token",
)

val signingPropertiesFile = rootProject.file("signing.properties")
val hasReleaseSigning = !demoBuild && signingPropertiesFile.isFile
val signingProperties = Properties().apply {
    if (hasReleaseSigning) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}

fun privateValue(name: String): String =
    if (usePrivateConfiguration) {
        privateProperties.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Missing $name in private.properties")
    } else {
        demoProperties.getValue(name)
    }

fun signingValue(name: String): String =
    signingProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Missing $name in signing.properties")

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") + "\""

android {
    namespace = "ru.vlad.triliumnotes"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.vlad.triliumnotes"
        minSdk = 28
        targetSdk = 36
        versionCode = 13
        versionName = "0.3.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TRILIUM_BASE_URL", privateValue("trilium.baseUrl").asBuildConfigString())
        buildConfigField("String", "TRILIUM_TOKEN", privateValue("trilium.token").asBuildConfigString())
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
    implementation("org.jsoup:jsoup:1.21.2")
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
