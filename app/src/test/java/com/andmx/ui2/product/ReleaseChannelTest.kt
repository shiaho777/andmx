package com.andmx.ui2.product

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReleaseChannelTest {
    @Test
    fun stableChannelAcceptsOnlyStableReleaseTags() {
        assertTrue(matchesGithubReleaseChannel("v1.0.0", prerelease = false, previewChannel = false))
        assertFalse(matchesGithubReleaseChannel("v1.0.0", prerelease = true, previewChannel = false))
        assertFalse(matchesGithubReleaseChannel("v1.0.0-preview.1", prerelease = false, previewChannel = false))
    }

    @Test
    fun previewChannelKeepsExistingPreviewTags() {
        assertTrue(matchesGithubReleaseChannel("v0.1.0-preview.2", prerelease = true, previewChannel = true))
        assertFalse(matchesGithubReleaseChannel("v0.1.0-preview.2", prerelease = false, previewChannel = true))
        assertFalse(matchesGithubReleaseChannel("v1.0.0", prerelease = false, previewChannel = true))
        assertFalse(matchesGithubReleaseChannel("v1.0.0", prerelease = true, previewChannel = true))
    }

    @Test
    fun manifestUsesTheExactVersionedFilename() {
        for (version in listOf("1.0.0", "1.0.1-preview.2")) {
            val release = parsePreviewRelease("v$version", manifest(version, "andmx.$version.APK"), "")
            assertEquals("$PRODUCT_REPO/releases/download/v$version/andmx.$version.APK", release.apkUrl)
        }
    }

    @Test
    fun manifestRejectsAFilenameThatDoesNotMatchTheVersion() {
        for (name in listOf("andmx.1.0.1.APK", "andmx.1.0.0.apk", "../andmx.1.0.0.APK")) {
            assertThrows(IllegalArgumentException::class.java) {
                parsePreviewRelease("v1.0.0", manifest("1.0.0", name), "")
            }
        }
    }

    private fun manifest(version: String, name: String) = JSONObject()
        .put("versionName", version)
        .put("versionCode", 4)
        .put("packageName", "com.andmx")
        .put("apk", name)
        .put("sha256", "0".repeat(64))
        .put("certificateSha256", "a".repeat(64))
}
