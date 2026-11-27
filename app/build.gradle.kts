plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val includeLinuxRuntime = providers.gradleProperty("androidLinuxRuntimeDir").isPresent
val githubPreview = providers.gradleProperty("andmxGithubPreview").orNull == "true"
val previewVersionCode = providers.gradleProperty("andmxPreviewVersionCode").orNull?.toInt() ?: 2
val previewVersionName = providers.gradleProperty("andmxPreviewVersionName").orElse("0.1.0-preview.1").get()


android {
    namespace = "com.andmx"
    compileSdk = if (includeLinuxRuntime) 36 else 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.andmx"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "GITHUB_PREVIEW", "false")
        vectorDrawables { useSupportLibrary = true }
        ndk {
            // Real devices and the dev emulator are arm64; keep the APK lean.
            abiFilters += providers.gradleProperty("testAbi").getOrElse("arm64-v8a").also {
                require(it in setOf("arm64-v8a", "x86_64"))
            }
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    flavorDimensions += "engine"
    productFlavors {
        // Plan C core: modern targetSdk, JGit/Kotlin tools only (no native exec
        // from the data dir). Play-friendly.
        create("lite") {
            dimension = "engine"
            targetSdk = 34
            buildConfigField("int", "PROBE_TARGET_SDK", "34")
            if (githubPreview) {
                require(previewVersionCode > 1 && previewVersionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+-preview\\.[0-9]+")))
                versionCode = previewVersionCode
                versionName = previewVersionName
                buildConfigField("boolean", "GITHUB_PREVIEW", "true")
            }
        }
        // Power engine: targetSdk 28 so guest binaries can be exec'd from the
        // writable data dir (the proot userland). Sideload-only by design.
        create("proot") {
            dimension = "engine"
            targetSdk = 28
            buildConfigField("int", "PROBE_TARGET_SDK", "28")
        }
    }

    if (includeLinuxRuntime) {
        productFlavors.create("vm") {
            dimension = "engine"
            targetSdk = 36
            applicationIdSuffix = ".vm"
            buildConfigField("int", "PROBE_TARGET_SDK", "36")
        }
    }
    sourceSets.named("lite") { java.srcDir("src/noVm/java") }
    sourceSets.named("proot") { java.srcDir("src/noVm/java") }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 单元测试中 android.util.Log 等 Android 框架调用返回默认值而非抛异常。
    // AgentEngine.streamWithRetry 等生产代码使用 Log.i/e，本地 JVM 测试需要此配置。
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Extract native binaries to nativeLibraryDir on install so they can
            // be exec'd (required for proot and the exec probe).
            useLegacyPackaging = true
        }
    }
}

if (githubPreview) {
    androidComponents.onVariants(androidComponents.selector().withFlavor("engine" to "lite")) { variant ->
        variant.packaging.jniLibs.excludes.addAll("**/libproot.so", "**/libproot_loader.so", "**/libtalloc.so")
    }
}

androidComponents.onVariants(androidComponents.selector().withFlavor("engine" to "vm")) { variant ->
    variant.packaging.jniLibs.excludes.addAll("**/libproot.so", "**/libproot_loader.so", "**/libtalloc.so")
}

dependencies {
    if (includeLinuxRuntime) add("vmImplementation", "org.andmx:linux-runtime:0.1.0-alpha.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
