import java.io.File

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.th3web.lean.awg.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rootProject.layout.projectDirectory.dir("native/amneziawg/generated/jni"))
        }
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.8.1")
    testImplementation(libs.junit)
}

val nativeOutput = rootProject.layout.projectDirectory.dir("native/amneziawg/generated/jni")
val nativeBuild = tasks.register<Exec>("buildAmneziaWgNative") {
    inputs.file(rootProject.layout.projectDirectory.file("native/versions.lock"))
    inputs.files(
        rootProject.fileTree("native/amneziawg") {
            exclude("generated/**")
        },
    )
    outputs.dir(nativeOutput)
    outputs.file(rootProject.layout.projectDirectory.file("native/amneziawg/generated/build-report.json"))

    // build.ps1 is the Windows and CI path. A build server without PowerShell — F-Droid's
    // is one — gets the same libraries from native/build-linux.sh, which the recipe runs
    // as its prebuild step, so this task steps aside instead of failing on a missing
    // interpreter. It only steps aside when the libraries are actually there: a prebuild
    // that never ran has to stop the build, not produce an APK with no tunnel in it.
    onlyIf {
        val haveTool = System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .any { dir -> File(dir, "pwsh").canExecute() || File(dir, "pwsh.exe").canExecute() }
        if (haveTool) return@onlyIf true
        // Any ABI will do. A build that asked for one architecture through LEAN_ABIS
        // produces exactly that one, and naming arm64 here failed the other three.
        val built = nativeOutput.asFile.listFiles()
            ?.any { File(it, "libwg-go.so").isFile } == true
        check(built) {
            "PowerShell is not available and native/amneziawg/generated/jni is empty. " +
                "Run native/build-linux.sh before Gradle on a host without pwsh."
        }
        logger.lifecycle("AmneziaWG: reusing the libraries native/build-linux.sh produced.")
        false
    }

    doFirst {
        val sdkRoot = providers.environmentVariable("ANDROID_SDK_ROOT")
            .orElse(providers.environmentVariable("ANDROID_HOME"))
            .orNull
            ?: error("ANDROID_SDK_ROOT or ANDROID_HOME must point to the pinned Android SDK.")
        commandLine(
            "pwsh",
            "-NoLogo",
            "-NoProfile",
            "-File",
            rootProject.file("native/amneziawg/build.ps1").absolutePath,
            "-AndroidSdkRoot",
            sdkRoot,
        )
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(nativeBuild)
    }
}
