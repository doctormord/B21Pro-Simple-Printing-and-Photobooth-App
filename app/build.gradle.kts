plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.b21print"
    compileSdk = 34
    defaultConfig {
        applicationId = "de.b21print"
        minSdk = 28
        targetSdk = 34
        versionCode = 11
        versionName = "1.3.7"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Release signing: the key stays outside the repo, configured in ~/.gradle/gradle.properties
    // (see README). Without it, release builds are signed with the local debug key.
    val keystore = providers.gradleProperty("B21_KEYSTORE").orNull
        ?.let { if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.gradleProperty("B21_KEYSTORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("B21_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("B21_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    testImplementation("junit:junit:4.13.2")
}
