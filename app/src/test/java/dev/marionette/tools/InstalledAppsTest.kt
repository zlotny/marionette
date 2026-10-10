package dev.marionette.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppsTest {
    private val apps = listOf(
        InstalledApp("YT Music", "app.revanced.android.apps.youtube.music"),
        InstalledApp("YouTube", "com.google.android.youtube"),
        InstalledApp("Spotify", "com.spotify.music"),
    )

    @Test
    fun matchesAppByPackageWordsWhenLabelDiffers() {
        assertEquals("app.revanced.android.apps.youtube.music", InstalledApps.match(apps, "YouTube Music").first().packageName)
    }

    @Test
    fun labelMatchesStillWin() {
        assertEquals("com.google.android.youtube", InstalledApps.match(apps, "YouTube").first().packageName)
        assertEquals("com.spotify.music", InstalledApps.match(apps, "spotify").first().packageName)
    }
}
