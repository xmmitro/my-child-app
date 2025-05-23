package com.xyz.child;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import android.provider.Settings;

public class MainActivity extends Activity {
    private static final String[] PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.READ_PHONE_STATE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button agreeButton = findViewById(R.id.agree_button);
        Button disagreeButton = findViewById(R.id.disagree_button);

        agreeButton.setOnClickListener(v -> {
            requestPermissions();
            // Prompt for Accessibility Service
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            startService(new Intent(this, MonitoringService.class));
            Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show();
            finish();
        });

        disagreeButton.setOnClickListener(v -> finish());
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, PERMISSIONS, 1);
    }
}