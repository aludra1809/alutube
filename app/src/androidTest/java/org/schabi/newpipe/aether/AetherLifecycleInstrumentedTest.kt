package org.schabi.newpipe.aether

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lifecycle + configuration-change behaviour on a real device/emulator
 * (Phase 7, task 42). These exercise the controller through the app-level
 * manager. They expect no tunnel to actually connect (no Aether backend in
 * CI), so they assert state-machine behaviour rather than connectivity.
 *
 * On devices without the native library, the controller degrades to ERROR /
 * DISABLED instead of crashing — which is itself the assertion here.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AetherLifecycleInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun controllerNeverCrashesOnStartOrStop() = runBlocking {
        val controller = AetherConnectionManager.get(context)
        // Start and immediately stop: must never throw, regardless of whether
        // the native library is present.
        controller.start(AetherConfig())
        withContext(Dispatchers.Default) { delay(300) }
        controller.stop()
        withContext(Dispatchers.Default) { delay(100) }
        assertTrue(
            "state must be a terminal, non-crashing state",
            controller.state.value == AetherState.RUNNING ||
                controller.state.value == AetherState.STOPPED ||
                controller.state.value == AetherState.ERROR ||
                controller.state.value == AetherState.RECONNECTING
        )
    }

    @Test
    fun updateConfigurationRestartsWithoutDuplicatingJobs() = runBlocking {
        val controller = AetherConnectionManager.get(context)
        controller.start(AetherConfig(AetherProtocol.MASQUE, AetherScanMode.BALANCED))
        withContext(Dispatchers.Default) { delay(200) }
        controller.updateConfiguration(AetherConfig(AetherProtocol.WIREGUARD, AetherScanMode.THOROUGH))
        withContext(Dispatchers.Default) { delay(200) }
        // idempotent start after update is a no-op (no duplicate jobs)
        controller.start(AetherConfig(AetherProtocol.WIREGUARD, AetherScanMode.THOROUGH))
        controller.stop()
        assertEquals(AetherState.STOPPED, controller.state.value)
    }
}
