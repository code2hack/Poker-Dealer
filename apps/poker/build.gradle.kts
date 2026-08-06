plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "com.code2hack.poker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.code2hack.poker"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-codex-reset"
    }

    flavorDimensions += "transport"
    productFlavors {
        create("mock") {
            dimension = "transport"
            versionNameSuffix = "-mock"
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
    implementation(project(":shared:domain"))
    implementation(project(":shared:protocol"))
    implementation(project(":shared:testing"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.test)
}
