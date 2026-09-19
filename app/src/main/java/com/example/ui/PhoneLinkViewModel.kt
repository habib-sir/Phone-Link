package com.example.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.call.CallManager
import com.example.call.CallState
import com.example.data.local.AppDatabase
import com.example.data.local.CallLogEntity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.SmsLogEntity
import com.example.data.model.SignalingMessage
import com.example.data.repository.PhoneLinkRepository
import com.example.network.ConnectionStatus
import com.example.network.SignalingClient
import com.example.service.SignalingForegroundService
import com.example.sms.SmsManagerHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

class PhoneLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("phonelink_prefs", Context.MODE_PRIVATE)

    private val database = AppDatabase.getInstance(application)
    private val repository = PhoneLinkRepository(database.phoneLinkDao())
    val signalingClient = SignalingClient.getInstance()
    val callManager = CallManager(application)

    // Data streams from Room
    val callLogs: StateFlow<List<CallLogEntity>> = repository.callLogs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val smsLogs: StateFlow<List<SmsLogEntity>> = repository.smsLogs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val chatMessages: StateFlow<List<ChatMessageEntity>> = repository.chatMessages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Pairing configuration
    private val _pairCode = MutableStateFlow(getOrGeneratePairCode())
    val pairCode: StateFlow<String> = _pairCode.asStateFlow()

    private val _serverUrl = MutableStateFlow(
        prefs.getString(KEY_SERVER_URL, "ws://10.0.2.2:8080") ?: "ws://10.0.2.2:8080"
    )
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    // Service running state
    private val _isServiceActive = MutableStateFlow(false)
    val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

    // Connection & Laptop status
    val connectionStatus: StateFlow<ConnectionStatus> = signalingClient.connectionStatus
    val isLaptopOnline: StateFlow<Boolean> = signalingClient.isLaptopOnline

    // Call status
    val callState: StateFlow<CallState> = callManager.callState
    val isMuted: StateFlow<Boolean> = callManager.isMuted
    val isSpeakerOn: StateFlow<Boolean> = callManager.isSpeakerOn
    val isFrontCamera: StateFlow<Boolean> = callManager.isFrontCamera
    val isCameraEnabled: StateFlow<Boolean> = callManager.isCameraEnabled

    init {
        listenForIncomingMessages()
        observeCallStateForLogging()
    }

    private fun getOrGeneratePairCode(): String {
        val saved = prefs.getString(KEY_PAIR_CODE, null)
        if (!saved.isNullOrEmpty()) return saved
        val newCode = (100000 + Random.nextInt(900000)).toString()
        prefs.edit().putString(KEY_PAIR_CODE, newCode).apply()
        return newCode
    }

    fun regeneratePairCode() {
        val newCode = (100000 + Random.nextInt(900000)).toString()
        prefs.edit().putString(KEY_PAIR_CODE, newCode).apply()
        _pairCode.value = newCode
        if (_isServiceActive.value) {
            connectService()
        }
    }

    fun updateServerUrl(newUrl: String) {
        val trimmed = newUrl.trim()
        prefs.edit().putString(KEY_SERVER_URL, trimmed).apply()
        _serverUrl.value = trimmed
        if (_isServiceActive.value) {
            connectService()
        }
    }

    fun startService() {
        _isServiceActive.value = true
        connectService()
    }

    fun stopService() {
        _isServiceActive.value = false
        SignalingForegroundService.stop(getApplication())
        signalingClient.disconnect()
    }

    private fun connectService() {
        SignalingForegroundService.start(getApplication(), _serverUrl.value, _pairCode.value)
    }

    private fun listenForIncomingMessages() {
        viewModelScope.launch {
            signalingClient.incomingMessages.collectLatest { msg ->
                when (msg.type) {
                    "call-offer" -> {
                        callManager.onIncomingCall(msg.isVideo, msg.sdp)
                    }
                    "call-answer" -> {
                        callManager.callConnected()
                    }
                    "call-end" -> {
                        callManager.endCall(msg.reason ?: "Laptop hung up")
                    }
                    "sms-command" -> {
                        handleSmsCommand(msg)
                    }
                    "chat-message" -> {
                        msg.text?.let { text ->
                            repository.addChatMessage(text, "LAPTOP")
                        }
                    }
                }
            }
        }
    }

    private fun handleSmsCommand(msg: SignalingMessage) {
        val commandId = msg.id ?: "cmd_${System.currentTimeMillis()}"
        val to = msg.to ?: return
        val body = msg.body ?: ""

        viewModelScope.launch {
            repository.addSmsLog(
                commandId = commandId,
                phoneNumber = to,
                body = body,
                direction = "SENT_VIA_LAPTOP",
                status = "PENDING"
            )

            SmsManagerHelper.sendSms(getApplication(), to, body) { success, errorMsg ->
                viewModelScope.launch {
                    val status = if (success) "SUCCESS" else "FAILED"
                    repository.updateSmsStatus(commandId, status)

                    signalingClient.sendMessage(
                        SignalingMessage(
                            type = "sms-status",
                            id = commandId,
                            success = success,
                            message = errorMsg
                        )
                    )
                }
            }
        }
    }

    fun sendChatMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            repository.addChatMessage(trimmed, "PHONE")
            signalingClient.sendMessage(
                SignalingMessage(
                    type = "chat-message",
                    pairCode = _pairCode.value,
                    text = trimmed,
                    sender = "phone",
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun initiateCall(isVideo: Boolean) {
        callManager.startOutgoingCall(isVideo)
        signalingClient.sendMessage(
            SignalingMessage(
                type = "call-offer",
                pairCode = _pairCode.value,
                isVideo = isVideo,
                from = "phone"
            )
        )
    }

    fun acceptIncomingCall() {
        val current = callManager.callState.value
        if (current is CallState.Incoming) {
            callManager.acceptCall()
            signalingClient.sendMessage(
                SignalingMessage(
                    type = "call-answer",
                    pairCode = _pairCode.value,
                    isVideo = current.isVideo,
                    from = "phone"
                )
            )
        }
    }

    fun declineIncomingCall() {
        callManager.endCall("Declined by phone")
        signalingClient.sendMessage(
            SignalingMessage(
                type = "call-end",
                pairCode = _pairCode.value,
                reason = "declined"
            )
        )
    }

    fun endActiveCall() {
        callManager.endCall("Ended by phone")
        signalingClient.sendMessage(
            SignalingMessage(
                type = "call-end",
                pairCode = _pairCode.value,
                reason = "user_hangup"
            )
        )
    }

    private fun observeCallStateForLogging() {
        var lastCallWasVideo = false
        var lastCallDirection = "OUTGOING"

        viewModelScope.launch {
            callManager.callState.collectLatest { state ->
                when (state) {
                    is CallState.Incoming -> {
                        lastCallWasVideo = state.isVideo
                        lastCallDirection = "INCOMING"
                    }
                    is CallState.Outgoing -> {
                        lastCallWasVideo = state.isVideo
                        lastCallDirection = "OUTGOING"
                    }
                    is CallState.Ended -> {
                        val status = if (state.durationSeconds > 0) "ANSWERED" else "MISSED"
                        repository.addCallLog(
                            isVideo = lastCallWasVideo,
                            direction = lastCallDirection,
                            durationSeconds = state.durationSeconds,
                            status = status
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    fun sendTestSms(phoneNumber: String, message: String) {
        val commandId = "test_${System.currentTimeMillis()}"
        viewModelScope.launch {
            repository.addSmsLog(
                commandId = commandId,
                phoneNumber = phoneNumber,
                body = message,
                direction = "LOCAL_TEST",
                status = "PENDING"
            )

            SmsManagerHelper.sendSms(getApplication(), phoneNumber, message) { success, _ ->
                viewModelScope.launch {
                    val status = if (success) "SUCCESS" else "FAILED"
                    repository.updateSmsStatus(commandId, status)
                }
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    companion object {
        private const val KEY_PAIR_CODE = "phonelink_pair_code"
        private const val KEY_SERVER_URL = "phonelink_server_url"
    }
}
