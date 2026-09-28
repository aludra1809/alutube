package org.schabi.newpipe.settings

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.schabi.newpipe.R
import org.schabi.newpipe.aether.AetherConfig
import org.schabi.newpipe.aether.AetherConnectionManager
import org.schabi.newpipe.aether.AetherProtocol
import org.schabi.newpipe.aether.AetherScanMode
import org.schabi.newpipe.aether.AetherState

/**
 * Alutube connection settings: enable/disable, protocol, scan mode, and a
 * live status row. Changing any option while the tunnel is running restarts
 * it (the FFI tunnel is created per connect call).
 */
class AetherSettingsFragment : BasePreferenceFragment() {

    private lateinit var enabledPreference: SwitchPreferenceCompat
    private lateinit var protocolPreference: ListPreference
    private lateinit var scanModePreference: ListPreference
    private lateinit var statusPreference: Preference

    private var statusJob: Job? = null

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResourceRegistry()

        enabledPreference = requirePreference(R.string.aether_enabled_key)
        protocolPreference = requirePreference(R.string.aether_protocol_key)
        scanModePreference = requirePreference(R.string.aether_scan_mode_key)
        statusPreference = requirePreference(R.string.aether_status_title)

        val controller = AetherConnectionManager.get(requireContext())

        enabledPreference.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            if (enabled) {
                controller.start(currentConfig())
            } else {
                controller.stop()
            }
            true
        }

        protocolPreference.setOnPreferenceChangeListener { _, newValue ->
            val selected = newValue?.toString()
            protocolPreference.summary = selected ?: ""
            if (enabledPreference.isChecked) {
                controller.updateConfiguration(currentConfig())
            }
            true
        }

        scanModePreference.setOnPreferenceChangeListener { _, newValue ->
            val selected = newValue?.toString()
            scanModePreference.summary = selected ?: ""
            if (enabledPreference.isChecked) {
                controller.updateConfiguration(currentConfig())
            }
            true
        }

        // Show the initial summaries.
        protocolPreference.summary = defaultPreferences
            .getString(getString(R.string.aether_protocol_key), null)
        scanModePreference.summary = defaultPreferences
            .getString(getString(R.string.aether_scan_mode_key), null)

        // Live status row: collect the controller state while the screen is
        // visible. A dedicated coroutine scope is started on ON_START and
        // cancelled on ON_STOP, so no lifecycle-ktx dependency is needed.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> startStatusCollection(controller)
                    Lifecycle.Event.ON_STOP -> stopStatusCollection()
                    else -> Unit
                }
            }
        )
    }

    private fun startStatusCollection(controller: org.schabi.newpipe.aether.AetherNetworkController) {
        if (statusJob?.isActive == true) return
        val scope = CoroutineScope(Dispatchers.Main + Job())
        statusJob = scope.launch {
            controller.state.collect { state ->
                statusPreference.summary = getString(statusTextRes(state))
            }
        }
    }

    private fun stopStatusCollection() {
        statusJob?.cancel()
        statusJob = null
    }

    private fun currentConfig(): AetherConfig = AetherConfig(
        protocol = AetherProtocol.from(
            defaultPreferences.getString(getString(R.string.aether_protocol_key), null)
        ),
        scanMode = AetherScanMode.from(
            defaultPreferences.getString(getString(R.string.aether_scan_mode_key), null)
        )
    )

    private fun statusTextRes(state: AetherState): Int = when (state) {
        AetherState.DISABLED -> R.string.aether_status_disabled
        AetherState.STARTING -> R.string.aether_status_starting
        AetherState.SCANNING -> R.string.aether_status_scanning
        AetherState.CONNECTING -> R.string.aether_status_connecting
        AetherState.RUNNING -> R.string.aether_status_running
        AetherState.RECONNECTING -> R.string.aether_status_reconnecting
        AetherState.STOPPED -> R.string.aether_status_stopped
        AetherState.ERROR -> R.string.aether_status_error
    }
}
