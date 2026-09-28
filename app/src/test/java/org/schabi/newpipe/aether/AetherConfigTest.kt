package org.schabi.newpipe.aether

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Aether configuration mapping and FFI JSON marshalling.
 * These run on the JVM (no native library required).
 */
class AetherConfigTest {

    @Test
    fun `protocol maps to exact Aether values`() {
        assertEquals("masque", AetherProtocol.MASQUE.value)
        assertEquals("wg", AetherProtocol.WIREGUARD.value)
    }

    @Test
    fun `protocol from unknown value falls back to MASQUE`() {
        assertEquals(AetherProtocol.MASQUE, AetherProtocol.from("gool"))
        assertEquals(AetherProtocol.MASQUE, AetherProtocol.from(null))
        assertEquals(AetherProtocol.WIREGUARD, AetherProtocol.from("wg"))
        assertEquals(AetherProtocol.WIREGUARD, AetherProtocol.from("WIREGUARD"))
        assertEquals(AetherProtocol.MASQUE, AetherProtocol.from("masque"))
    }

    @Test
    fun `scan modes map to exact Aether values`() {
        assertEquals("turbo", AetherScanMode.TURBO.value)
        assertEquals("balanced", AetherScanMode.BALANCED.value)
        assertEquals("thorough", AetherScanMode.THOROUGH.value)
        assertEquals("stealth", AetherScanMode.STEALTH.value)
        assertEquals("ironclad", AetherScanMode.IRONCLAD.value)
    }

    @Test
    fun `scan mode from unknown value falls back to BALANCED`() {
        assertEquals(AetherScanMode.BALANCED, AetherScanMode.from("deep"))
        assertEquals(AetherScanMode.BALANCED, AetherScanMode.from(null))
        assertEquals(AetherScanMode.THOROUGH, AetherScanMode.from("thorough"))
        assertEquals(AetherScanMode.IRONCLAD, AetherScanMode.from("IRONCLAD"))
    }

    @Test
    fun `default config uses MASQUE and BALANCED`() {
        val config = AetherConfig()
        assertEquals(AetherProtocol.MASQUE, config.protocol)
        assertEquals(AetherScanMode.BALANCED, config.scanMode)
    }
}

class AetherJsonTest {

    @Test
    fun `parseReply accepts ok replies`() {
        val reply = AetherJson.parseReply("""{"ok":true,"job":42}""")
        assertEquals(42L, reply["job"]!!.jsonPrimitive.long)
    }

    @Test(expected = AetherEngineException::class)
    fun `parseReply throws on error replies`() {
        AetherJson.parseReply("""{"ok":false,"error":"boom"}""")
    }

    @Test
    fun `jobIdOf extracts job id`() {
        val reply = AetherJson.parseReply("""{"ok":true,"job":7}""")
        assertEquals(7L, AetherJson.jobIdOf(reply))
    }

    @Test
    fun `jobResult returns null while running and result when done`() {
        assertNull(AetherJson.jobResult("""{"ok":true,"state":"running"}"""))
        val done = AetherJson.jobResult(
            """{"ok":true,"state":"done","result":{"identity":3}}"""
        )
        assertEquals(3L, done?.get("identity")?.jsonPrimitive?.long)
    }

    @Test
    fun `identity payload carries path and transport`() {
        val payload = identityPayload("/data/aether.toml", AetherProtocol.WIREGUARD)
        assertEquals("/data/aether.toml", payload["path"]!!.jsonPrimitive.content)
        assertEquals("wg", payload["transport"]!!.jsonPrimitive.content)
    }

    @Test
    fun `scan payload carries transport and mode`() {
        val payload = scanPayload(AetherProtocol.MASQUE, AetherScanMode.STEALTH)
        assertEquals("masque", payload["transport"]!!.jsonPrimitive.content)
        assertEquals("stealth", payload["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tunnel payload carries peer socks and optional http`() {
        val withHttp = tunnelPayload(
            AetherProtocol.MASQUE,
            "1.2.3.4:4567",
            "127.0.0.1:1819",
            "127.0.0.1:1820"
        )
        assertEquals("1.2.3.4:4567", withHttp["peer"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1:1819", withHttp["socks"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1:1820", withHttp["http"]!!.jsonPrimitive.content)

        val withoutHttp = tunnelPayload(AetherProtocol.WIREGUARD, "5.6.7.8:9999", "127.0.0.1:1819")
        assertNull(withoutHttp["http"])
        assertEquals("wg", withoutHttp["transport"]!!.jsonPrimitive.content)
    }

    @Test
    fun `verify payload carries peer socks transport`() {
        val payload = verifyPayload(AetherProtocol.MASQUE, "1.2.3.4:4567", "127.0.0.1:1819")
        assertEquals("1.2.3.4:4567", payload["peer"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1:1819", payload["socks"]!!.jsonPrimitive.content)
    }
}
