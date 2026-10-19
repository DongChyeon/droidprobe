plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    `maven-publish`
}

android {
    namespace = "dev.droidprobe.hilt"
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
    api(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "probe-hilt"
            afterEvaluate { from(components["release"]) }
        }
    }
}
