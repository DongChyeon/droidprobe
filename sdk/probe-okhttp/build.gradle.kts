plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "dev.droidprobe.okhttp"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    publishing {
        singleVariant("release")
    }
}

dependencies {
    api(project(":probe-core"))
    api(libs.okhttp)
    implementation(libs.androidx.startup)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "probe-okhttp"
            afterEvaluate { from(components["release"]) }
        }
    }
}
