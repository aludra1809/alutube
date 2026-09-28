package org.schabi.newpipe.aether

/**
 * Lifecycle/state of the embedded Aether network controller.
 *
 * The state is deliberately coarse: business logic (player, services,
 * settings) only reacts to these states and never sees Rust/JNI internals.
 */
enum class AetherState {
    /** Aether is disabled (user preference off) or not initialized. */
    DISABLED,

    /** Controller is initializing (loading native lib, reading identity). */
    STARTING,

    /** Identity is provisioned; scanning for a usable endpoint. */
    SCANNING,

    /** A tunnel endpoint was found; establishing the tunnel. */
    CONNECTING,

    /** Tunnel is up; SOCKS/HTTP listeners bound and serving. */
    RUNNING,

    /** Tunnel dropped; controller is retrying with the cached endpoint. */
    RECONNECTING,

    /** Stopped on request; native resources released. */
    STOPPED,

    /** An unrecoverable error occurred; see [AetherError]. */
    ERROR
}

/**
 * Errors surfaced to the UI. `message` is always a user-presentable,
 * Alutube-branded string; the raw Rust/JNI error text never leaks. Extends
 * [Exception] so the controller can `throw`/catch these directly.
 */
sealed class AetherError(
    open val userMessage: String
) : Exception(userMessage) {
    /** Native library missing / cannot be loaded (ABI mismatch, etc.). */
    data object NativeLibraryUnavailable : AetherError("Alutube connection is unavailable on this device.")

    /** Aether engine rejected the operation (provisioning, scan, tunnel). */
    data class EngineFailure(override val userMessage: String) : AetherError(userMessage)

    /** The selected protocol is not available in this build. */
    data object ProtocolUnavailable :
        AetherError("The selected Alutube protocol is not available on this device.")

    /** Scanning found no usable endpoint. */
    data object NoEndpointFound :
        AetherError("No Alutube endpoint was found. Check your network and try again.")

    /** The tunnel dropped and reconnection is in progress. */
    data object ConnectionLost :
        AetherError("The Alutube connection was lost. Reconnecting…")

    /** The device has no usable network connectivity. */
    data object NoNetwork :
        AetherError("No network connection. The Alutube connection will resume automatically.")

    /** Invalid configuration was supplied. */
    data object InvalidConfiguration :
        AetherError("The Alutube connection settings are invalid.")

    /** A generic unexpected failure. */
    data class Unknown(override val userMessage: String) : AetherError(userMessage)
}
