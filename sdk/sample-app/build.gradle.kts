plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidprobe.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.droidprobe.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // -PwithEcho=true adds the `echo` command, so one CLI binary can be checked against two builds.
        buildConfigField("boolean", "WITH_ECHO", (findProperty("withEcho") == "true").toString())
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.fragment)

    debugImplementation(project(":probe-core"))
    debugImplementation(project(":probe-okhttp"))
    debugImplementation(project(":probe-lifecycle"))
}
