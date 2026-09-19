// Phone Link Chrome Extension Background Service Worker
let socket = null;
let currentPairCode = null;
let currentServerUrl = "ws://localhost:8080";

chrome.storage.local.get(["serverUrl", "pairCode"], (result) => {
  if (result.serverUrl) currentServerUrl = result.serverUrl;
  if (result.pairCode) currentPairCode = result.pairCode;
  if (currentPairCode) connectWebSocket();
});

function connectWebSocket() {
  if (!currentPairCode) return;
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return;
  }

  try {
    socket = new WebSocket(currentServerUrl);

    socket.onopen = () => {
      console.log("[PhoneLink BG] Connected to signaling server:", currentServerUrl);
      socket.send(JSON.stringify({
        type: "join",
        role: "laptop",
        pairCode: currentPairCode
      }));
    };

    socket.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        console.log("[PhoneLink BG] Received:", msg);

        if (msg.type === "call-offer") {
          chrome.notifications.create({
            type: "basic",
            iconUrl: "icons/icon128.png",
            title: "Incoming Call from Phone",
            message: `${msg.isVideo ? "Video" : "Voice"} call incoming! Click extension to answer.`,
            priority: 2
          });
        } else if (msg.type === "sms-received") {
          chrome.notifications.create({
            type: "basic",
            iconUrl: "icons/icon128.png",
            title: `New SMS from ${msg.from}`,
            message: msg.body || "(Empty message)",
            priority: 1
          });
        }
      } catch (err) {
        console.error("[PhoneLink BG] Parse error:", err);
      }
    };

    socket.onclose = () => {
      console.log("[PhoneLink BG] Disconnected. Reconnecting in 5 seconds...");
      setTimeout(connectWebSocket, 5000);
    };

    socket.onerror = (err) => {
      console.error("[PhoneLink BG] Error:", err);
      socket.close();
    };
  } catch (e) {
    console.error("[PhoneLink BG] Connection attempt failed:", e);
    setTimeout(connectWebSocket, 5000);
  }
}

chrome.runtime.onMessage.addListener((req, sender, sendResponse) => {
  if (req.action === "update-config") {
    currentServerUrl = req.serverUrl;
    currentPairCode = req.pairCode;
    if (socket) socket.close();
    connectWebSocket();
    sendResponse({ success: true });
  }
});
