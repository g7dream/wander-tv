import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun prop(name: String): String = (project.findProperty(name) as String?)?.trim().orEmpty()

android {
    namespace = "tv.wander"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "tv.wander"
        minSdk = 25 // Fire OS 6 (Android 7.1) and newer
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-hackathon"
        buildConfigField("String", "API_URL", "\"${prop("wander.apiUrl")}\"")
        buildConfigField("String", "API_KEY", "\"${prop("wander.apiKey")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so judges can sideload the release APK without extra setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("wander.snapDir", rootProject.file("app/build/snaps").absolutePath)
                it.maxHeapSize = "2g"
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":couchlink"))
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
}
