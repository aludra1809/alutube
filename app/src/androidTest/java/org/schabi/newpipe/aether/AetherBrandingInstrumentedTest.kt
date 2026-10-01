package org.schabi.newpipe.aether

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

/**
 * Branding verification on a clean install (Phase 7, task 42). Runs on the
 * instrumented device/emulator: asserts the app is labelled "Alutube" and
 * that the launcher + settings entries resolve to Alutube-branded assets.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AetherBrandingInstrumentedTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun appLabelIsAlutube() {
        val label = context.packageManager
            .getApplicationInfo(context.packageName, 0)
            .loadLabel(context.packageManager)
            .toString()
        // Debug builds append the git branch name (e.g. "Alutube main"), so
        // assert the Alutube prefix rather than an exact match.
        assertTrue("app label must be Alutube-branded, got \"$label\"", label.startsWith("Alutube"))
    }

    @Test
    fun applicationIdIsAlutube() {
        // Debug builds append ".debug" plus the git branch suffix.
        assertTrue(
            "applicationId must be Alutube-branded, got ${context.packageName}",
            context.packageName.startsWith("org.alutube.app")
        )
    }

    @Test
    fun launcherIconResolves() {
        val iconRes = context.packageManager
            .getApplicationInfo(context.packageName, 0)
            .icon
        assertTrue("launcher icon must be set", iconRes != 0)
    }

    @Test
    fun aetherSettingsStringsExist() {
        assertEquals("Alutube connection", context.getString(R.string.settings_category_aether_title))
        assertTrue(context.getString(R.string.aether_enabled_title).startsWith("Enable Alutube"))
        assertTrue(context.getString(R.string.aether_scan_mode_title).isNotEmpty())
    }
}
