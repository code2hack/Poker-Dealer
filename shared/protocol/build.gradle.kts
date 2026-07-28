plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":shared:domain"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.jsch)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    val liveWorkstation = providers.environmentVariable("POKER_DEALER_LIVE_WORKSTATION")
        .map { it == "true" }
        .orElse(false)
    val liveTermux = providers.environmentVariable("POKER_DEALER_LIVE_TERMUX")
        .map { it == "true" }
        .orElse(false)
    val liveHost = liveWorkstation.zip(liveTermux) { workstation, termux -> workstation || termux }
    outputs.upToDateWhen { !liveHost.get() }
    outputs.doNotCacheIf("host live test is enabled") { liveHost.get() }
}
