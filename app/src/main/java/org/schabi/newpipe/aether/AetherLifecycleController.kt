package org.schabi.newpipe.aether

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R

/**
 * App-level lifecycle integration for the Aether network controller.
 *
 * Responsibilities (Phase 5):
 *  - start on app launch when the "Enable Alutube connection" preference is
 *    on (task 30);
 *  - observe foreground/background via [ProcessLifecycleOwner] — the tunnel
 *    keeps running in the background (process alive), no foreground service
 *    in v1 (playback is already covered by the player's own foreground
 *    service) (task 31);
 *  - reconnect when connectivity changes (Wi-Fi <-> mobile, offline)
 *    (task 32);
 *  - survive process death/recreation: identity is persisted on disk, the
 *    controller re-provisions only when needed and guards against duplicate
 *    jobs (task 33);
 *  - surface user-visible failure states via the controller's [AetherState]
 *    (task 34);
 *  - log hygiene: never log tokens/keys; native log level only from debug
 *    builds (task 35).
 */
class AetherLifecycleController(private val appContext: Context) : DefaultLifecycleObserver {

    private val controller by lazy {
        AetherConnectionManager.get(appContext)
    }

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallbackRegistered = false

    /**
     * Called from `App.onCreate`. Starts the engine if the user preference is
     * enabled and registers the process-lifecycle + connectivity observers.
     */
    fun initialize() {
        // Native library load failure must not crash the app: it is caught
        // inside the controller and surfaced as AetherState.ERROR. Verify the
        // load here so a broken ABI produces a clean disabled state.
        runCatching { NativeAetherBridge.verifyLoaded() }
            .onFailure {
                Log.w(TAG, "Aether native library unavailable: ${it.javaClass.simpleName}")
                return
            }

        if (isEnabled()) {
            controller.start(currentConfig())
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registerNetworkCallback()
    }

    /** Called from [AetherConnectionManager.shutdown] on app termination. */
    fun shutdown() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        unregisterNetworkCallback()
        controller.stop()
    }

    // ----------------------------------------------------------- lifecycle

    override fun onStart(owner: LifecycleOwner) {
        if (isEnabled()) {
            // If the engine dropped while backgrounded, the controller's own
            // reconnect loop already retries; this is a safety net that
            // ensures the state is re-evaluated when the user returns.
            if (controller.state.value == AetherState.ERROR) {
                controller.updateConfiguration(currentConfig())
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // No action: the tunnel keeps running while the process is alive.
        // Playback continues via the player's foreground service; killing the
        // process stops the tunnel, and identity persists on disk so the next
        // launch reconnects (task 31/33).
    }

    // ------------------------------------------------------- connectivity

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available")
            if (isEnabled() && controller.state.value == AetherState.ERROR) {
                controller.updateConfiguration(currentConfig())
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost — waiting for Aether reconnect loop")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val hasInternet = networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
            if (hasInternet && isEnabled() && controller.state.value == AetherState.ERROR) {
                controller.updateConfiguration(currentConfig())
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, networkCallback) }
            .onSuccess { networkCallbackRegistered = true }
            .onFailure { Log.w(TAG, "Could not register network callback", it) }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        networkCallbackRegistered = false
    }

    // ------------------------------------------------------------- helpers

    private fun isEnabled(): Boolean = PreferenceManager.getDefaultSharedPreferences(appContext)
        .getBoolean(appContext.getString(R.string.aether_enabled_key), false)

    private fun currentConfig(): AetherConfig = AetherConfig(
        protocol = AetherProtocol.from(
            PreferenceManager.getDefaultSharedPreferences(appContext)
                .getString(appContext.getString(R.string.aether_protocol_key), null)
        ),
        scanMode = AetherScanMode.from(
            PreferenceManager.getDefaultSharedPreferences(appContext)
                .getString(appContext.getString(R.string.aether_scan_mode_key), null)
        )
    )

    companion object {
        private const val TAG = "AetherLifecycle"
    }
}
