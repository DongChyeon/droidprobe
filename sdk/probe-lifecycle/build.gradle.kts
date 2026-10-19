plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "dev.droidprobe.lifecycle"
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
    implementation(libs.androidx.startup)
    // Fragment support is used only when the app already ships androidx.fragment.
    compileOnly(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "probe-lifecycle"
            afterEvaluate { from(components["release"]) }
        }
    }
}
