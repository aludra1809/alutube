package org.schabi.newpipe.aether

/**
 * User-facing Aether configuration. Only the protocol and scan mode are
 * exposed; every other Aether option stays internal (defaults).
 */
data class AetherConfig(
    val protocol: AetherProtocol = AetherProtocol.MASQUE,
    val scanMode: AetherScanMode = AetherScanMode.BALANCED
)

/** Protocol selection, mapped to the exact Aether `Transport` value. */
enum class AetherProtocol(val value: String) {
    MASQUE("masque"),
    WIREGUARD("wg");

    companion object {
        fun from(value: String?): AetherProtocol {
            if (value == null) return MASQUE
            val v = value.trim()
            return entries.firstOrNull {
                it.value.equals(v, ignoreCase = true) || it.name.equals(v, ignoreCase = true)
            } ?: MASQUE
        }
    }
}

/** Scan-mode selection, mapped to the exact Aether probe mode value. */
enum class AetherScanMode(val value: String) {
    TURBO("turbo"),
    BALANCED("balanced"),
    THOROUGH("thorough"),
    STEALTH("stealth"),
    IRONCLAD("ironclad");

    companion object {
        fun from(value: String?): AetherScanMode {
            if (value == null) return BALANCED
            val v = value.trim()
            return entries.firstOrNull {
                it.value.equals(v, ignoreCase = true) || it.name.equals(v, ignoreCase = true)
            } ?: BALANCED
        }
    }
}
