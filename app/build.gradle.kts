plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Firebase Phase 1 (Crashlytics + Analytics). google-services MUST be
    // applied before the Crashlytics plugin. Both read app/google-services.json
    // at build time — that file is NEVER committed (repo is public); in CI it
    // is restored from the FIREBASE_GOOGLE_SERVICES_JSON secret (see REPORT.md).
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.frianzo.app"
    compileSdk = 35

    // Stable signing: passwords/alias come ONLY from CI environment
    // variables (GitHub Secrets). Nothing secret is in this file.
    // The keystore file itself is restored by the workflow from the
    // ANDROID_KEYSTORE_BASE64 secret and is never committed.
    signingConfigs {
        create("release") {
            storeFile = file("frianzo-release.keystore")
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    defaultConfig {
        // A NEW app id namespace is used for this app.
        // The Play app keeps its own id; do not publish this app under
        // the live app's id until the owner orders a Play build.
        applicationId = "com.frianzo.nativeapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 60
        versionName = "5.18.4.1"
    }

    buildFeatures { compose = true }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
    implementation(platform("androidx.compose:compose-bom:2024.10.01")) // reverted: 2025.01.00 BOM pulls a foundation-layout 1.10.1 that does not exist; 2024.10.01 built fine in v2.8
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // PERMANENT FIX for builds #31 and #32: ChatBubble and Chat are
    // BOTH in material-icons-extended, not in the core icons set.
    // Guessing another "core" icon failed twice, so the extended set
    // is now a real dependency (version managed by the compose BOM
    // above, no version pinned here). This also covers any other
    // extended-only icon used in future UI work.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // C-B24: navigation-compose REMOVED (was 2.8.5) — nothing in the app
    // uses NavController/NavHost; navigation is hand-rolled state in
    // MainActivity. Dead dependency, slightly larger APK.

    // Networking: Retrofit + OkHttp. Auth is COOKIE based on this backend
    // (authMiddleware reads vynzo_auth_token from cookies only, it does NOT
    // accept an Authorization header), so a persistent CookieJar is required.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // v5.13: Socket.IO client for chat per-message actions (reply / react /
    // edit / delete / pin / forward). The backend exposes those ONLY as
    // Socket.IO events (server is socket.io 4.7.5); the 2.x Java client
    // speaks its protocol. Plain text sends stay on REST (v5.12 outbox).
    implementation("io.socket:socket.io-client:2.0.1")

    // Images in feed cards.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Native Google sign-in (Credential Manager). The web client id is NOT
    // hard-coded: it is fetched at runtime from
    // GET /api/auth/google/native-config, exactly like the live backend serves.
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // In-app camera recorder (v3.8, F1): CameraX — preview, video
    // capture, photo capture, lifecycle binding.
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Firebase Phase 1: Crashlytics + Analytics. Versions are managed by
    // the BOM. BOM 33.7.0 pins firebase-analytics 22.1.2 and
    // firebase-crashlytics 19.3.0. BOM 35.1.0 was REVERTED (run #118):
    // its analytics 24.0.0 pulls play-services-measurement-api 24.0.0 and
    // measurement-impl 24.0.0 whose Kotlin metadata is binary version
    // 2.4.0, which the Kotlin 2.0.21 compiler cannot read (it reads
    // metadata <= 2.0.0). Every Kotlin-compiled jar in the 33.7.0 graph
    // was verified on Google Maven to carry metadata <= 2.0.0 (see
    // REPORT.md). No code changes needed: both SDKs auto-initialise from
    // google-services.json. No mapping-file config: minify is off.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
}
