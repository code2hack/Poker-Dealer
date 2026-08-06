import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val embeddedTailnetAar = rootProject.layout.projectDirectory.file(
    "native/embedded-tailnet/build/embeddedtailnet.aar",
)
val buildEmbeddedTailnet by tasks.registering(Exec::class) {
    inputs.files(
        rootProject.fileTree("native/embedded-tailnet") {
            exclude("build/**", ".toolchains/**")
        },
    )
    outputs.file(embeddedTailnetAar)
    commandLine(rootProject.file("native/embedded-tailnet/build.sh"))
}
val sherpaOnnxAar = rootProject.layout.projectDirectory.file(
    "native/sherpa-onnx/build/sherpa-onnx.aar",
)
val buildSherpaOnnx by tasks.registering(Exec::class) {
    inputs.files(
        rootProject.fileTree("native/sherpa-onnx") {
            exclude("build/**", ".toolchains/**")
        },
    )
    outputs.file(sherpaOnnxAar)
    commandLine(rootProject.file("native/sherpa-onnx/build.sh"))
}
val sherpaSmokeAssets = layout.buildDirectory.dir("generated/sherpa-smoke-assets")
val prepareSherpaSmokeFixture by tasks.registering(Exec::class) {
    inputs.files(
        rootProject.fileTree("native/sherpa-onnx") {
            exclude("build/**", ".toolchains/**")
        },
    )
    outputs.dir(sherpaSmokeAssets)
    commandLine(
        rootProject.file("native/sherpa-onnx/prepare-smoke-fixture.sh"),
        sherpaSmokeAssets.get().asFile,
    )
}

android {
    namespace = "com.code2hack.dealer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.code2hack.dealer"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(files(embeddedTailnetAar).builtBy(buildEmbeddedTailnet))
    implementation(files(sherpaOnnxAar).builtBy(buildSherpaOnnx))
    implementation(project(":shared:domain"))
    implementation(project(":shared:protocol"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    annotationProcessor(libs.room.compiler)
}

tasks.named("preBuild").configure {
    dependsOn(buildEmbeddedTailnet, buildSherpaOnnx)
}

android.sourceSets.getByName("androidTest").assets.directories.add(
    sherpaSmokeAssets.get().asFile.absolutePath,
)
tasks.configureEach {
    if (name.contains("AndroidTest")) {
        dependsOn(prepareSherpaSmokeFixture)
    }
}

tasks.register("verifyEmbeddedTailnetPackaging") {
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/dealer-debug.apk").get().asFile
        val manifest = layout.buildDirectory.file(
            "intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        ).get().asFile.readText()
        check(
            ZipFile(apk).use { zip ->
                zip.getEntry("lib/arm64-v8a/libgojni.so") != null
            },
        ) { "Dealer debug APK is missing lib/arm64-v8a/libgojni.so" }
        check("android.net.VpnService" !in manifest && "android.permission.BIND_VPN_SERVICE" !in manifest) {
            "Dealer must not package Android VpnService"
        }
    }
}

tasks.register("verifySherpaOnnxPackaging") {
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/dealer-debug.apk").get().asFile
        val modelSuffixes = listOf(".onnx", ".ort", ".model", "/tokens.txt")
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            check("lib/arm64-v8a/libsherpa-onnx-jni.so" in entries) {
                "Dealer debug APK is missing lib/arm64-v8a/libsherpa-onnx-jni.so"
            }
            check("lib/arm64-v8a/libonnxruntime.so" in entries) {
                "Dealer debug APK is missing lib/arm64-v8a/libonnxruntime.so"
            }
            check(entries.none { name ->
                modelSuffixes.any(name::endsWith) ||
                    (name.startsWith("assets/") && name.endsWith(".bin"))
            }) {
                "Dealer production APK must not contain an ASR model pack"
            }
            check(entries.filter { it.startsWith("lib/") }.all { it.startsWith("lib/arm64-v8a/") }) {
                "Dealer debug APK contains a non-ARM64 native library"
            }
        }
    }
}

tasks.register("verifySherpaOnnxInstrumentationPackaging") {
    dependsOn("assembleDebugAndroidTest")
    doLast {
        val apk = layout.buildDirectory.file(
            "outputs/apk/androidTest/debug/dealer-debug-androidTest.apk",
        ).get().asFile
        ZipFile(apk).use { zip ->
            val modelEntries = zip.entries().asSequence().filter { entry ->
                entry.name.startsWith("assets/") &&
                    (entry.name.endsWith(".onnx") || entry.name.endsWith("/tokens.txt"))
            }.toList()
            check(modelEntries.isNotEmpty()) {
                "Dealer instrumentation APK is missing the tiny ASR fixture"
            }
            check(modelEntries.sumOf { it.size } < 64L * 1024 * 1024) {
                "Dealer instrumentation APK contains a model fixture larger than 64 MiB"
            }
            check(modelEntries.none { it.name.contains("0.6b", ignoreCase = true) }) {
                "Dealer instrumentation APK must not contain the production Parakeet pack"
            }
        }
    }
}
