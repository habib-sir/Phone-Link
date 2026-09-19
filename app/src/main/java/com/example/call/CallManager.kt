package com.example.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class CallState {
    object Idle : CallState()
    data class Incoming(val isVideo: Boolean, val sdp: String?) : CallState()
    data class Outgoing(val isVideo: Boolean) : CallState()
    data class Active(val isVideo: Boolean, val durationSeconds: Int = 0) : CallState()
    data class Ended(val reason: String, val durationSeconds: Int) : CallState()
}

class CallManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var ringtone: Ringtone? = null
    private var vibratorJob: Job? = null
    private var callTimerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(true)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private var activeDuration = 0

    init {
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ringtone: ${e.message}")
        }
    }

    fun onIncomingCall(isVideo: Boolean, sdp: String?) {
        if (_callState.value !is CallState.Idle) return
        _callState.value = CallState.Incoming(isVideo, sdp)
        startRinging()
    }

    fun startOutgoingCall(isVideo: Boolean) {
        if (_callState.value !is CallState.Idle) return
        _callState.value = CallState.Outgoing(isVideo)
        _isCameraEnabled.value = isVideo
        setAudioModeInCall()
    }

    fun acceptCall() {
        val current = _callState.value
        if (current is CallState.Incoming) {
            stopRinging()
            activeDuration = 0
            _callState.value = CallState.Active(current.isVideo, 0)
            _isCameraEnabled.value = current.isVideo
            setAudioModeInCall()
            startCallTimer(current.isVideo)
        }
    }

    fun callConnected() {
        val current = _callState.value
        if (current is CallState.Outgoing) {
            activeDuration = 0
            _callState.value = CallState.Active(current.isVideo, 0)
            startCallTimer(current.isVideo)
        }
    }

    fun endCall(reason: String = "Call ended") {
        stopRinging()
        callTimerJob?.cancel()
        resetAudioMode()
        val duration = activeDuration
        _callState.value = CallState.Ended(reason, duration)

        scope.launch {
            delay(1500)
            _callState.value = CallState.Idle
        }
    }

    private fun startCallTimer(isVideo: Boolean) {
        callTimerJob?.cancel()
        activeDuration = 0
        callTimerJob = scope.launch {
            while (isActive) {
                delay(1000)
                activeDuration++
                _callState.value = CallState.Active(isVideo, activeDuration)
            }
        }
    }

    private fun startRinging() {
        try {
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing ringtone: ${e.message}")
        }

        vibratorJob?.cancel()
        vibratorJob = scope.launch {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            while (isActive && _callState.value is CallState.Incoming) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(500)
                }
                delay(1200)
            }
        }
    }

    private fun stopRinging() {
        try {
            if (ringtone?.isPlaying == true) {
                ringtone?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone: ${e.message}")
        }
        vibratorJob?.cancel()
        vibratorJob = null
    }

    private fun setAudioModeInCall() {
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager?.isSpeakerphoneOn = _isSpeakerOn.value
        audioManager?.isMicrophoneMute = _isMuted.value
    }

    private fun resetAudioMode() {
        audioManager?.mode = AudioManager.MODE_NORMAL
        audioManager?.isMicrophoneMute = false
    }

    fun toggleMute() {
        val newMute = !_isMuted.value
        _isMuted.value = newMute
        audioManager?.isMicrophoneMute = newMute
    }

    fun toggleSpeaker() {
        val newSpeaker = !_isSpeakerOn.value
        _isSpeakerOn.value = newSpeaker
        audioManager?.isSpeakerphoneOn = newSpeaker
    }

    fun toggleCameraFacing() {
        _isFrontCamera.value = !_isFrontCamera.value
    }

    fun toggleCameraEnabled() {
        _isCameraEnabled.value = !_isCameraEnabled.value
    }

    companion object {
        private const val TAG = "CallManager"
    }
}
