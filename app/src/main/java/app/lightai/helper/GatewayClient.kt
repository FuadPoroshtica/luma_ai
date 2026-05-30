package app.lightai.helper

import android.content.Context
import android.os.Build
import android.util.Log
import app.lightai.BuildConfig
import app.lightai.data.DeviceIdentityStore
import app.lightai.data.GatewayConnectConfig
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenClaw gateway client.
 *
 * Wire protocol (ported from packages/gateway-client/src/client.ts):
 *  1. Open WebSocket to config.wssUrl
 *  2. Server sends event frame: {"type":"evt","event":"connect.challenge","payload":{"nonce":"..."}}
 *  3. Client sends request frame: {"type":"req","id":"...","method":"connect","params":{...,auth:{bootstrapToken:"..."},role:"operator",scopes:[...]}}
 *  4. Server replies: {"type":"res","id":"...","result":{...helloOk...}} or {"type":"res","id":"...","error":{...}}
 *
 * Subsequent traffic uses the same {type:"req"|"res"|"evt"} frame format.
 *
 * For v0.8.0 we connect, complete the handshake, and report status.
 * Chat send/receive is wired in v0.9.0 atop the same socket.
 */
class GatewayClient {
    enum class Status { Idle, Connecting, ChallengeReceived, Connected, Disconnected, Error }

    data class Frame(val raw: String)

    private val json = Json { ignoreUnknownKeys = true }
    private var ws: WebSocket? = null
    private var http: OkHttpClient? = null
    private var nonce: String? = null
    private var connectId: String? = null
    private var lastConfig: GatewayConnectConfig? = null

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _incoming =
        MutableSharedFlow<Frame>(
            replay = 0,
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val incoming: SharedFlow<Frame> = _incoming.asSharedFlow()

    private var appContext: Context? = null

    fun connect(
        context: Context,
        config: GatewayConnectConfig,
    ) {
        if (_status.value == Status.Connecting || _status.value == Status.Connected) {
            Log.d(TAG, "Already connecting/connected; ignoring connect()")
            return
        }
        appContext = context.applicationContext
        lastConfig = config
        _lastError.value = null
        _status.value = Status.Connecting

        val client =
            OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived WS
                .build()
        http = client

        val request = Request.Builder().url(config.wssUrl).build()
        Log.d(TAG, "Opening WebSocket to ${config.wssUrl}")
        ws = client.newWebSocket(request, Listener())
    }

    // === Chat surface (v0.9.0) ===

    data class ChatChunk(
        val runId: String,
        val state: String, // delta / final / aborted / error
        val text: String,
    )

    private val _chatStream =
        MutableSharedFlow<ChatChunk>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val chatStream: SharedFlow<ChatChunk> = _chatStream.asSharedFlow()

    /** Send a chat message. Returns the runId (idempotencyKey) we used. */
    fun sendChat(
        prompt: String,
        sessionKey: String = "main",
    ): String? {
        if (_status.value != Status.Connected) {
            Log.d(TAG, "sendChat ignored — gateway not connected (status=${_status.value})")
            return null
        }
        val runId = UUID.randomUUID().toString()
        val reqId = UUID.randomUUID().toString()
        val params =
            buildJsonObject {
                put("sessionKey", sessionKey)
                put("message", prompt)
                put("thinking", false)
                put("timeoutMs", 30_000)
                put("idempotencyKey", runId)
            }
        val frame =
            buildJsonObject {
                put("type", "req")
                put("id", reqId)
                put("method", "chat.send")
                put("params", params)
            }.toString()
        Log.d(TAG, ">> $frame")
        ws?.send(frame)
        return runId
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        ws?.close(1000, "client disconnect")
        ws = null
        http?.dispatcher?.executorService?.shutdown()
        http = null
        nonce = null
        connectId = null
        _status.value = Status.Disconnected
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            Log.d(TAG, "onOpen status=${response.code}")
            // Wait for server's connect.challenge event.
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            Log.d(TAG, "<< $text")
            handleFrame(text)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: okio.ByteString,
        ) {
            // OpenClaw gateway uses text frames; ignore binary for now.
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            Log.d(TAG, "onClosing code=$code reason=$reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            Log.d(TAG, "onClosed code=$code reason=$reason")
            _status.value = Status.Disconnected
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            Log.e(TAG, "onFailure code=${response?.code} msg=${t.message}", t)
            _lastError.value = t.message ?: t.javaClass.simpleName
            _status.value = Status.Error
        }
    }

    private fun handleFrame(raw: String) {
        _incoming.tryEmit(Frame(raw))
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val type = obj.field("type")
        when (type) {
            "event" -> handleEventFrame(obj)
            "res" -> handleResponseFrame(obj)
            else -> Log.d(TAG, "ignored frame type=$type")
        }
    }

    private fun handleEventFrame(obj: JsonObject) {
        val event = obj.field("event") ?: return
        when (event) {
            "connect.challenge" -> {
                val payload = (obj["payload"] as? JsonObject) ?: return
                val n = payload.field("nonce")
                if (n.isNullOrBlank()) {
                    _lastError.value = "challenge missing nonce"
                    _status.value = Status.Error
                    ws?.close(1008, "challenge missing nonce")
                    return
                }
                nonce = n
                _status.value = Status.ChallengeReceived
                sendConnect()
            }

            "chat" -> {
                val payload = (obj["payload"] as? JsonObject) ?: return
                val runId =
                    payload.field("runId")
                        ?: payload.field("idempotencyKey")
                        ?: return
                val state = payload.field("state") ?: "delta"
                val text = payload.field("text").orEmpty()
                _chatStream.tryEmit(ChatChunk(runId = runId, state = state, text = text))
            }

            else -> {
                // tick / health / etc. — ignore for now
            }
        }
    }

    private fun handleResponseFrame(obj: JsonObject) {
        val id = obj.field("id") ?: return
        if (id != connectId) return
        val error = obj["error"] as? JsonObject
        if (error != null) {
            val msg = error.field("message") ?: "connect rejected"
            val code = error.field("code") ?: ""
            _lastError.value = "$msg ($code)"
            _status.value = Status.Error
            return
        }
        Log.d(TAG, "connect.helloOk received")
        _status.value = Status.Connected
    }

    private fun sendConnect() {
        val config = lastConfig ?: return
        val ctx = appContext ?: return
        val n = nonce ?: return
        val id = UUID.randomUUID().toString()
        connectId = id

        // Load/create the persistent Ed25519 device identity.
        val store = DeviceIdentityStore.getInstance(ctx)
        val identity = store.loadOrCreate()
        val publicKeyB64Url = store.publicKeyBase64Url(identity)
        val signedAtMs = System.currentTimeMillis()

        // signatureToken = authToken ?? authBootstrapToken (per their selectConnectAuth)
        val signatureToken =
            if (config.token.isNotEmpty()) config.token else config.bootstrapToken

        val role = "operator"
        val scopesList = listOf("operator.read", "operator.write", "operator.approvals")
        val clientId = "openclaw-android"
        val clientMode = "ui"
        val platform = "android"
        val deviceFamily = "android"

        // buildDeviceAuthPayloadV3 — pipe-delimited, platform & deviceFamily lowercase.
        val payload =
            listOf(
                "v3",
                identity.deviceId,
                clientId,
                clientMode,
                role,
                scopesList.joinToString(","),
                signedAtMs.toString(),
                signatureToken,
                n,
                platform,
                deviceFamily,
            ).joinToString("|")

        val signature = store.signPayload(payload, identity)
        if (signature == null || publicKeyB64Url == null) {
            _lastError.value = "device sign failed"
            _status.value = Status.Error
            ws?.close(1008, "device sign failed")
            return
        }

        val authObj =
            buildJsonObject {
                if (config.bootstrapToken.isNotEmpty()) put("bootstrapToken", config.bootstrapToken)
                if (config.token.isNotEmpty()) put("token", config.token)
                if (config.password.isNotEmpty()) put("password", config.password)
            }

        val deviceObj =
            buildJsonObject {
                put("id", identity.deviceId)
                put("publicKey", publicKeyB64Url)
                put("signature", signature)
                put("signedAt", signedAtMs)
                put("nonce", n)
            }

        val params =
            buildJsonObject {
                put("minProtocol", 4)
                put("maxProtocol", 4)
                put(
                    "client",
                    buildJsonObject {
                        put("id", clientId)
                        put("displayName", "LightAI")
                        put("version", BuildConfig.VERSION_NAME)
                        put("platform", platform)
                        put("deviceFamily", "Android")
                        put("mode", clientMode)
                        put("instanceId", instanceId())
                    },
                )
                put("caps", buildJsonArray { })
                put("auth", authObj)
                put("role", role)
                put(
                    "scopes",
                    buildJsonArray {
                        for (s in scopesList) add(JsonPrimitive(s))
                    },
                )
                put("device", deviceObj)
            }

        val frame =
            buildJsonObject {
                put("type", "req")
                put("id", id)
                put("method", "connect")
                put("params", params)
            }.toString()

        Log.d(TAG, ">> $frame")
        ws?.send(frame)
    }

    private fun instanceId(): String = "lightai-${Build.MODEL ?: "android"}-${Build.SERIAL ?: "0"}"

    private fun JsonObject.field(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }

    companion object {
        private const val TAG = "LightAI-Gateway"

        @Volatile
        private var INSTANCE: GatewayClient? = null

        fun shared(): GatewayClient =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: GatewayClient().also { INSTANCE = it }
            }
    }
}
