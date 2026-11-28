plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val appAbi = providers.gradleProperty("testAbi").getOrElse("arm64-v8a")
require(appAbi in setOf("arm64-v8a", "x86_64"))
val runtimeSource = rootProject.layout.projectDirectory.dir("linux-runtime")
val githubRelease = providers.gradleProperty("andmxGithubRelease").orNull == "true"
val appVersionCode = providers.gradleProperty("andmxVersionCode").orNull?.toInt() ?: 4
val appVersionName = providers.gradleProperty("andmxVersionName").getOrElse("1.0.0")
val releaseVersion = Regex("""[0-9]+\.[0-9]+\.[0-9]+(?:-preview\.[0-9]+)?""")

require(appVersionCode > 0)
require(!githubRelease || appVersionCode > 1 && releaseVersion.matches(appVersionName))

android {
    namespace = "com.andmx"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.andmx"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "GITHUB_RELEASE", githubRelease.toString())
        buildConfigField("boolean", "GITHUB_PREVIEW", (githubRelease && "-preview." in appVersionName).toString())
        vectorDrawables { useSupportLibrary = true }
        ndk {
            abiFilters += appAbi
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

    sourceSets.named("main") {
        java.srcDir(runtimeSource.dir("src"))
    }

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
            useLegacyPackaging = true
        }
    }
}

abstract class PrepareLinuxRuntime : Exec() {
    @get:OutputDirectory
    abstract val runtimeAssets: DirectoryProperty

    @get:OutputDirectory
    abstract val runtimeLibraries: DirectoryProperty
}

if (appAbi == "arm64-v8a") {
    val prepareLinuxRuntime = tasks.register<PrepareLinuxRuntime>("prepareLinuxRuntime") {
        inputs.dir(runtimeSource.dir("tools"))
        inputs.dir(runtimeSource.dir("native"))
        workingDir(rootProject.projectDir)
        val localPython = rootProject.file(".venv/bin/python")
        val python = providers.gradleProperty("andmxRuntimePython")
            .orElse(providers.environmentVariable("ANDMX_RUNTIME_PYTHON"))
            .getOrElse(if (localPython.canExecute()) localPython.absolutePath else "python3")
        val script = runtimeSource.file("tools/prepare.py").asFile
        doFirst {
            commandLine(python, script, "--assets", runtimeAssets.get().asFile, "--jni", runtimeLibraries.get().asFile)
        }
    }
    androidComponents.onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareLinuxRuntime, PrepareLinuxRuntime::runtimeAssets)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareLinuxRuntime, PrepareLinuxRuntime::runtimeLibraries)
    }
}

dependencies {
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
