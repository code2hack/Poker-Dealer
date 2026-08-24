import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
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

android {
    namespace = "com.code2hack.dealer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.code2hack.dealer"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-extraction"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(files(embeddedTailnetAar).builtBy(buildEmbeddedTailnet))
    implementation(project(":shared:domain"))
    implementation(project(":shared:protocol"))
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    annotationProcessor(libs.room.compiler)

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
        check(
            ZipFile(apk).use { zip ->
                zip.getEntry("lib/arm64-v8a/libgojni.so") != null
            },
        ) { "Dealer debug APK is missing lib/arm64-v8a/libgojni.so" }
    }
}
