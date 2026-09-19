// Phone Link Chrome Extension UI & WebRTC Peer Logic
let ws = null;
let peerConnection = null;
let localStream = null;
let callTimerInterval = null;
let callSeconds = 0;
let isAudioMuted = false;
let isVideoDisabled = false;

const rtcConfig = {
  iceServers: [
    { urls: "stun:stun.l.google.com:19302" },
    { urls: "stun:stun1.l.google.com:19302" }
  ]
};

// DOM elements
const serverUrlInput = document.getElementById("serverUrl");
const pairCodeInput = document.getElementById("pairCode");
const btnConnect = document.getElementById("btnConnect");
const connectionPill = document.getElementById("connectionPill");
const peerStatusText = document.getElementById("peerStatusText");

const btnVoiceCall = document.getElementById("btnVoiceCall");
const btnVideoCall = document.getElementById("btnVideoCall");
const inCallSection = document.getElementById("inCallSection");
const callStateLabel = document.getElementById("callStateLabel");
const callTimer = document.getElementById("callTimer");
const localVideo = document.getElementById("localVideo");
const remoteVideo = document.getElementById("remoteVideo");
const btnMuteAudio = document.getElementById("btnMuteAudio");
const btnToggleVideo = document.getElementById("btnToggleVideo");
const btnEndCall = document.getElementById("btnEndCall");

const smsTo = document.getElementById("smsTo");
const smsBody = document.getElementById("smsBody");
const btnSendSms = document.getElementById("btnSendSms");
const smsStatus = document.getElementById("smsStatus");

const chatMessages = document.getElementById("chatMessages");
const chatInput = document.getElementById("chatInput");
const btnSendChat = document.getElementById("btnSendChat");

// Restore saved settings
chrome.storage.local.get(["serverUrl", "pairCode"], (data) => {
  if (data.serverUrl) serverUrlInput.value = data.serverUrl;
  else serverUrlInput.value = "ws://localhost:8080";

  if (data.pairCode) pairCodeInput.value = data.pairCode;

  if (data.pairCode) {
    connectSignaling();
  }
});

btnConnect.addEventListener("click", () => {
  const url = serverUrlInput.value.trim();
  const code = pairCodeInput.value.trim();
  if (!url || !code) {
    alert("Please enter both server URL and 6-digit pair code.");
    return;
  }
  chrome.storage.local.set({ serverUrl: url, pairCode: code });
  connectSignaling();
});

function updateUiConnected(connected, peerOnline = false) {
  if (!connected) {
    connectionPill.className = "pill disconnected";
    connectionPill.textContent = "Disconnected";
    peerStatusText.textContent = "Phone status: Offline";
    peerStatusText.className = "status-hint text-danger";
    btnVoiceCall.disabled = true;
    btnVideoCall.disabled = true;
    btnSendSms.disabled = true;
    btnSendChat.disabled = true;
  } else {
    connectionPill.className = "pill connected";
    connectionPill.textContent = "Connected";
    if (peerOnline) {
      peerStatusText.textContent = "Phone status: 🟢 Phone is ONLINE and ready";
      peerStatusText.className = "status-hint text-success";
      btnVoiceCall.disabled = false;
      btnVideoCall.disabled = false;
      btnSendSms.disabled = false;
      btnSendChat.disabled = false;
    } else {
      peerStatusText.textContent = "Phone status: 🟡 Waiting for phone to join...";
      peerStatusText.className = "status-hint text-warning";
      btnVoiceCall.disabled = true;
      btnVideoCall.disabled = true;
      btnSendSms.disabled = true;
      btnSendChat.disabled = true;
    }
  }
}

function connectSignaling() {
  const url = serverUrlInput.value.trim();
  const code = pairCodeInput.value.trim();
  if (!url || !code) return;

  if (ws) {
    ws.close();
  }

  try {
    ws = new WebSocket(url);

    ws.onopen = () => {
      ws.send(JSON.stringify({
        type: "join",
        role: "laptop",
        pairCode: code
      }));
      updateUiConnected(true, false);
    };

    ws.onmessage = async (event) => {
      try {
        const msg = JSON.parse(event.data);
        handleServerMessage(msg);
      } catch (err) {
        console.error("Message parse error:", err);
      }
    };

    ws.onclose = () => {
      updateUiConnected(false);
    };

    ws.onerror = (e) => {
      console.error("WS error:", e);
      updateUiConnected(false);
    };
  } catch (err) {
    console.error("WS connection failure:", err);
    updateUiConnected(false);
  }
}

async function handleServerMessage(msg) {
  switch (msg.type) {
    case "join-success":
      updateUiConnected(true, msg.peerOnline);
      break;

    case "peer-status":
      if (msg.peerRole === "phone") {
        updateUiConnected(true, msg.status === "online");
      }
      break;

    case "call-offer":
      handleIncomingOffer(msg);
      break;

    case "call-answer":
      if (peerConnection) {
        await peerConnection.setRemoteDescription(new RTCSessionDescription(msg.sdp));
        startCallTimer();
      }
      break;

    case "ice-candidate":
      if (peerConnection && msg.candidate) {
        await peerConnection.addIceCandidate(new RTCIceCandidate(msg.candidate));
      }
      break;

    case "call-end":
      endCallLocally();
      break;

    case "sms-status":
      smsStatus.textContent = msg.success ? `✅ SMS sent successfully!` : `❌ Failed: ${msg.message || "Error"}`;
      smsStatus.className = msg.success ? "sms-status-msg text-success" : "sms-status-msg text-danger";
      break;

    case "sms-received":
      appendChatMessage(`[SMS from ${msg.from}]: ${msg.body}`, "remote");
      break;

    case "chat-message":
      appendChatMessage(msg.text, "remote");
      break;
  }
}

// Call Handlers
btnVoiceCall.addEventListener("click", () => initiateCall(false));
btnVideoCall.addEventListener("click", () => initiateCall(true));
btnEndCall.addEventListener("click", () => {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: "call-end", pairCode: pairCodeInput.value.trim() }));
  }
  endCallLocally();
});

async function initiateCall(isVideo) {
  try {
    localStream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: isVideo
    });

    localVideo.srcObject = localStream;
    inCallSection.classList.remove("hidden");
    callStateLabel.textContent = isVideo ? "Calling phone (Video)..." : "Calling phone (Voice)...";

    setupPeerConnection();

    localStream.getTracks().forEach(track => {
      peerConnection.addTrack(track, localStream);
    });

    const offer = await peerConnection.createOffer();
    await peerConnection.setLocalDescription(offer);

    ws.send(JSON.stringify({
      type: "call-offer",
      pairCode: pairCodeInput.value.trim(),
      isVideo: isVideo,
      sdp: offer
    }));
  } catch (err) {
    alert("Camera/Mic access error: " + err.message);
    endCallLocally();
  }
}

async function handleIncomingOffer(msg) {
  const accept = confirm(`Incoming ${msg.isVideo ? "Video" : "Voice"} call from Phone! Accept?`);
  if (!accept) {
    ws.send(JSON.stringify({ type: "call-end", pairCode: pairCodeInput.value.trim() }));
    return;
  }

  try {
    localStream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: msg.isVideo
    });
    localVideo.srcObject = localStream;
    inCallSection.classList.remove("hidden");
    callStateLabel.textContent = "Connected to Phone";

    setupPeerConnection();

    localStream.getTracks().forEach(track => {
      peerConnection.addTrack(track, localStream);
    });

    await peerConnection.setRemoteDescription(new RTCSessionDescription(msg.sdp));
    const answer = await peerConnection.createAnswer();
    await peerConnection.setLocalDescription(answer);

    ws.send(JSON.stringify({
      type: "call-answer",
      pairCode: pairCodeInput.value.trim(),
      sdp: answer
    }));

    startCallTimer();
  } catch (err) {
    alert("Error accepting call: " + err.message);
    endCallLocally();
  }
}

function setupPeerConnection() {
  peerConnection = new RTCPeerConnection(rtcConfig);

  peerConnection.ontrack = (event) => {
    remoteVideo.srcObject = event.streams[0];
  };

  peerConnection.onicecandidate = (event) => {
    if (event.candidate && ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        type: "ice-candidate",
        pairCode: pairCodeInput.value.trim(),
        candidate: event.candidate
      }));
    }
  };
}

function startCallTimer() {
  callSeconds = 0;
  callTimer.textContent = "00:00";
  if (callTimerInterval) clearInterval(callTimerInterval);
  callTimerInterval = setInterval(() => {
    callSeconds++;
    const m = String(Math.floor(callSeconds / 60)).padStart(2, "0");
    const s = String(callSeconds % 60).padStart(2, "0");
    callTimer.textContent = `${m}:${s}`;
  }, 1000);
}

function endCallLocally() {
  if (callTimerInterval) clearInterval(callTimerInterval);
  if (localStream) {
    localStream.getTracks().forEach(t => t.stop());
    localStream = null;
  }
  if (peerConnection) {
    peerConnection.close();
    peerConnection = null;
  }
  inCallSection.classList.add("hidden");
  localVideo.srcObject = null;
  remoteVideo.srcObject = null;
}

btnMuteAudio.addEventListener("click", () => {
  if (localStream) {
    const audioTrack = localStream.getAudioTracks()[0];
    if (audioTrack) {
      isAudioMuted = !isAudioMuted;
      audioTrack.enabled = !isAudioMuted;
      btnMuteAudio.textContent = isAudioMuted ? "Unmute Mic" : "Mute Mic";
    }
  }
});

btnToggleVideo.addEventListener("click", () => {
  if (localStream) {
    const videoTrack = localStream.getVideoTracks()[0];
    if (videoTrack) {
      isVideoDisabled = !isVideoDisabled;
      videoTrack.enabled = !isVideoDisabled;
      btnToggleVideo.textContent = isVideoDisabled ? "Enable Cam" : "Toggle Cam";
    }
  }
});

// SMS Sending via Phone SIM
btnSendSms.addEventListener("click", () => {
  const to = smsTo.value.trim();
  const body = smsBody.value.trim();
  if (!to || !body) {
    alert("Please enter both recipient number and SMS message.");
    return;
  }

  smsStatus.textContent = "Sending command to Phone...";
  smsStatus.className = "sms-status-msg text-warning";

  ws.send(JSON.stringify({
    type: "sms-command",
    pairCode: pairCodeInput.value.trim(),
    id: "sms_" + Date.now(),
    to: to,
    body: body
  }));
});

// In-app messaging
btnSendChat.addEventListener("click", sendChatMessage);
chatInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") sendChatMessage();
});

function sendChatMessage() {
  const text = chatInput.value.trim();
  if (!text || !ws || ws.readyState !== WebSocket.OPEN) return;

  ws.send(JSON.stringify({
    type: "chat-message",
    pairCode: pairCodeInput.value.trim(),
    text: text,
    timestamp: Date.now()
  }));

  appendChatMessage(text, "local");
  chatInput.value = "";
}

function appendChatMessage(text, source) {
  const msgEl = document.createElement("div");
  msgEl.className = `chat-bubble ${source}`;
  msgEl.textContent = text;
  chatMessages.appendChild(msgEl);
  chatMessages.scrollTop = chatMessages.scrollHeight;
}
