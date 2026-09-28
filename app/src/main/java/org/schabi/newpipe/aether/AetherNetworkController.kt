package org.schabi.newpipe.aether

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * High-level controller for the embedded Aether engine.
 *
 * Responsibilities:
 *  - load-or-provision the Aether identity (persisted in app-private storage),
 *  - scan for a usable endpoint and optionally verify the cached one,
 *  - start/stop the tunnel (SOCKS5 + optional HTTP CONNECT listeners),
 *  - reconnect with bounded backoff when the tunnel drops,
 *  - expose a coarse [AetherState] flow and user-presentable [AetherError]s.
 *
 * This class never touches JNI/Rust types directly; all FFI traffic goes
 * through an injected [AetherBridge] (native or fake for tests).
 */
class AetherNetworkController(
    private val context: Context,
    private val bridge: AetherBridge = NativeAetherBridge,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    // Loopback listeners bound by the Aether tunnel inside this process.
    // Fixed ports keep the integration stable; bind is loopback-only.
    internal val socksPort = 1819
    internal val httpPort = 1820

    private val _state = MutableStateFlow(AetherState.DISABLED)
    val state: StateFlow<AetherState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<AetherError?>(null)
    val lastError: StateFlow<AetherError?> = _lastError.asStateFlow()

    private var identityHandle: Long = 0L
    private var tunnelJob: Long = 0L
    private var currentEndpoint: String? = null
    private var lastconnPath: String? = null
    private var engineJob: Job? = null

    private val identityFile: File
        get() = File(context.filesDir, "aether/aether.toml")

    init {
        identityFile.parentFile?.mkdirs()
    }

    val isRunning: Boolean
        get() = _state.value == AetherState.RUNNING

    // ------------------------------------------------------------------ API

    /**
     * Start the engine with the given configuration. Idempotent: calling
     * while running is a no-op; call [stop] first to reconfigure via
     * [updateConfiguration].
     */
    fun start(config: AetherConfig) {
        if (engineJob?.isActive == true) return
        _lastError.value = null
        engineJob = scope.launch { runEngine(config) }
    }

    /** Stop the engine and release native resources. */
    fun stop() {
        engineJob?.cancel()
        engineJob = null
        releaseResources()
        _state.value = AetherState.STOPPED
    }

    /**
     * Apply a new configuration. Protocol/scan-mode changes require a tunnel
     * restart (the FFI tunnel is created per connect() call); so this stops
     * and restarts the engine.
     */
    fun updateConfiguration(config: AetherConfig) {
        stop()
        start(config)
    }

    fun getEndpoint(): String? = currentEndpoint

    // ------------------------------------------------------------- internals

    private suspend fun runEngine(config: AetherConfig) {
        _state.value = AetherState.STARTING
        try {
            bridge.version() // sanity check; throws UnsatisfiedLinkError if broken
            identityHandle = openIdentity(config.protocol)
            _state.value = AetherState.SCANNING

            while (scope.isActive) {
                val endpoint = findEndpoint(config)
                currentEndpoint = endpoint
                lastconnPath?.let { persistLastEndpoint(endpoint) }

                _state.value = AetherState.CONNECTING
                tunnelJob = startTunnel(config, endpoint)
                _state.value = AetherState.RUNNING

                val dropped = awaitTunnelTermination()
                if (!dropped) {
                    // stopped deliberately by [stop]
                    break
                }
                _state.value = AetherState.RECONNECTING
                _lastError.value = AetherError.ConnectionLost
                reconnectBackoff()
            }
        } catch (e: AetherEngineException) {
            _lastError.value = AetherError.EngineFailure(
                "Alutube connection failed: ${e.message ?: "unknown engine error"}"
            )
            _state.value = AetherState.ERROR
        } catch (e: UnsatisfiedLinkError) {
            _lastError.value = AetherError.NativeLibraryUnavailable
            _state.value = AetherState.ERROR
        } catch (e: Throwable) {
            _lastError.value = AetherError.Unknown(
                "Alutube connection failed: ${e.message ?: "unexpected error"}"
            )
            _state.value = AetherState.ERROR
        }
    }

    /** Opens (loads or provisions) the identity, blocking until the job ends. */
    private suspend fun openIdentity(protocol: AetherProtocol): Long {
        val payload = identityPayload(identityFile.absolutePath, protocol)
        val jobId = AetherJson.jobIdOf(
            AetherJson.parseReply(bridge.identityOpen(AetherJson.encode(payload)))
        )
        val result = awaitJob(jobId) ?: JsonObject(emptyMap())

        val identity = result["identity"]?.jsonPrimitive?.long
            ?: throw AetherEngineException("identity provisioning returned no handle")
        lastconnPath = result["lastconn_path"]?.jsonPrimitive?.contentOrNull
        return identity
    }

    /**
     * Scans for an endpoint. If a cached endpoint exists it is verified
     * first (quick reconnect path); otherwise a full scan runs.
     */
    private suspend fun findEndpoint(config: AetherConfig): String {
        val cached = currentEndpoint ?: readLastEndpoint()
        if (cached != null) {
            val verified = verifyEndpoint(config, cached)
            if (verified) return cached
        }
        return scanEndpoint(config)
    }

    private suspend fun scanEndpoint(config: AetherConfig): String {
        val payload = scanPayload(config.protocol, config.scanMode)
        val jobId = AetherJson.jobIdOf(
            AetherJson.parseReply(
                bridge.scanStart(identityHandle, AetherJson.encode(payload))
            )
        )
        val result = awaitJob(jobId)
            ?: throw AetherEngineException("endpoint scan returned no result")
        val endpoint = result["endpoint"]?.jsonObject ?: throw AetherError.NoEndpointFound

        val ip = endpoint["ip"]?.jsonPrimitive?.contentOrNull
            ?: throw AetherError.NoEndpointFound
        val port = endpoint["port"]?.jsonPrimitive?.long
            ?: throw AetherError.NoEndpointFound
        return "$ip:$port"
    }

    private suspend fun verifyEndpoint(config: AetherConfig, peer: String): Boolean {
        return try {
            val payload = verifyPayload(config.protocol, peer, "127.0.0.1:$socksPort")
            val jobId = AetherJson.jobIdOf(
                AetherJson.parseReply(
                    bridge.verifyStart(identityHandle, AetherJson.encode(payload))
                )
            )
            val result = awaitJob(jobId)
            result?.get("reachable")?.jsonPrimitive?.content == "true"
        } catch (e: Throwable) {
            false // stale cached endpoint; fall through to full scan
        }
    }

    private suspend fun startTunnel(config: AetherConfig, peer: String): Long {
        val payload = tunnelPayload(
            transport = config.protocol,
            peer = peer,
            socks = "127.0.0.1:$socksPort",
            http = "127.0.0.1:$httpPort"
        )
        return AetherJson.jobIdOf(
            AetherJson.parseReply(
                bridge.tunnelStart(identityHandle, AetherJson.encode(payload))
            )
        )
    }

    /**
     * Waits for the tunnel job to terminate. Returns `true` if it dropped on
     * its own (reconnect needed), `false` if it was cancelled (deliberate).
     */
    private suspend fun awaitTunnelTermination(): Boolean {
        while (scope.isActive) {
            val raw = bridge.jobPoll(tunnelJob)
            val root = try {
                AetherJson.json.parseToJsonElement(raw).jsonObject
            } catch (e: Throwable) {
                return true // unreadable reply → treat as dropped
            }
            when (root["state"]?.jsonPrimitive?.content) {
                "done" -> {
                    val result = root["result"]?.jsonObject
                    val ended = result?.get("state")?.jsonPrimitive?.content
                    // "stopped" = cancelled by us; anything else = dropped
                    return ended != "stopped"
                }

                else -> delay(500)
            }
        }
        return false
    }

    private suspend fun awaitJob(job: Long): JsonObject? {
        while (scope.isActive) {
            val raw = bridge.jobPoll(job)
            val root = try {
                AetherJson.json.parseToJsonElement(raw).jsonObject
            } catch (e: Throwable) {
                return null
            }
            when (root["state"]?.jsonPrimitive?.content) {
                "done" -> {
                    bridge.jobFree(job)
                    return root["result"]?.jsonObject
                }

                "running" -> delay(250)

                else -> {
                    bridge.jobFree(job)
                    return null
                }
            }
        }
        return null
    }

    private suspend fun reconnectBackoff() {
        delay(1_000)
        delay(1_000)
        delay(3_000)
    }

    private fun releaseResources() {
        if (tunnelJob != 0L) {
            runCatching { bridge.jobCancel(tunnelJob) }
            runCatching { bridge.jobFree(tunnelJob) }
            tunnelJob = 0L
        }
        if (identityHandle != 0L) {
            runCatching { bridge.identityFree(identityHandle) }
            identityHandle = 0L
        }
    }

    private fun persistLastEndpoint(endpoint: String) {
        val lastconn = lastconnPath?.let(::File) ?: return
        runCatching {
            lastconn.parentFile?.mkdirs()
            lastconn.writeText("peer = \"$endpoint\"\n")
        }
    }

    private fun readLastEndpoint(): String? {
        val lastconn = lastconnPath?.let(::File) ?: return null
        return runCatching {
            val text = lastconn.readText()
            val marker = "peer = \""
            val start = text.indexOf(marker)
            if (start < 0) {
                null
            } else {
                val from = start + marker.length
                val to = text.indexOf('"', from)
                if (to < 0) null else text.substring(from, to)
            }
        }.getOrNull()
    }
}
