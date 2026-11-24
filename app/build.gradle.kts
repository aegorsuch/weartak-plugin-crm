import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application") version "8.7.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    throw GradleException(
        "A verified Windows combined WearTAK SDK AAR is unavailable; use TAK.gov Linux CI.",
    )
}

val sdkPath = providers.gradleProperty("weartakPluginSdkAar").orNull
    ?: throw GradleException("Set -PweartakPluginSdkAar to the verified combined WearTAK SDK AAR.")
val sdkFile = file(sdkPath)
if (!sdkFile.isAbsolute || !sdkFile.isFile) {
    throw GradleException("weartakPluginSdkAar must be an absolute path to an existing file.")
}
val sdkDigest = MessageDigest.getInstance("SHA-256")
sdkFile.inputStream().use { input ->
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        sdkDigest.update(buffer, 0, count)
    }
}
val sdkHash = sdkDigest.digest().joinToString("") { "%02x".format(it) }
val approvedSdkHashes = setOf(
    "49af20aaa08d476cce4c199813c5ebf52b4516c7e499571eb130d81b33eb25ca",
)
if (sdkHash !in approvedSdkHashes) {
    throw GradleException("WearTAK SDK AAR does not match the pinned combined SDK hash.")
}
val hostServicesSdkPath = providers.gradleProperty("weartakHostServicesSdkAar").orNull
    ?: throw GradleException("Set -PweartakHostServicesSdkAar to the verified host-services SDK AAR.")
val hostServicesSdkFile = file(hostServicesSdkPath)
if (!hostServicesSdkFile.isAbsolute || !hostServicesSdkFile.isFile) {
    throw GradleException("weartakHostServicesSdkAar must be an absolute path to an existing file.")
}
val approvedHostServicesSdkEntryHashes = mapOf(
    "AndroidManifest.xml" to "2a76559914cc535daaa2f9f486351ff47f1436f5d2f11c86fc9c2985957d8caf",
    "R.txt" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "classes.jar" to "7aa37dfec4731f343e3d727385608b1645f8ff15541af3eef69cc8bf2a2ba1a3",
    "proguard.txt" to "0834f3b925448c7fca332d58dd50cbf73e30ba0199bfce3dd39a638a88f6b05d",
    "META-INF/com/android/build/gradle/aar-metadata.properties" to
        "9cc8517bbdf06d879f57a2cfd6f8c6914e48800d443421cd850971945f98e7b2",
)
ZipFile(hostServicesSdkFile).use { archive ->
    val actualEntries = archive.entries().asSequence()
        .filterNot { it.isDirectory }
        .associate { entry ->
            val digest = MessageDigest.getInstance("SHA-256")
            archive.getInputStream(entry).use { input ->
                if (entry.name == "AndroidManifest.xml" || entry.name == "proguard.txt") {
                    val canonicalText = input.readBytes().toString(Charsets.UTF_8)
                        .replace("\r\n", "\n")
                        .replace("\r", "\n")
                    digest.update(canonicalText.toByteArray(Charsets.UTF_8))
                } else {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
            entry.name to digest.digest().joinToString("") { "%02x".format(it) }
        }
    if (actualEntries != approvedHostServicesSdkEntryHashes) {
        throw GradleException("Host-services SDK AAR does not match the approved Core contract.")
    }
}

android {
    namespace = "com.weartak.plugin.crm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weartak.plugin.crm"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        noCompress += "tflite"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            if (gradle.extra.has("takArtifactoryUrl")) {
                test.systemProperty("robolectric.dependency.repo.id", "takArtifactory")
                test.systemProperty("robolectric.dependency.repo.url", gradle.extra["takArtifactoryUrl"]!!)
                test.systemProperty("robolectric.dependency.repo.username", gradle.extra["takArtifactoryUser"]!!)
                test.systemProperty("robolectric.dependency.repo.password", gradle.extra["takArtifactoryPassword"]!!)
            }
        }
    }
}

apply(from = rootProject.file("gradle/weartak-sideload-debug.gradle"))
apply(from = rootProject.file("gradle/weartak-sideload-production.gradle"))

dependencies {
    implementation(files(sdkFile))
    compileOnly(files(hostServicesSdkFile))
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
}
