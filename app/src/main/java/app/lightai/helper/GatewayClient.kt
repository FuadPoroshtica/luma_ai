package app.lightai.helper

import android.os.Build
import android.util.Log
import app.lightai.BuildConfig
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

    fun connect(config: GatewayConnectConfig) {
        if (_status.value == Status.Connecting || _status.value == Status.Connected) {
            Log.d(TAG, "Already connecting/connected; ignoring connect()")
            return
        }
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
            "evt" -> handleEventFrame(obj)
            "res" -> handleResponseFrame(obj)
            else -> Log.d(TAG, "ignored frame type=$type")
        }
    }

    private fun handleEventFrame(obj: JsonObject) {
        val event = obj.field("event") ?: return
        if (event == "connect.challenge") {
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
        val n = nonce ?: return
        val id = UUID.randomUUID().toString()
        connectId = id

        val authObj =
            buildJsonObject {
                if (config.bootstrapToken.isNotEmpty()) put("bootstrapToken", config.bootstrapToken)
                if (config.token.isNotEmpty()) put("token", config.token)
                if (config.password.isNotEmpty()) put("password", config.password)
            }

        val params =
            buildJsonObject {
                put("minProtocol", 4)
                put("maxProtocol", 4)
                put(
                    "client",
                    buildJsonObject {
                        put("id", "lightai-android")
                        put("displayName", "LightAI")
                        put("version", BuildConfig.VERSION_NAME)
                        put("platform", "android")
                        put("deviceFamily", "Android")
                        put("mode", "ui")
                        put("instanceId", instanceId())
                    },
                )
                put("caps", buildJsonArray { })
                put("auth", authObj)
                put("role", "operator")
                put(
                    "scopes",
                    buildJsonArray {
                        add(JsonPrimitive("operator.read"))
                        add(JsonPrimitive("operator.write"))
                        add(JsonPrimitive("operator.approvals"))
                    },
                )
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
