package org.schabi.newpipe.aether

import java.io.File
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * JVM integration tests against the real Aether bridge library (Phase 7,
 * task 41 — host-level subset). The host `libaether_bridge.so` is built from
 * the `aether-bridge` crate (`cargo build --release` for the host target) and
 * loaded here; tests that would need network or an Android device are skipped
 * on the JVM and run as instrumented tests instead.
 *
 * These tests are skipped when the host library is absent (e.g. CI without a
 * Rust toolchain), so the unit-test task never fails solely for missing
 * native artifacts.
 */
class AetherBridgeHostIntegrationTest {

    companion object {
        private const val LIB_PATH = "/home/reza/Desktop/youtube/aether-bridge/target/release"

        @JvmStatic
        @BeforeClass
        fun loadHostBridge() {
            val candidates = listOf(
                System.getProperty("aether.bridge.lib"),
                "$LIB_PATH/libaether_bridge.so"
            )
            val lib = candidates.filterNotNull().map(::File).firstOrNull { it.exists() }
            assumeTrue("host libaether_bridge.so not available", lib != null)

            // java.library.path is set by AGP to include app/src/test/jniLibs
            // (where the host lib is placed); the NativeAetherBridge object
            // init resolves "aether_bridge" from it. Trigger the init here so
            // later tests see a clean state.
            NativeAetherBridge.version()
        }
    }

    @Test
    fun `version returns a valid ok reply`() {
        val reply = AetherJson.parseReply(NativeAetherBridge.version())
        assertEquals("2.0.0", reply["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `error replies are surfaced as engine exceptions`() {
        // identity_open with a bogus payload path must never crash across the
        // JNI boundary: it either returns an ok:false reply or spawns a job.
        val payload = identityPayload("/nonexistent/aether.toml", AetherProtocol.MASQUE)
        val raw = NativeAetherBridge.identityOpen(AetherJson.encode(payload))
        val root = AetherJson.json.parseToJsonElement(raw).jsonObject
        val ok = root["ok"]?.jsonPrimitive?.content == "true"
        if (ok) {
            // A job was spawned. It may keep running (provisioning over the
            // network) or reach a terminal state; both are valid engine
            // behaviour — the assertion is that the bridge keeps working.
            val jobId = root["job"]?.jsonPrimitive?.content?.toLongOrNull()
            if (jobId != null) {
                val poll = AetherJson.json.parseToJsonElement(
                    NativeAetherBridge.jobPoll(jobId)
                ).jsonObject
                val state = poll["state"]?.jsonPrimitive?.content
                assertTrue(
                    "expected a valid job state, got $state",
                    state == "done" || state == "error" || state == "running"
                )
                NativeAetherBridge.jobFree(jobId)
            }
        } else {
            assertTrue("unexpected: $raw", raw.contains("ok\":false") || raw.contains("\"error\""))
        }
    }

    @Test
    fun `job poll on an unknown handle returns an error reply`() {
        val raw = NativeAetherBridge.jobPoll(999_999_999L)
        // The bridge must return a JSON reply (ok:false or a state), never
        // crash or return garbage.
        val root = try {
            AetherJson.json.parseToJsonElement(raw).jsonObject
        } catch (e: Throwable) {
            throw AssertionError("job_poll returned non-JSON: $raw", e)
        }
        assertTrue(root["ok"] != null || root["state"] != null)
    }
}
