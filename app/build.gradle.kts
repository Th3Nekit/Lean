plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ABI suffixes keep version codes unique while preserving release order.
val abiVersionDigits = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4,
)
val singleAbi = (project.findProperty("abiFilter") as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.th3web.lean"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.th3web.lean"
        minSdk = 24
        targetSdk = 35
        // Continue the monotonic versionCode sequence used by earlier releases.
        versionCode = 37
        versionName = "1.2.1"

        // Packaging one ABI is what makes the APK single-ABI; the split below is off in
        // that case, so there is nothing else narrowing it down.
        if (singleAbi != null) {
            ndk { abiFilters.add(singleAbi) }
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // `full` includes NaiveProxy; `foss` uses helpers built from pinned sources.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            isDefault = true
            // Promotion is disabled in the foss flavour.
            buildConfigField("boolean", "SHOWS_PROMO", "true")
            // Manual update checks remain available in both flavours.
            buildConfigField("boolean", "UPDATE_CHECK_DEFAULT", "true")
        }
        create("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "SHOWS_PROMO", "false")
            buildConfigField("boolean", "UPDATE_CHECK_DEFAULT", "false")
            // Mieru, Xray and olcRTC are built by native/build-linux.sh.
            // NaiveProxy is excluded through the full-only source set below.
            // Do not use a per-flavour packaging block: its implicit receiver can
            // resolve to android and exclude the library from every flavour.
            // NativePlugin.binary() returns null for an unavailable helper.
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
    // One APK per ABI plus a universal one, so a phone downloads ~18 MB instead of ~88.
    //
    // F-Droid gets the universal APK alone: its build refuses outright when a build
    // produces more than one APK, since it publishes exactly one file per version code.
    // `-PsingleApk` turns the split off, and the recipe passes it through gradleprops.
    // AGP otherwise signs a "Dependency metadata" block into the APK: an opaque,
    // Play-oriented blob listing the libraries the build used. F-Droid's scanner rejects
    // any extra signing block it cannot read, and nothing in this app needs it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    splits {
        abi {
            isEnable = singleAbi == null && !project.hasProperty("singleApk")
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
            // the debug key so a local release build still assembles.
            //
            // findByName, not getByName: F-Droid strips the signing configs out of this
            // file before building, because it signs with its own key. getByName then
            // threw "SigningConfig with name 'release' not found" and took the build with
            // it. With findByName the whole thing resolves to null, which is precisely
            // what an unsigned build wants.
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
                ?: signingConfigs.findByName("debug")
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

// A single-ABI build reports 10 * versionCode + the ABI's digit, which is the number the
// F-Droid recipe declares for that entry. It is set on the output rather than in
// defaultConfig because fdroidserver reads versionCode out of this file with a regex:
// an expression there parses as nothing and its update check then reads an older tag.
androidComponents {
    onVariants { variant ->
        val digit = singleAbi?.let { abiVersionDigits.getValue(it) } ?: return@onVariants
        variant.outputs.forEach { output ->
            output.versionCode.set(37 * 10 + digit)
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
