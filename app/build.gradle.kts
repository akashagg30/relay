plugins {
    id("com.android.application")
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Auto-increment versionCode from git commit count
val commitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1

// Release 1.0.113 ships a fixed versionCode/versionName via
// -PrelayVersionCode=113 -PrelayVersionName=1.0.113. A plain build
// (no properties) still auto-derives from the commit count.
val releaseVersionCode = (findProperty("relayVersionCode") as String?)?.toInt() ?: commitCount
val releaseVersionName = (findProperty("relayVersionName") as String?) ?: "1.0.$releaseVersionCode"

// Fixed signing key so a build can be installed OVER a previous one.
//
// Without this, AGP auto-generates a debug keystore per machine — and since every
// GitHub Actions run is a fresh runner, every build was signed with a different
// key. Android refuses to update an app whose signature changed, failing with
// "App not installed as package conflicts with an existing package", so the old
// build had to be uninstalled (losing its data) before each install.
//
// The key lives in GitHub secrets; the workflow decodes it and points
// KEYSTORE_PATH at it. When those vars are absent (plain local build) we fall
// back to the default debug signing so `./gradlew assembleDebug` still works.
val keystorePath = System.getenv("KEYSTORE_PATH")
val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("KEY_ALIAS") ?: "relay"
val signingKeyPassword = System.getenv("KEY_PASSWORD")

val hasStableSigning = !keystorePath.isNullOrBlank() &&
    !keystorePassword.isNullOrBlank() &&
    file(keystorePath).exists() &&
    file(keystorePath).length() > 0L

android {
    namespace = "com.agent.accessibility"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.agent.accessibility"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        buildConfigField("String", "APP_VERSION", "\"$releaseVersionName\"")
    }

    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword ?: keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    lint {
        abortOnError = true
        checkDependencies = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
    // Real org.json for JVM unit tests: android.jar only ships stubs that throw.
    // Lets tests build/parse the observe envelope, screenshot error envelope
    // and element-id args without Robolectric.
    testImplementation("org.json:json:20240303")
}

// Debug: print version during build
tasks.configureEach {
    if (name == "assembleDebug") {
        doFirst {
            println("=== BUILD VERSION: versionCode=$releaseVersionCode versionName=$releaseVersionName ===")
        }
    }
}


ktlint {
    android = true
    ignoreFailures = true
}