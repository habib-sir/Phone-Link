package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.call.CallState
import com.example.ui.PhoneLinkViewModel
import com.example.ui.components.ActiveCallScreen
import com.example.ui.components.IncomingCallDialog
import com.example.ui.screens.CallLogsScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.CompanionSetupScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SmsBridgeScreen
import com.example.ui.theme.MyApplicationTheme

enum class ScreenTab(val title: String, val icon: ImageVector) {
    HOME("Pair", Icons.Default.Devices),
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    SMS("SMS Bridge", Icons.Default.Sms),
    CALLS("Call Logs", Icons.Default.History),
    SETUP("Laptop", Icons.Default.Laptop)
}

class MainActivity : ComponentActivity() {

    private val viewModel: PhoneLinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Auto-start connection on app launch
        viewModel.startService()

        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainAppScreen(viewModel: PhoneLinkViewModel) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(ScreenTab.HOME) }

    // State collections
    val pairCode by viewModel.pairCode.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val isServiceActive by viewModel.isServiceActive.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val isLaptopOnline by viewModel.isLaptopOnline.collectAsStateWithLifecycle()

    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsStateWithLifecycle()
    val isFrontCamera by viewModel.isFrontCamera.collectAsStateWithLifecycle()
    val isCameraEnabled by viewModel.isCameraEnabled.collectAsStateWithLifecycle()

    val callLogs by viewModel.callLogs.collectAsStateWithLifecycle()
    val smsLogs by viewModel.smsLogs.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()

    // Permission states
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasCameraPermission = results[Manifest.permission.CAMERA] == true
        hasAudioPermission = results[Manifest.permission.RECORD_AUDIO] == true
        hasSmsPermission = (results[Manifest.permission.SEND_SMS] == true) && (results[Manifest.permission.RECEIVE_SMS] == true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = results[Manifest.permission.POST_NOTIFICATIONS] == true
        }
    }

    fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(permissions.toTypedArray())
    }

    // Request permissions on first launch
    LaunchedEffect(Unit) {
        if (!hasCameraPermission || !hasAudioPermission || !hasSmsPermission) {
            requestRequiredPermissions()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF0B0F19),
        bottomBar = {
            val isCallActive = callState !is CallState.Idle && callState !is CallState.Ended
            AnimatedVisibility(visible = !isCallActive) {
                NavigationBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    containerColor = Color(0xFF0F172A),
                    tonalElevation = 8.dp
                ) {
                    ScreenTab.values().forEach { tab ->
                        val selected = currentTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title
                                )
                            },
                            label = { Text(tab.title, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF0F172A),
                                selectedTextColor = Color(0xFF38BDF8),
                                indicatorColor = Color(0xFF38BDF8),
                                unselectedIconColor = Color(0xFF64748B),
                                unselectedTextColor = Color(0xFF64748B)
                            ),
                            modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Selected Screen Content
            when (currentTab) {
                ScreenTab.HOME -> {
                    HomeScreen(
                        pairCode = pairCode,
                        serverUrl = serverUrl,
                        isServiceActive = isServiceActive,
                        connectionStatus = connectionStatus,
                        isLaptopOnline = isLaptopOnline,
                        hasCameraPermission = hasCameraPermission,
                        hasAudioPermission = hasAudioPermission,
                        hasSmsPermission = hasSmsPermission,
                        hasNotificationPermission = hasNotificationPermission,
                        onToggleService = { enable ->
                            if (enable) viewModel.startService() else viewModel.stopService()
                        },
                        onRegeneratePairCode = { viewModel.regeneratePairCode() },
                        onUpdateServerUrl = { newUrl -> viewModel.updateServerUrl(newUrl) },
                        onInitiateVoiceCall = { viewModel.initiateCall(isVideo = false) },
                        onInitiateVideoCall = { viewModel.initiateCall(isVideo = true) },
                        onRequestPermissions = { requestRequiredPermissions() },
                        onNavigateToSetupGuide = { currentTab = ScreenTab.SETUP }
                    )
                }

                ScreenTab.CHAT -> {
                    ChatScreen(
                        messages = chatMessages,
                        isLaptopOnline = isLaptopOnline,
                        onSendMessage = { text -> viewModel.sendChatMessage(text) },
                        onClearChat = { viewModel.clearAllHistory() }
                    )
                }

                ScreenTab.SMS -> {
                    SmsBridgeScreen(
                        smsLogs = smsLogs,
                        onSendTestSms = { phone, msg -> viewModel.sendTestSms(phone, msg) }
                    )
                }

                ScreenTab.CALLS -> {
                    CallLogsScreen(
                        callLogs = callLogs,
                        onClearLogs = { viewModel.clearAllHistory() }
                    )
                }

                ScreenTab.SETUP -> {
                    CompanionSetupScreen(pairCode = pairCode)
                }
            }

            // Incoming Call Alert Heads-Up Dialog
            IncomingCallDialog(
                callState = callState,
                onAccept = { viewModel.acceptIncomingCall() },
                onDecline = { viewModel.declineIncomingCall() }
            )

            // Fullscreen Active Call Overlay
            ActiveCallScreen(
                callState = callState,
                isMuted = isMuted,
                isSpeakerOn = isSpeakerOn,
                isFrontCamera = isFrontCamera,
                isCameraEnabled = isCameraEnabled,
                onToggleMute = { viewModel.callManager.toggleMute() },
                onToggleSpeaker = { viewModel.callManager.toggleSpeaker() },
                onToggleCameraFacing = { viewModel.callManager.toggleCameraFacing() },
                onToggleCameraEnabled = { viewModel.callManager.toggleCameraEnabled() },
                onEndCall = { viewModel.endActiveCall() }
            )
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
