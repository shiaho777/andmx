package com.andmx.agent.plugins.mobile

object AndroidComposeTemplate {
    fun files(
        appName: String,
        packageName: String,
        minSdk: Int,
        compileSdk: Int,
        sdkDir: String? = null,
    ): Map<String, String> {
        val pkgPath = packageName.replace('.', '/')
        val out = linkedMapOf(
            "settings.gradle.kts" to """
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "$appName"
include(":app")
""".trimStart(),
            "build.gradle.kts" to """
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
""".trimStart(),
            "gradle.properties" to """
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
""".trimStart(),
            "gradle/libs.versions.toml" to """
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
coreKtx = "1.15.0"
activityCompose = "1.9.3"
composeBom = "2024.12.01"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
""".trimStart(),
            "app/build.gradle.kts" to """
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "$packageName"
    compileSdk = $compileSdk

    defaultConfig {
        applicationId = "$packageName"
        minSdk = $minSdk
        targetSdk = $compileSdk
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
}
""".trimStart(),
            "app/src/main/AndroidManifest.xml" to """
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""".trimStart(),
            "app/src/main/res/values/strings.xml" to """
<resources>
    <string name="app_name">$appName</string>
</resources>
""".trimStart(),
            "app/src/main/res/values/styles.xml" to """
<resources>
    <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar" />
</resources>
""".trimStart(),
            "app/src/main/java/$pkgPath/MainActivity.kt" to """
package $packageName

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}

@Composable
fun App() {
    var count by remember { mutableIntStateOf(0) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Hello from Android",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Count: ${'$'}count",
                    modifier = Modifier.semantics { contentDescription = "countLabel" },
                    style = MaterialTheme.typography.titleLarge,
                )
                Button(
                    onClick = { count += 1 },
                    modifier = Modifier.semantics { contentDescription = "incrementButton" },
                ) {
                    Text("Increment")
                }
            }
        }
    }
}
""".trimStart(),
            "README.md" to """
# $appName

Minimal Kotlin + Jetpack Compose app generated by AndMX Android Dev (ZCode-compatible template).

```bash
./gradlew :app:assembleDebug
```
""".trimStart(),
        )
        if (!sdkDir.isNullOrBlank()) {
            val escaped = sdkDir.replace("\\", "\\\\")
            out["local.properties"] = "sdk.dir=$escaped\n"
        }
        return out
    }
}
