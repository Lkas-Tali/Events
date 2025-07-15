plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.student.events"
    // Aligned SDK to match provided code for compatibility
    compileSdk = 35

    defaultConfig {
        applicationId = "com.student.events"
        minSdk = 29
        // Aligned SDK to match provided code for compatibility
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        // Aligned Java version to match provided code
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        // Aligned Kotlin JVM target to match provided code
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        dataBinding = true  // Added for ProfileActivity
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android libraries from your file
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Material Design (v1.12.0 is already in your catalog)
    implementation(libs.material.v1120)

    // Added for Event list grid
    implementation(libs.androidx.recyclerview)
    // Added for Event card layout
    implementation(libs.androidx.cardview)

    // Firebase (from your file)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    // Added for future image uploads to Firebase Storage
    implementation(libs.firebase.storage.ktx)

    // Added for loading event images
    implementation(libs.glide)

    // Testing libraries (from your file)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}