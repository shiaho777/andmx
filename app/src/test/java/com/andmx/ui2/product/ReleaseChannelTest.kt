package com.andmx.ui2.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
