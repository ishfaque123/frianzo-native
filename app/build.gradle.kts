plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.frianzo.app"
    compileSdk = 35

    defaultConfig {
        // A NEW app id namespace is used only for this prototype build.
        // The Play app keeps its own id; do not publish this prototype under
        // the live app's id until the owner orders a Play build.
        applicationId = "com.frianzo.nativeapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-prototype"
    }

    buildFeatures { compose = true }

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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Networking: Retrofit + OkHttp. Auth is COOKIE based on this backend
    // (authMiddleware reads vynzo_auth_token from cookies only, it does NOT
    // accept an Authorization header), so a persistent CookieJar is required.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Images in feed cards.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Native Google sign-in (Credential Manager). The web client id is NOT
    // hard-coded: it is fetched at runtime from
    // GET /api/auth/google/native-config, exactly like the live backend serves.
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
