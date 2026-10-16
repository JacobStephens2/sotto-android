package page.stephens.sotto.shell.ui

// Constitution boundary 6, checked on the rendered About screen of the real
// SottoActivity. Runs under each flavor's unit-test task (testLocalDebugUnitTest,
// testSyncDebugUnitTest), where Robolectric loads that flavor's merged manifest,
// so the no-network claim is held against the permission the build really has.

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import page.stephens.sotto.BuildConfig

@RunWith(RobolectricTestRunner::class)
class AboutScreenTest {

    @get:Rule val compose = createAndroidComposeRule<SottoActivity>()

    private val holdsInternet: Boolean
        get() = compose.activity.packageManager
            .getPackageInfo(compose.activity.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET)

    @Before
    fun openAbout() {
        compose.onNodeWithText("About").performClick()
        compose.waitForIdle()
    }

    private fun shows(phrase: String) = compose.onNodeWithText(phrase, substring = true).assertExists()

    @Test
    fun `about calls the voice synthetic and names Kokoro-82M, its license and its training data`() {
        shows("synthetic")
        shows("Kokoro-82M")
        shows("Apache-2.0")
        shows("documented public-domain and permissively licensed")
    }

    @Test
    fun `about says narration happens on the device`() {
        shows("narrates documents on this device")
    }

    @Test
    fun `the no-network claim and its proof appear exactly when the build holds no INTERNET permission`() {
        val expected = if (holdsInternet) 0 else 1
        compose.onAllNodesWithText("holds no INTERNET permission", substring = true).assertCountEquals(expected)
        compose.onAllNodesWithText("aapt dump permissions", substring = true).assertCountEquals(expected)
    }

    @Test
    fun `the manifest under test is the flavor's own`() {
        // Guards the test above against passing vacuously on the wrong manifest.
        assertEquals(BuildConfig.FLAVOR == "sync", holdsInternet)
    }
}
