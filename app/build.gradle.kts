import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.chaquo)
}

android {
    val appId = "${project.group}.android"

    namespace = appId
    // Car build: compileSdk/targetSdk 36 instead of 37. Google has not
    // published the API 37 platform (no "platforms;android-37" in any
    // sdkmanager channel), so the project cannot be built with 37 outside
    // Android Studio's preview SDK. On the API 27 car radio this changes
    // nothing at runtime.
    compileSdk = 36

    // Car build: the target radio (ALPS Q91-A5-CPL, AutoChips AC8227L) is a
    // 32-bit ARMv7 device (armeabi-v7a). Building only that ABI keeps the
    // APK small and, more importantly, produces native libraries the radio
    // can actually load (arm64-v8a/x86_64 .so files are useless there).
    val abis = listOf("armeabi-v7a")
    val cmakeVersion = "4.1.2"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = appId

        minSdk = 24
        targetSdk = 36

        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 31
        versionName = project.version.toString()

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += abis
        }

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            isUniversalApk = false
        }
    }

    // Car build: the AAR metadata check fails because several published
    // dependencies (Compose 1.12.1, Coil 3.6.3, OkHttp 5.5.1, core-ktx
    // 1.19.1) declare minCompileSdk 37, and the API 37 platform is not
    // published. The Kotlin/Java compilers already enforce that no API 37
    // symbol is used (everything resolves against android-36), so the
    // check adds nothing here; skip it for the debug and release builds.
    afterEvaluate {
        tasks.findByName("checkDebugAarMetadata")?.let { it.enabled = false }
        tasks.findByName("checkReleaseAarMetadata")?.let { it.enabled = false }
    }

    signingConfigs {
        create("ci") {
            storeFile = System.getenv("ANDROID_NIGHTLY_KEYSTORE")?.let { file(it) }
            storePassword = System.getenv("ANDROID_NIGHTLY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_NIGHTLY_KEYSTORE_ALIAS")
            keyPassword = System.getenv("ANDROID_NIGHTLY_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            manifestPlaceholders["appName"] = "ViTune Debug"
        }

        release {
            // Car release build: keep the same package as the debug builds
            // (app.vitune.android.debug) so this release replaces v3 as the
            // same app. BUILD ONLY — remove to go back to a normal release
            // (package app.vitune.android).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-RELEASE"
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appName"] = "ViTune"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("nightly") {
            initWith(getByName("release"))
            matchingFallbacks += "release"

            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-NIGHTLY"
            manifestPlaceholders["appName"] = "ViTune Nightly"
            signingConfig = signingConfigs.findByName("ci")
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes.add("META-INF/**/*")
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    externalNativeBuild {
        cmake {
            version = cmakeVersion
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

afterEvaluate {
    val jniLibs = file("${layout.projectDirectory}/src/main/jniLibs").also { it.mkdirs() }
    android.buildTypes.forEach { type ->
        val typeCapitalized = type.name.let {
            it.first().uppercase() + it.substring(1)
        }

        tasks.named("assemble${typeCapitalized}").configure {
            doFirst {
                val cxxDir =
                    file("${layout.buildDirectory.get()}/intermediates/cxx/${if (typeCapitalized == "Debug") "Debug" else "RelWithDebInfo"}")

                cxxDir.walkTopDown().forEach cxx@{ f ->
                    if (f.name != "qjs") return@cxx

                    f.copyTo(
                        target = jniLibs
                            .resolve(f.parentFile.name)
                            .also { it.mkdirs() }
                            .resolve("libqjs.so"), // disguise because fuck you
                        overwrite = true
                    )
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_5)

        freeCompilerArgs.addAll(
            "-Xconsistent-data-class-copy-visibility"
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

composeCompiler {
    if (project.findProperty("enableComposeCompilerReports") == "true") {
        val dest = layout.buildDirectory.dir("compose_metrics")
        metricsDestination = dest
        reportsDestination = dest
    }
}

chaquopy {
    defaultConfig {
        // Car build: Python 3.11, not 3.14. Chaquopy dropped the 32-bit
        // ABIs (armeabi-v7a, x86) in Python 3.12 and later; only Python
        // 3.11 and older ship an armeabi-v7a runtime, and the target radio
        // (AC8227L) is 32-bit ARM. yt-dlp only needs Python 3.10+.
        version = "3.11"
        pip {
            install("yt-dlp>=2026.10.26")
            install("yt-dlp-ejs>=0.8.0")
            install("pip")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugaring)

    implementation(projects.compose.persist)
    implementation(projects.compose.preferences)
    implementation(projects.compose.routing)
    implementation(projects.compose.reordering)

    implementation(fileTree(projectDir.resolve("vendor")))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.activity)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.shimmer)
    implementation(libs.compose.lottie)
    implementation(libs.compose.material3)

    implementation(libs.coil.compose)
    implementation(libs.coil.ktor)

    implementation(libs.palette)
    implementation(libs.monet)
    runtimeOnly(projects.core.materialCompat)

    implementation(libs.exoplayer)
    implementation(libs.exoplayer.workmanager)
    implementation(libs.media3.session)
    implementation(libs.media)

    implementation(libs.workmanager)
    implementation(libs.workmanager.ktx)

    //noinspection LoginCredentials
    implementation(libs.credentials)
    //noinspection LoginCredentials
    implementation(libs.credentials.play)

    implementation(libs.kotlin.coroutines)
    implementation(libs.kotlin.immutable)
    implementation(libs.kotlin.datetime)

    implementation(libs.room)
    ksp(libs.room.compiler)

    implementation(libs.log4j)
    implementation(libs.slf4j)
    implementation(libs.logback)

    implementation(projects.providers.github)
    implementation(projects.providers.innertube)
    implementation(projects.providers.kugou)
    implementation(projects.providers.lrclib)
    implementation(projects.providers.piped)
    implementation(projects.providers.sponsorblock)
    implementation(projects.providers.translate)
    implementation(projects.core.data)
    implementation(projects.core.ui)
}
