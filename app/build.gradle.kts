import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing credentials are loaded from `keystore.properties` (local, git-ignored)
// or, if that file is absent, from environment variables (used by CI). When neither is
// present the release build stays unsigned so debug work and CI without secrets keep working.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)

android {
    namespace = "com.gproust.sprout"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gproust.sprout"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.4.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingValue("storeFile", "SPROUT_KEYSTORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = signingValue("storePassword", "SPROUT_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SPROUT_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SPROUT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks the app; the mapping file is embedded in the AAB so
            // Play can deobfuscate crash reports. Validate each release on the
            // internal testing track before production (docs/RELEASING.md).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Package the symbol tables of dependencies' native libs into the
            // AAB so Play can symbolicate native crashes/ANRs.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            // Only attach the signing config when a keystore is actually configured.
            signingConfigs.getByName("release").takeIf { it.storeFile != null }
                ?.let { signingConfig = it }
        }
    }
    // Play splits an app bundle by language and installs only the ones matching
    // the device, which breaks an in-app language picker: choosing a language
    // whose resources were never delivered silently falls back to the device's
    // language instead. Sprout offers all 7 in Settings, so ship all 7 in the
    // base install.
    bundle {
        language {
            enableSplit = false
        }
    }

    // Shipping every language in the base install would otherwise also drag in
    // the ~80 locales our libraries translate into. Keep the 7 Sprout actually
    // offers; anything else falls back to the English default as before.
    androidResources {
        localeFilters += listOf("en", "fr", "de", "es", "it", "pl", "pt")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Let Robolectric load merged resources/manifest so localized
            // strings resolve in JVM unit tests.
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
