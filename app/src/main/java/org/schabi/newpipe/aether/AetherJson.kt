package org.schabi.newpipe.aether

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * JSON marshalling for the Aether FFI boundary. All native replies are
 * `{"ok":true,...}` or `{"ok":false,"error":"..."}`; payloads are built as
 * plain JsonObjects and serialized to strings. Uses the reflective Json
 * runtime only — no serialization plugin required.
 */
object AetherJson {
    val json = Json { ignoreUnknownKeys = true }

    /** Parses a native reply; throws [AetherEngineException] when `ok` is false. */
    fun parseReply(raw: String): JsonObject {
        val root = json.parseToJsonElement(raw).jsonObject
        val ok = root["ok"]?.jsonPrimitive?.boolean ?: false
        if (!ok) {
            val message = root["error"]?.jsonPrimitive?.contentOrNull
                ?: "unknown native error"
            throw AetherEngineException(message)
        }
        return root
    }

    /** Extracts a job id from a `*Start` reply. */
    fun jobIdOf(reply: JsonObject): Long = reply["job"]?.jsonPrimitive?.long
        ?: throw AetherEngineException("native reply did not contain a job id")

    /** Serializes a payload object to the JSON string the FFI expects. */
    fun encode(obj: JsonObject): String = json.encodeToString(JsonObject.serializer(), obj)

    /**
     * Polls a job until it reaches a terminal state. Returns the job result
     * object (or null for a plain "done"). `poll` returns the JSON string
     * from `aether_job_poll`, i.e. `{"state":"running"}` or
     * `{"state":"done","result":{...}}`.
     */
    fun jobResult(pollRaw: String): JsonObject? {
        val root = json.parseToJsonElement(pollRaw).jsonObject
        return when (root["state"]?.jsonPrimitive?.content) {
            "done" -> root["result"]?.jsonObject
            else -> null
        }
    }
}

class AetherEngineException(message: String) : Exception(message)

// ---------------------------------------------------------------------------
// Payload builders
// ---------------------------------------------------------------------------

fun identityPayload(path: String, transport: AetherProtocol): JsonObject = JsonObject(
    mapOf(
        "path" to JsonPrimitive(path),
        "transport" to JsonPrimitive(transport.value)
    )
)

fun scanPayload(
    transport: AetherProtocol,
    mode: AetherScanMode
): JsonObject = JsonObject(
    mapOf(
        "transport" to JsonPrimitive(transport.value),
        "mode" to JsonPrimitive(mode.value)
    )
)

fun tunnelPayload(
    transport: AetherProtocol,
    peer: String,
    socks: String,
    http: String? = null
): JsonObject = JsonObject(
    buildMap {
        put("transport", JsonPrimitive(transport.value))
        put("peer", JsonPrimitive(peer))
        put("socks", JsonPrimitive(socks))
        if (http != null) put("http", JsonPrimitive(http))
    }
)

fun verifyPayload(
    transport: AetherProtocol,
    peer: String,
    socks: String
): JsonObject = JsonObject(
    mapOf(
        "transport" to JsonPrimitive(transport.value),
        "peer" to JsonPrimitive(peer),
        "socks" to JsonPrimitive(socks)
    )
)
