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

android {
    namespace = "com.agent.accessibility"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.agent.accessibility"
        minSdk = 26
        targetSdk = 35
        versionCode = commitCount
        versionName = "1.0.$commitCount"

        buildConfigField("String", "APP_VERSION", "\"1.0.$commitCount\"")
    }

    buildTypes {
        release {
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
}

// Debug: print version during build
tasks.configureEach {
    if (name == "assembleDebug") {
        doFirst {
            println("=== BUILD VERSION: versionCode=$commitCount versionName=1.0.$commitCount ===")
        }
    }
}


ktlint {
    android = true
    ignoreFailures = false
    reporter = "checkstyle,plain"
    reporters {
        reporter("checkstyle")
        reporter("plain")
    }
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
    }
}

tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask> {
    dependsOn("lint")
}