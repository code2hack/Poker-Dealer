plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
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
