plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.saverio.pdfviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.saverio.pdfviewer.beta"
        minSdk = 21
        targetSdk = 35
        versionCode = 68
        versionName = "2.0#2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionNameSuffix = "-beta"

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
                "lib/arm64-v8a/libmlkit_google_ocr_pipeline.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/armeabi-v7a/libjniPdfium.so",
                "lib/armeabi-v7a/libmodft2.so",
                "lib/armeabi-v7a/libmodpdfium.so",
                "lib/armeabi-v7a/libmodpng.so",
                "lib/armeabi-v7a/libmlkit_google_ocr_pipeline.so",
                "lib/x86/libc++_shared.so",
                "lib/x86/libjniPdfium.so",
                "lib/x86/libmodft2.so",
                "lib/x86/libmodpdfium.so",
                "lib/x86/libmodpng.so",
                "lib/x86/libmlkit_google_ocr_pipeline.so",
                "lib/x86_64/libc++_shared.so",
                "lib/x86_64/libjniPdfium.so",
                "lib/x86_64/libmodft2.so",
                "lib/x86_64/libmodpdfium.so",
                "lib/x86_64/libmodpng.so",
                "lib/x86_64/libmlkit_google_ocr_pipeline.so"
            )
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

    // ML Kit on-device text recognition (OCR) for PDF search
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Kotlin coroutines for background OCR indexing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
