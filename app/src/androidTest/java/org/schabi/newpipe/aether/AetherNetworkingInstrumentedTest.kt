package org.schabi.newpipe.aether

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import java.net.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.DownloaderImpl

/**
 * Networking regression (Phase 7, task 43 — device/CI): verifies that the
 * shared OkHttp client still performs real requests when Aether is disabled
 * (direct mode) and that the proxy toggle is applied without breaking the
 * client. Full "through the tunnel" playback/search verification requires a
 * live Aether backend and is part of the device matrix (task 44).
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AetherNetworkingInstrumentedTest {

    @Test
    fun directModeFetchStillWorks() {
        // Aether off -> exact original direct client must resolve the network.
        val downloader = DownloaderImpl.getInstance()
        assertNotNull("DownloaderImpl must be initialised", downloader)
        downloader.setProxy(null)
        val response = downloader.get("https://example.com/", null)
        assertNotNull(response)
        assertTrue(
            "expected a 2xx/3xx status, got ${response!!.responseCode()}",
            response.responseCode() in 200..399
        )
    }

    @Test
    fun proxyToggleDoesNotBreakClient() {
        val downloader = DownloaderImpl.getInstance()
        downloader.setProxy(Proxy.NO_PROXY)
        // Proxy.NO_PROXY is effectively direct; the client must still work.
        val response = downloader.get("https://example.com/", null)
        assertNotNull(response)
        assertEquals(200, response?.responseCode()?.let { if (it in 200..399) 200 else it })
    }
}
