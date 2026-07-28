import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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
    implementation(project(":shared:domain"))
    implementation(project(":shared:protocol"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.named("preBuild").configure {
    dependsOn(buildEmbeddedTailnet)
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
