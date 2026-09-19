package com.example.network

import android.util.Log
import com.example.data.model.SignalingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class SignalingClient private constructor() {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var reconnectJob: Job? = null

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isLaptopOnline = MutableStateFlow(false)
    val isLaptopOnline: StateFlow<Boolean> = _isLaptopOnline.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<SignalingMessage> = _incomingMessages.asSharedFlow()

    private var currentUrl: String = "ws://10.0.2.2:8080"
    private var currentPairCode: String = ""
    private var shouldKeepConnected: Boolean = false

    fun connect(serverUrl: String, pairCode: String) {
        currentUrl = serverUrl.trim()
        currentPairCode = pairCode.trim()
        shouldKeepConnected = true

        reconnectJob?.cancel()
        doConnect()
    }

    fun disconnect() {
        shouldKeepConnected = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _isLaptopOnline.value = false
    }

    private fun doConnect() {
        if (!shouldKeepConnected) return

        _connectionStatus.value = ConnectionStatus.CONNECTING
        try {
            val request = Request.Builder()
                .url(currentUrl)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected to $currentUrl")
                    _connectionStatus.value = ConnectionStatus.CONNECTED

                    // Send Join message
                    val joinMsg = SignalingMessage(
                        type = "join",
                        role = "phone",
                        pairCode = currentPairCode
                    )
                    webSocket.send(joinMsg.toJson())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "WebSocket incoming: $text")
                    val message = SignalingMessage.fromJson(text) ?: return
                    handleMessage(message)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code / $reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    _isLaptopOnline.value = false
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket error: ${t.message}")
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    _isLaptopOnline.value = false
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate WebSocket connection: ${e.message}")
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            scheduleReconnect()
        }
    }

    private fun handleMessage(msg: SignalingMessage) {
        when (msg.type) {
            "join-success" -> {
                _isLaptopOnline.value = msg.peerOnline
            }
            "peer-status" -> {
                if (msg.peerRole == "laptop") {
                    _isLaptopOnline.value = msg.status == "online"
                }
            }
        }
        scope.launch {
            _incomingMessages.emit(msg)
        }
    }

    private fun scheduleReconnect() {
        if (!shouldKeepConnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(4000)
            if (isActive && shouldKeepConnected) {
                Log.d(TAG, "Reconnecting WebSocket...")
                doConnect()
            }
        }
    }

    fun sendMessage(msg: SignalingMessage): Boolean {
        val ws = webSocket
        return if (ws != null && _connectionStatus.value == ConnectionStatus.CONNECTED) {
            val json = msg.toJson()
            Log.d(TAG, "WebSocket send: $json")
            ws.send(json)
        } else {
            Log.w(TAG, "Cannot send message, WebSocket not connected")
            false
        }
    }

    companion object {
        private const val TAG = "SignalingClient"

        @Volatile
        private var INSTANCE: SignalingClient? = null

        fun getInstance(): SignalingClient {
            return INSTANCE ?: synchronized(this) {
                val instance = SignalingClient()
                INSTANCE = instance
                instance
            }
        }
    }
}
