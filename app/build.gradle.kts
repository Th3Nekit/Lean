plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.th3web.lean"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.th3web.lean"
        minSdk = 24
        targetSdk = 35
        // 1.1.0 — the field-report release: the three faults behind "connected but
        // nothing loads" (HTTPS queries hitting fakeip, a corrupted core cache, DNS not
        // being reset on a network change), a failed AmneziaWG handshake no longer
        // reported as connected, and Clash .yaml import. versionCode keeps its own
        // monotonic count (installers order by it, never by the name), so it continues
        // from the 0.9.x CI series rather than restarting at 1.
        versionCode = 34
        versionName = "1.1.3"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Two ways to ship the same application.
    //
    // `full` is the build published on GitHub and the site. It carries the helper
    // executables (NaiveProxy, Mieru, Xray, olcRTC) that some protocols need, and those
    // arrive as upstream release downloads pinned by sha256 in native/versions.lock.
    //
    // `foss` exists because a build that downloads prebuilt executables is not a build
    // from source, and F-Droid will not carry one. It contains only what this repository
    // can build from source on a Linux machine: the app, libcore (sing-box) and
    // AmneziaWG-Go. The protocols that need a helper are then unavailable, and the app
    // already says so per server rather than failing at connect time.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            isDefault = true
            // The build handed out on GitHub and 4PDA, where pointing at the author's own
            // service is fair. F-Droid is someone else's catalogue: an app that arrives
            // from it advertising a paid subscription is an anti-feature there, and
            // rightly so, so the foss build has no promotion in it at all.
            buildConfigField("boolean", "SHOWS_PROMO", "true")
        }
        create("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "SHOWS_PROMO", "false")
            // Only NaiveProxy is absent, and only because it cannot be built from source
            // on an ordinary build server: it is a Chromium fork, not a Go program.
            // Mieru, Xray and olcRTC are built from pinned sources by
            // native/build-linux.sh, so this flavour carries them.
            //
            // The exclusion is done with source sets below, not with a packaging rule:
            // AGP has no per-flavour `packaging` block, and writing one inside a flavour
            // silently resolves to the android-level block through Kotlin's implicit
            // receiver — which excludes the file from every flavour, quietly.
            //
            // NativePlugin.binary() answers null for the missing one, which the UI
            // reports per server as "нет сборки для этой архитектуры" rather than
            // failing at connect time.
        }
    }

    // NaiveProxy rides in the `full` flavour's own jniLibs, so the `foss` variant
    // never sees it. native/plugins/vendor.ps1 writes it there; everything else stays
    // in src/main/jniLibs and is packaged by both flavours.
    sourceSets {
        getByName("full") {
            jniLibs.srcDir("src/full/jniLibs")
        }
    }

    // Ship per-ABI APKs plus a universal one. The pinned Neko core contains all
    // four Android ABIs and each split APK keeps only its own native library.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        // A fixed debug key, when one is present, so every build shares a signature
        // and debug APKs install over each other without an uninstall. It is the
        // standard Android debug key with the public password "android", and it is
        // never used for release.
        //
        // Optional on purpose: a checkout without it falls back to the per-machine
        // key the SDK generates, which is what any fresh clone should do.
        getByName("debug") {
            val sharedDebugKey = file("debug.keystore")
            if (sharedDebugKey.exists()) {
                storeFile = sharedDebugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Release signing — the PKCS12 keystore + password come from CI secrets
        // (ANDROID_KEYSTORE_FILE points at the decoded keystore; the password is the
        // same for store and key in a PKCS12). Configured only when the keystore is
        // present, so a local build without the secrets still assembles (it then
        // falls back to the debug signature below).
        create("release") {
            val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")
            if (ksFile != null && file(ksFile).exists()) {
                storeFile = file(ksFile)
                storeType = "PKCS12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "lean"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            // R8: shrink + obfuscate the Kotlin/Compose code. Cuts size and makes the
            // app meaningfully harder to reverse. The JNI (libcore/gomobile) and
            // kotlinx.serialization surfaces are protected by proguard-rules.pro.
            isMinifyEnabled = true
            isDebuggable = false
            // Use the real release key when the CI secret is present, else fall back to
            // the (committed) debug key so an unsigned local release build still works.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    // Robolectric provides real android.net.Uri / android.util.Base64 impls in
    // pure-JVM unit tests, so the parser tests (ShareLinks/XrayConfig/Subscriptions)
    // run on `gradle :app:testDebugUnitTest` in CI without a device.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Without this, a CI failure prints only "org.junit.ComparisonFailure at
            // Foo.kt:45" — no expected/actual, no stack — and the HTML report it points
            // at lives on a runner that is already gone. Since this project has no local
            // Android toolchain (CI is the only way to run tests), that turned every red
            // build into a guessing game. `FULL` puts the real assertion message in the
            // log we can actually read.
            all {
                it.testLogging {
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    events("failed")
                    showStackTraces = true
                }
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/**"
        }
        jniLibs {
            // The naive and mieru helpers are executables shipped as lib*.so (see
            // native/plugins/vendor.ps1). Since targetSdk 30 the default is to leave
            // native libs compressed inside the APK and map them from there, which works
            // for a library the loader maps but leaves no file on disk to exec. Legacy
            // packaging puts them back under nativeLibraryDir with the execute bit, which
            // is the only supported way to run a bundled binary on modern Android.
            //
            // The cost is APK size (the libs stop being compressed) and it applies to the
            // core's own .so files too. That is the accepted price of the two protocols;
            // there is no per-file switch.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Pinned Neko sing-box core. It owns the one gomobile go.Seq runtime.
    implementation(files("libs/libcore.aar"))
    implementation(project(":amneziawg-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.haze)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    // Real Android impls (android.net.Uri, android.util.Base64) for JVM unit tests.
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver) // MockWebServer for Http redirect test (test classpath only)
}
