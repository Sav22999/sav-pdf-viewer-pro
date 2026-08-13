import com.android.build.api.variant.FilterConfiguration.FilterType.ABI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.saverio.pdfviewer"
    compileSdk = 35

    dependenciesInfo {
        // Disables dependency metadata when building APKs (for IzzyOnDroid/F-Droid)
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles (for Google Play)
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.saverio.pdfviewer"
        minSdk = 21
        targetSdk = 35
        versionCode = 76
        versionName = "2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Disable generating PNGs from vector drawables (reproducible builds)
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Disable PNG crunching for reproducible builds
            isCrunchPngs = false
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
    splits {
        abi {
            // Split per-ABI: affects ONLY the APKs (assembleRelease) for IzzyOnDroid.
            // The App Bundle (bundleRelease) for Google Play ignores this and keeps all ABIs.
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            // true  = also generate a "universal" APK with all ABIs (fallback for direct distribution)
            // false = generate only the per-ABI APKs (lighter)
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true

            // Prefer our 16 KB-aligned .so from jniLibs over the ones bundled in AARs
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/arm64-v8a/libjniPdfium.so",
                "lib/arm64-v8a/libmodft2.so",
                "lib/arm64-v8a/libmodpdfium.so",
                "lib/arm64-v8a/libmodpng.so",
                "lib/arm64-v8a/libpdfium.so",
                "lib/arm64-v8a/libpdfiumandroid.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/armeabi-v7a/libjniPdfium.so",
                "lib/armeabi-v7a/libmodft2.so",
                "lib/armeabi-v7a/libmodpdfium.so",
                "lib/armeabi-v7a/libmodpng.so",
                "lib/armeabi-v7a/libpdfium.so",
                "lib/armeabi-v7a/libpdfiumandroid.so",
                "lib/x86/libc++_shared.so",
                "lib/x86/libjniPdfium.so",
                "lib/x86/libmodft2.so",
                "lib/x86/libmodpdfium.so",
                "lib/x86/libmodpng.so",
                "lib/x86/libpdfium.so",
                "lib/x86/libpdfiumandroid.so",
                "lib/x86_64/libc++_shared.so",
                "lib/x86_64/libjniPdfium.so",
                "lib/x86_64/libmodft2.so",
                "lib/x86_64/libmodpdfium.so",
                "lib/x86_64/libmodpng.so",
                "lib/x86_64/libpdfium.so",
                "lib/x86_64/libpdfiumandroid.so"
            )
        }
    }
}

// Assign a distinct versionCode to each per-ABI split APK (for IzzyOnDroid updates).
// The App Bundle / universal APK keep the base versionCode (they have no ABI filter).
androidComponents {
    val abiCodes = mapOf(
        "armeabi-v7a" to 1,
        "x86" to 2,
        "arm64-v8a" to 3,
        "x86_64" to 4
    )
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abiName = output.filters.find { it.filterType == ABI }?.identifier
            if (abiName != null) {
                val base = output.versionCode.get() ?: 0
                output.versionCode.set(base * 10 + (abiCodes[abiName] ?: 0))
            }
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.vectordrawable:vectordrawable:1.2.0")

    val navigation_version = "2.7.7"
    implementation("androidx.navigation:navigation-fragment:$navigation_version")
    implementation("androidx.navigation:navigation-ui:$navigation_version")
    implementation("androidx.navigation:navigation-fragment-ktx:$navigation_version")
    implementation("androidx.navigation:navigation-ui-ktx:$navigation_version")

    val lifecycle_version = "2.8.4"
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycle_version")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycle_version")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    implementation("io.github.muthuraj57:AndroidPdfViewer:1.1.0")

    // Pdfium text extraction (FOSS) for PDF search and text selection
    implementation("io.legere:pdfiumandroid:1.0.20")

    // Kotlin coroutines for background text indexing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
