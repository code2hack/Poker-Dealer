plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.code2hack.prototype.hotspot.poker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.code2hack.prototype.hotspot.poker"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-prototype"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation(project(":prototypes:android-hotspot:core"))
}
