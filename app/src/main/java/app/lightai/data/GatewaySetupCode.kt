package app.lightai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.util.Base64
import java.util.Locale

/**
 * Ported from OpenClaw's `apps/android/.../GatewayConfigResolver.kt` (MIT).
 * Decodes their setup-code format: URL-safe Base64 wrapping a JSON object
 * `{"url":"wss://...","bootstrapToken":"...","token":"...","password":"..."}`.
 *
 * QR codes may contain the raw base64 or a JSON wrapper `{"setupCode":"..."}`.
 */
data class GatewaySetupCode(
    val url: String,
    val bootstrapToken: String?,
    val token: String?,
    val password: String?,
)

data class GatewayEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val displayUrl: String,
)

data class GatewayConnectConfig(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val bootstrapToken: String,
    val token: String,
    val password: String,
) {
    val wssUrl: String get() = (if (tls) "wss" else "ws") + "://" + host + ":" + port
    val httpUrl: String get() = (if (tls) "https" else "http") + "://" + host + ":" + port
}

private val gatewayJson = Json { ignoreUnknownKeys = true }

object GatewaySetupCodeDecoder {
    /** Decode a raw setup-code string (URL-safe Base64 JSON) to [GatewaySetupCode]. */
    fun decode(rawInput: String): GatewaySetupCode? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        val padded =
            trimmed
                .replace('-', '+')
                .replace('_', '/')
                .let { normalized ->
                    val remainder = normalized.length % 4
                    if (remainder == 0) normalized else normalized + "=".repeat(4 - remainder)
                }

        return try {
            val decoded = String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
            val obj = parseJsonObject(decoded) ?: return null
            val url = jsonField(obj, "url").orEmpty()
            if (url.isEmpty()) return null
            GatewaySetupCode(
                url = url,
                bootstrapToken = jsonField(obj, "bootstrapToken"),
                token = jsonField(obj, "token"),
                password = jsonField(obj, "password"),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Resolve a setup-code candidate from a QR-scanned payload. The QR can
     * either contain the raw setup code or a JSON envelope `{"setupCode":"..."}`.
     */
    fun resolveScannedPayload(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        val envelopeCode = parseJsonObject(trimmed)?.let { jsonField(it, "setupCode") }
        return envelopeCode ?: trimmed
    }

    /** Parse a gateway URL (ws/wss/http/https) into an endpoint, no validation beyond URI parsing. */
    fun parseEndpoint(rawInput: String): GatewayEndpoint? {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return null
        val normalized = if (raw.contains("://")) raw else "https://$raw"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host?.trim()?.trim('[', ']').orEmpty()
        if (host.isEmpty()) return null
        val scheme = uri.scheme?.trim()?.lowercase(Locale.US).orEmpty()
        if (scheme !in setOf("ws", "wss", "http", "https")) return null
        val tls = scheme == "wss" || scheme == "https"
        val defaultPort = if (tls) 443 else 18789
        val port = uri.port.takeIf { it in 1..65535 } ?: defaultPort
        val displayHost = if (host.contains(":")) "[$host]" else host
        val displayUrl = "${if (tls) "https" else "http"}://$displayHost:$port"
        return GatewayEndpoint(host = host, port = port, tls = tls, displayUrl = displayUrl)
    }

    /** Build a fully-resolved connect config from a setup code, or null if invalid. */
    fun resolveConnectConfig(setupCode: String): GatewayConnectConfig? {
        val setup = decode(setupCode) ?: return null
        val endpoint = parseEndpoint(setup.url) ?: return null
        return GatewayConnectConfig(
            host = endpoint.host,
            port = endpoint.port,
            tls = endpoint.tls,
            bootstrapToken = setup.bootstrapToken.orEmpty().trim(),
            token = setup.token.orEmpty().trim(),
            password = setup.password.orEmpty().trim(),
        )
    }

    private fun parseJsonObject(input: String): JsonObject? =
        runCatching { gatewayJson.parseToJsonElement(input).jsonObject }.getOrNull()

    private fun jsonField(
        obj: JsonObject,
        key: String,
    ): String? {
        val value = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return value.ifEmpty { null }
    }
}
