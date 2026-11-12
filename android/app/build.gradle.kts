import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// sherpa-onnx (Apache-2.0) runs Parakeet on the phone. Its AAR is 49 MB, so
// it's fetched from the upstream release instead of living in git.
val sherpaVersion = "2.0.1"
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256")
    .digest(f.readBytes()).joinToString("") { "%02x".format(it) }

if (!sherpaAar.exists() || sha256(sherpaAar) != sherpaSha256) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Downloading $url")
    URI(url).toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
    check(sha256(sherpaAar) == sherpaSha256) { "sherpa-onnx AAR checksum mismatch" }
}

// Release signing and this install's defaults live outside the repo, in ~/.config/uplink/:
//   keystore.properties  storeFile/storePassword/keyAlias/keyPassword
//   android.properties   hub=http://... and squelch_hub=https://..., baked in as first-run defaults
fun props(name: String) = Properties().apply {
    val file = file(System.getProperty("user.home") + "/.config/uplink/" + name)
    if (file.exists()) file.inputStream().use { load(it) }
}
val keystoreProps = props("keystore.properties")
val installProps = props("android.properties")

val commitCount: Int = runCatching {
    val git = ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(rootDir).redirectErrorStream(true).start()
    git.inputStream.bufferedReader().readText().trim().toInt().takeIf { git.waitFor() == 0 }
}.getOrNull() ?: 1

// The page is ../web, shared with the hub; it's copied into the APK's assets at build time.
val webAssets = layout.buildDirectory.dir("generated/webassets")
val copyWeb = tasks.register<Sync>("copyWeb") {
    from(rootProject.file("../web"))
    into(webAssets.map { it.dir("web") })
}
tasks.named("preBuild") { dependsOn(copyWeb) }

android {
    namespace = "ca.heeney.uplink"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.heeney.uplink"
        minSdk = 31
        targetSdk = 35
        versionCode = commitCount
        versionName = "${project.property("uplinkVersion")}.$commitCount"
        // -Phub=http://10.0.2.2:4698 points a test build at a test hub
        buildConfigField("String", "HUB_URL", "\"${project.findProperty("hub") ?: installProps.getProperty("hub", "")}\"")
        buildConfigField("String", "SQUELCH_HUB", "\"${installProps.getProperty("squelch_hub", "")}\"")
        ndk {
            abiFilters += "arm64-v8a"
            // -Pemu for testing on an x86_64 emulator
            if (project.hasProperty("emu")) abiFilters += "x86_64"
        }
    }

    sourceSets["main"].assets.srcDir(webAssets)

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val key = signingConfigs.findByName("release")
        release {
            isMinifyEnabled = false
            signingConfig = key ?: signingConfigs.getByName("debug")
        }
        debug {
            if (key != null) signingConfig = key
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation(files(sherpaAar))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.webkit:webkit:1.11.0")
    // play-services drags in Fragment 1.0, which breaks the activity-result API
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.work:work-runtime:2.9.1")
    // Geofences for place reminders (Google Play services, which the phone has).
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
