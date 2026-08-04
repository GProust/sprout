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
        versionCode = 2
        versionName = "1.3.0"

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
    compileOptions {
        // The *app's* language level and bytecode, constrained by Android (D8
        // desugaring + ART) — deliberately independent of the JDK that runs the
        // build (see the `java.toolchain` block below). Bumping this changes the
        // bytecode we ship, so it stays at 17.
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

// The JDK used to *run* the build (compile Kotlin/Java, run unit tests). Pinned
// here rather than only in the three CI workflows, so every build — CI, release
// or local — uses the same toolchain instead of whatever JDK the machine happens
// to default to. Separate from `compileOptions` above, which fixes the bytecode
// we actually ship.
//
// 17 is AGP's minimum *and* default, and is all this project needs. Raising it
// only becomes worthwhile if the Robolectric tests should run against the SDK we
// ship: Robolectric defaults to `targetSdk`, and the SDK 36 `android.jar` is
// Java 21 bytecode that a JDK 17 build cannot read. The tests pin
// `@Config(sdk = [34])` instead, so that trade-off isn't being paid today.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
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
