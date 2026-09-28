package org.schabi.newpipe.aether

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preference-persistence tests (Phase 7, task 40): writes the Aether settings
 * into a [FakeSharedPreferences], reads them back, and verifies the mapping
 * into [AetherConfig] (protocol/scan mode) as the settings fragment does.
 */
class AetherPreferencesTest {

    private fun prefsWith(
        enabled: Boolean = false,
        protocol: String? = null,
        scanMode: String? = null
    ): FakeSharedPreferences {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putBoolean("aether_enabled", enabled)
            .putString("aether_protocol", protocol)
            .putString("aether_scan_mode", scanMode)
            .commit()
        return prefs
    }

    private fun configFrom(prefs: FakeSharedPreferences): AetherConfig = AetherConfig(
        protocol = AetherProtocol.from(prefs.getString("aether_protocol", null)),
        scanMode = AetherScanMode.from(prefs.getString("aether_scan_mode", null))
    )

    @Test
    fun `defaults when preferences are empty`() {
        val prefs = FakeSharedPreferences()
        val config = configFrom(prefs)
        assertEquals(AetherProtocol.MASQUE, config.protocol)
        assertEquals(AetherScanMode.BALANCED, config.scanMode)
    }

    @Test
    fun `wireguard and ironclad round-trip through preferences`() {
        val prefs = prefsWith(protocol = "wg", scanMode = "ironclad")
        val config = configFrom(prefs)
        assertEquals(AetherProtocol.WIREGUARD, config.protocol)
        assertEquals(AetherScanMode.IRONCLAD, config.scanMode)
    }

    @Test
    fun `invalid stored values fall back to defaults`() {
        val prefs = prefsWith(protocol = "gool", scanMode = "deep")
        val config = configFrom(prefs)
        // gool/deep are valid upstream aliases but not in the shipped UI set;
        // the mapping falls back to the safe defaults.
        assertEquals(AetherProtocol.MASQUE, config.protocol)
        assertEquals(AetherScanMode.BALANCED, config.scanMode)
    }

    @Test
    fun `enabled flag round-trips`() {
        val prefs = prefsWith(enabled = true)
        assertTrue(prefs.getBoolean("aether_enabled", false))
        prefs.edit().putBoolean("aether_enabled", false).commit()
        assertFalse(prefs.getBoolean("aether_enabled", true))
    }

    @Test
    fun `enabled flag default is false`() {
        val prefs = FakeSharedPreferences()
        assertFalse(prefs.getBoolean("aether_enabled", false))
    }
}

/**
 * End-to-end preference -> config -> FFI-payload mapping (Phase 7, task 40):
 * verifies that the persisted values produce exactly the JSON the native side
 * expects when the fragment calls start/updateConfiguration.
 */
class AetherPreferenceToPayloadTest {

    @Test
    fun `stored masque and stealth map to the scan payload`() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("aether_protocol", "masque")
            .putString("aether_scan_mode", "stealth")
            .commit()

        val config = AetherConfig(
            AetherProtocol.from(prefs.getString("aether_protocol", null)),
            AetherScanMode.from(prefs.getString("aether_scan_mode", null))
        )
        val payload = scanPayload(config.protocol, config.scanMode)
        assertEquals("masque", payload["transport"]?.jsonPrimitive?.content)
        assertEquals("stealth", payload["mode"]?.jsonPrimitive?.content)
    }
}
