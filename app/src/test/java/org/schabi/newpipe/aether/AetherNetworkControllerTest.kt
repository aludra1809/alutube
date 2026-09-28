package org.schabi.newpipe.aether

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Controller tests with a scripted fake bridge. The Aether engine is not
 * touched; the JSON payloads / states are produced by [FakeAetherBridge].
 * The controller runs on the test scheduler so time advances deterministically.
 */
class AetherNetworkControllerTest {

    private fun contextWithFilesDir(tmpDir: File): Context {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tmpDir)
        return context
    }

    private class FakeAetherBridge : AetherBridge {
        val identityOpenPayloads = mutableListOf<String>()
        val scanPayloads = mutableListOf<String>()
        val tunnelPayloads = mutableListOf<String>()
        var verifyReachable = true
        var identityJobId = 1L
        var scanJobId = 2L
        var tunnelJobId = 3L
        var tunnelDoneWith: String? = null // e.g. "closed" to simulate a drop
        var version = """{"ok":true,"version":"2.0.0"}"""

        override fun version(): String = version

        override fun identityOpen(payload: String): String {
            identityOpenPayloads += payload
            return """{"ok":true,"job":$identityJobId}"""
        }

        override fun identitySummary(identity: Long): String = """{"ok":true,"device_id":"dev-1"}"""

        override fun identityFree(identity: Long): String = """{"ok":true,"freed":$identity}"""

        override fun scanStart(identity: Long, payload: String): String {
            scanPayloads += payload
            return """{"ok":true,"job":$scanJobId}"""
        }

        override fun verifyStart(identity: Long, payload: String): String = """{"ok":true,"job":99}"""

        override fun tunnelStart(identity: Long, payload: String): String {
            tunnelPayloads += payload
            return """{"ok":true,"job":$tunnelJobId}"""
        }

        override fun coreStart(args: String): String = """{"ok":true,"job":200}"""

        override fun jobPoll(job: Long): String = when (job) {
            identityJobId -> """{"ok":true,"state":"done","result":{"identity":7,"path":"/a/aether.toml","lastconn_path":"/a/aether-lastconn.toml"}}"""

            scanJobId -> """{"ok":true,"state":"done","result":{"endpoint":{"ip":"1.2.3.4","port":443,"rtt_ms":50}}}"""

            verifyJobId -> """{"ok":true,"state":"done","result":{"reachable":$verifyReachable}}"""

            tunnelJobId -> if (tunnelDoneWith == null) {
                """{"ok":true,"state":"running"}"""
            } else {
                """{"ok":true,"state":"done","result":{"state":"$tunnelDoneWith"}}"""
            }

            else -> """{"ok":true,"state":"done","result":{}}"""
        }

        override fun jobCancel(job: Long): String = """{"ok":true,"cancelled":$job}"""

        override fun jobFree(job: Long): String = """{"ok":true,"freed":$job}"""

        private val verifyJobId = 99L
    }

/** Creates a controller bound to the test scheduler. */
    private fun TestScope.controllerWith(tmpDir: File, bridge: FakeAetherBridge, router: AetherTrafficRouter = FakeRouter()): Pair<AetherNetworkController, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val controller = AetherNetworkController(
            contextWithFilesDir(tmpDir),
            bridge = bridge,
            scope = scope,
            router = router
        )
        return controller to scope
    }

    /** Records routing calls so tests can assert proxy wiring. */
    private class FakeRouter : AetherTrafficRouter {
        var routedThrough: Pair<String, Int>? = null
        var directCalls = 0

        override fun routeThrough(host: String, port: Int) {
            routedThrough = host to port
        }

        override fun routeDirect() {
            directCalls++
        }
    }

    /** Advances virtual time until [condition] is true (max ~10 s virtual). */
    private suspend fun TestScope.until(condition: () -> Boolean) {
        repeat(400) {
            if (condition()) return
            advanceTimeBy(50)
            runCurrent()
        }
    }

    @Test
    fun `start transitions to RUNNING and sends expected payloads`() = runTest {
        val tmp = kotlin.io.path.createTempDirectory("aether-ctrl").toFile()
        val bridge = FakeAetherBridge()
        val router = FakeRouter()
        val (controller, scope) = controllerWith(tmp, bridge, router)

        controller.start(AetherConfig(AetherProtocol.MASQUE, AetherScanMode.THOROUGH))
        until { controller.state.value == AetherState.RUNNING }

        assertEquals(AetherState.RUNNING, controller.state.value)
        assertTrue(controller.isRunning)
        assertEquals("1.2.3.4:443", controller.getEndpoint())

        val identityPayload = bridge.identityOpenPayloads.single()
        assertTrue(identityPayload.contains("aether.toml"))
        assertTrue(identityPayload.contains("masque"))

        val scanPayload = bridge.scanPayloads.single()
        assertTrue(scanPayload.contains("thorough"))
        assertTrue(scanPayload.contains("masque"))

        val tunnelPayload = bridge.tunnelPayloads.single()
        assertTrue(tunnelPayload.contains("1.2.3.4:443"))
        assertTrue(tunnelPayload.contains("127.0.0.1:1819"))

        // Traffic routing must be applied on RUNNING: loopback Aether listener.
        assertEquals("127.0.0.1" to 1819, router.routedThrough)

        controller.stop()
        assertTrue(router.directCalls > 0)
        scope.cancel()
        tmp.deleteRecursively()
    }

    @Test
    fun `stop sets STOPPED and releases resources`() = runTest {
        val tmp = kotlin.io.path.createTempDirectory("aether-ctrl").toFile()
        val bridge = FakeAetherBridge()
        val (controller, scope) = controllerWith(tmp, bridge)

        controller.start(AetherConfig())
        until { controller.state.value == AetherState.RUNNING }
        controller.stop()

        assertEquals(AetherState.STOPPED, controller.state.value)
        assertFalse(controller.isRunning)
        scope.cancel()
        tmp.deleteRecursively()
    }

    @Test
    fun `start is idempotent while running`() = runTest {
        val tmp = kotlin.io.path.createTempDirectory("aether-ctrl").toFile()
        val bridge = FakeAetherBridge()
        val (controller, scope) = controllerWith(tmp, bridge)

        controller.start(AetherConfig())
        controller.start(AetherConfig()) // no-op
        until { controller.state.value == AetherState.RUNNING }

        assertEquals(AetherState.RUNNING, controller.state.value)
        scope.cancel()
        tmp.deleteRecursively()
    }

    @Test
    fun `reconnects when the tunnel drops`() = runTest {
        val tmp = kotlin.io.path.createTempDirectory("aether-ctrl").toFile()
        val bridge = FakeAetherBridge()
        val (controller, scope) = controllerWith(tmp, bridge)

        controller.start(AetherConfig())
        until { controller.state.value == AetherState.RUNNING }

        // Simulate the tunnel dropping.
        bridge.tunnelDoneWith = "closed"
        until { controller.state.value == AetherState.RECONNECTING }

        assertEquals(AetherState.RECONNECTING, controller.state.value)
        scope.cancel()
        tmp.deleteRecursively()
    }
}
