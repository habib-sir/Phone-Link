const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT }, () => {
  console.log(`[Signaling Server] Running on ws://localhost:${PORT}`);
  console.log(`[Signaling Server] Ready for connections via Cloudflare Tunnel or Local Network.`);
});

// Store connected clients grouped by pair code
// rooms: { [pairCode]: { phone: ws, laptop: ws } }
const rooms = new Map();

wss.on('connection', (ws, req) => {
  const clientIp = req.socket.remoteAddress;
  console.log(`[Signaling Server] New connection from ${clientIp}`);

  let clientRoom = null;
  let clientRole = null; // 'phone' or 'laptop'

  ws.isAlive = true;
  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (rawMessage) => {
    try {
      const message = JSON.parse(rawMessage.toString());
      const { type, pairCode, role } = message;

      if (type === 'join') {
        clientRoom = pairCode;
        clientRole = role; // 'phone' or 'laptop'

        if (!rooms.has(clientRoom)) {
          rooms.set(clientRoom, {});
        }

        const room = rooms.get(clientRoom);
        room[clientRole] = ws;

        console.log(`[Room ${clientRoom}] ${clientRole} joined`);

        // Send confirmation back to sender
        ws.send(JSON.stringify({
          type: 'join-success',
          role: clientRole,
          pairCode: clientRoom,
          peerOnline: !!(clientRole === 'phone' ? room.laptop : room.phone)
        }));

        // Notify the peer if connected
        const peer = clientRole === 'phone' ? room.laptop : room.phone;
        if (peer && peer.readyState === WebSocket.OPEN) {
          peer.send(JSON.stringify({
            type: 'peer-status',
            peerRole: clientRole,
            status: 'online'
          }));
        }
        return;
      }

      // If not yet joined a room
      if (!clientRoom || !rooms.has(clientRoom)) {
        ws.send(JSON.stringify({ type: 'error', message: 'Not joined to any room. Send join first.' }));
        return;
      }

      const room = rooms.get(clientRoom);
      const peer = clientRole === 'phone' ? room.laptop : room.phone;

      // Handle Ping / Heartbeat
      if (type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
        return;
      }

      // Forward WebRTC signals, SMS commands, and Chat messages directly to the peer
      switch (type) {
        case 'call-offer':
        case 'call-answer':
        case 'ice-candidate':
        case 'call-end':
        case 'sms-command':
        case 'sms-status':
        case 'sms-received':
        case 'chat-message':
        case 'device-status':
          if (peer && peer.readyState === WebSocket.OPEN) {
            peer.send(JSON.stringify({ ...message, from: clientRole }));
            console.log(`[Room ${clientRoom}] Relayed ${type} from ${clientRole} to peer`);
          } else {
            console.log(`[Room ${clientRoom}] Peer not connected to receive ${type}`);
            ws.send(JSON.stringify({
              type: 'peer-status',
              peerRole: clientRole === 'phone' ? 'laptop' : 'phone',
              status: 'offline'
            }));
          }
          break;

        default:
          console.warn(`[Room ${clientRoom}] Unknown message type: ${type}`);
      }
    } catch (err) {
      console.error('[Signaling Server] Error handling message:', err.message);
    }
  });

  ws.on('close', () => {
    console.log(`[Signaling Server] Client disconnected (${clientRole} in room ${clientRoom})`);
    if (clientRoom && rooms.has(clientRoom)) {
      const room = rooms.get(clientRoom);
      if (clientRole && room[clientRole] === ws) {
        delete room[clientRole];
      }

      // Notify peer that this device went offline
      const peer = clientRole === 'phone' ? room.laptop : room.phone;
      if (peer && peer.readyState === WebSocket.OPEN) {
        peer.send(JSON.stringify({
          type: 'peer-status',
          peerRole: clientRole,
          status: 'offline'
        }));
      }

      // Clean up empty rooms
      if (!room.phone && !room.laptop) {
        rooms.delete(clientRoom);
      }
    }
  });

  ws.on('error', (err) => {
    console.error(`[Signaling Server] WebSocket error:`, err.message);
  });
});

// Periodic heartbeat to prevent ghost connections
const heartbeatInterval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on('close', () => {
  clearInterval(heartbeatInterval);
});
