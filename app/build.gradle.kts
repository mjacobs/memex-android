plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Name of the Gradle property that picks the build type the test sources compile and run
 * against. AGP reads it at configuration time, so a task inside this build cannot set it;
 * the checks at the bottom of this file re-invoke the wrapper with it instead.
 */
val TEST_BUILD_TYPE_PROPERTY = "android.testBuildType"

/**
 * Command prefix that re-invokes this build's Gradle wrapper.
 *
 * On Windows the wrapper is a batch script, which `Exec` (and the underlying
 * `CreateProcess`) cannot launch directly, so it goes through the command interpreter.
 */
val gradleWrapperCommand: List<String> =
    if (System.getProperty("os.name").startsWith("Windows")) {
        listOf("cmd.exe", "/d", "/c", "$rootDir\\gradlew.bat")
    } else {
        listOf("$rootDir/gradlew")
    }

android {
    namespace = "com.memex.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.memex.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
        create("releaseInstrumented") {
            // Mirrors `release` (R8 minification + resource shrinking) but stays
            // debuggable and debug-signed so the existing on-device MockWebServer
            // UI suite can be installed and run against it. This is what actually
            // exercises the Retrofit/kotlinx.serialization proguard-rules.pro keep
            // rules; the plain `debug` build type never runs R8 at all, so a
            // missing keep rule would only surface as a runtime crash in a real
            // release build.
            initWith(getByName("release"))
            isDebuggable = true
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            testProguardFiles("proguard-rules-android-test.pro")
        }
    }

    // AGP builds the androidTest sources against a single "test build type" per
    // module (default: `debug`); this is a hard AGP limitation without adding a
    // product flavor dimension. So `releaseInstrumented` is not exercised by an
    // ordinary `./gradlew connectedAndroidTest`, and it takes its own invocation
    // with the property below, which points the androidTest variant at the
    // minified build type (JVM unit tests keep their own per-build-type tasks,
    // `testDebugUnitTest` and friends, and are unaffected):
    //     ./gradlew connectedAndroidTest -Pandroid.testBuildType=releaseInstrumented
    // `minifiedInstrumentedTest` (below) is that invocation, checked in so the
    // minified variant has a named, repeatable check rather than a comment.
    testBuildType = providers.gradleProperty(TEST_BUILD_TYPE_PROPERTY).getOrElse("debug")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // SSE frame parsing logs skipped frames through android.util.Log, which is a
        // stub in local unit tests; return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "**/.*",
                "**/.secrets/**",
                "**/secrets/**"
            )
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

/**
 * Runs the on-device suite against the R8-minified `releaseInstrumented` variant.
 *
 * This is the check that catches a missing keep rule: the default `debug` build never
 * runs R8, so a Retrofit, kotlinx.serialization, or Compose class stripped or renamed by
 * minification only shows up here — or in a shipped release. Needs a connected device or
 * emulator, like any other instrumentation run.
 */
tasks.register<Exec>("minifiedInstrumentedTest") {
    group = "verification"
    description =
        "Runs the instrumentation suite against the R8-minified releaseInstrumented variant."
    workingDir = rootDir
    commandLine(
        gradleWrapperCommand +
            listOf(
                "connectedAndroidTest",
                "-P$TEST_BUILD_TYPE_PROPERTY=releaseInstrumented"
            )
    )
}

/**
 * Builds — but does not run — the minified app and its test APK.
 *
 * Every R8 pass happens here, so this catches a keep rule that breaks the build or
 * strips a class the test APK links against, and unlike [minifiedInstrumentedTest] it
 * needs no device.
 */
tasks.register<Exec>("assembleMinifiedInstrumentedTest") {
    group = "verification"
    description =
        "Builds the minified releaseInstrumented app and test APKs, running R8, with no device."
    workingDir = rootDir
    commandLine(
        gradleWrapperCommand +
            listOf(
                ":app:assembleAndroidTest",
                "-P$TEST_BUILD_TYPE_PROPERTY=releaseInstrumented"
            )
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowSizeClass)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.security.crypto)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.espresso.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
