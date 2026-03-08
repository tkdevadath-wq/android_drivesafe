package com.example.drivesafe;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class EyeTrackingFragment extends Fragment {

    // ─── Views ───────────────────────────────────────────────────────────
    private PreviewView previewView;
    private CardView aiCard;
    private TextView statusText, eyeValueText, blinkRateText;
    private FrameLayout statusCircleFrame;
    private FrameLayout fullscreenOverlay;
    private ImageButton btnCloseFullscreen;
    private TextView tvFullscreenHint;
    private Button btnAction;
    private View emergencyOverlay;
    private boolean isPreviewExpanded = false;
    private boolean isInPipMode = false;

    // ─── Core Components ─────────────────────────────────────────────────
    private FaceDetector faceDetector;
    private MediaPlayer mediaPlayer;
    private MediaPlayer voicePlayer;
    private ExecutorService cameraExecutor;
    private DatabaseHelper dbHelper;

    // ─── Animations ──────────────────────────────────────────────────────
    private Animation pulseAnim;
    private Animation flashAnim;

    // ─── Thread-safe monitoring flag ─────────────────────────────────────
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);

    // ─── Blink Tracking ──────────────────────────────────────────────────
    private int blinkCount = 0;
    private int sessionTotalBlinks = 0;
    private boolean eyesWereClosed = false;
    private long minuteStartTime = 0;
    private long eyeClosedStartTime = 0;

    // ─── Distraction Tracking ────────────────────────────────────────────
    private long distractionStartTime = 0;
    private boolean isDistracted = false;
    private boolean distractionFired = false;
    private int sessionTotalDistractions = 0;

    // ─── Yawn Tracking ───────────────────────────────────────────────────
    private boolean yawnFired = false;
    private int sessionTotalYawns = 0;

    // ─── Session & SOS Tracking ──────────────────────────────────────────
    private long sessionId = -1;
    private long sessionStartTime = 0;
    private int sessionWarningCount = 0;
    private int sessionCriticalCount = 0;
    private boolean warningFired = false;
    private boolean criticalFired = false;
    private boolean sosSent = false;

    // ─── Settings-driven Detection Parameters ────────────────────────────
    private float activeEarThreshold = Constants.EAR_THRESHOLD;
    private long activeWarningDuration = Constants.WARNING_DURATION_MS;

    // ─── Smart Detection ─────────────────────────────────────────────────
    private boolean smartDetectionEnabled = false;
    private boolean minSpeedGatingEnabled = false;
    private float minSpeedKmh = Constants.DEFAULT_MIN_SPEED_KMH;
    private volatile float currentSpeedKmh = 0f;
    private FusedLocationProviderClient fusedSpeedClient;
    private LocationCallback speedCallback;
    private boolean isSpeedTrackingActive = false;
    private boolean largestFaceOnly = false;

    // ─── Permission Launcher ─────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Boolean cam = result.get(Manifest.permission.CAMERA);
                        Boolean sms = result.get(Manifest.permission.SEND_SMS);
                        if (Boolean.TRUE.equals(cam) && Boolean.TRUE.equals(sms)) {
                            startMonitoring();
                        } else {
                            Context ctx = getContext();
                            if (ctx != null) {
                                Toast.makeText(ctx, R.string.permissions_required, Toast.LENGTH_LONG).show();
                            }
                        }
                    }
            );

    // ─── Lifecycle ───────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_eye_tracking, container, false);

        previewView = view.findViewById(R.id.previewView);
        aiCard = view.findViewById(R.id.ai_card);
        statusText = view.findViewById(R.id.statusText);
        eyeValueText = view.findViewById(R.id.eyeValueText);
        blinkRateText = view.findViewById(R.id.blinkRateText);
        statusCircleFrame = view.findViewById(R.id.statusCircleFrame);
        fullscreenOverlay = view.findViewById(R.id.fullscreenOverlay);
        btnCloseFullscreen = view.findViewById(R.id.btnCloseFullscreen);
        tvFullscreenHint = view.findViewById(R.id.tvFullscreenHint);
        btnAction = view.findViewById(R.id.btnAction);
        emergencyOverlay = view.findViewById(R.id.emergencyOverlay);

        Context ctx = requireContext();
        flashAnim = AnimationUtils.loadAnimation(ctx, R.anim.emergency_flash);
        pulseAnim = AnimationUtils.loadAnimation(ctx, R.anim.pulse);

        cameraExecutor = Executors.newSingleThreadExecutor();
        dbHelper = DatabaseHelper.getInstance(ctx);

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build();
        faceDetector = FaceDetection.getClient(options);

        aiCard.setOnClickListener(v -> {
            if (isMonitoring.get()) expandPreview();
        });
        btnCloseFullscreen.setOnClickListener(v -> collapsePreview());

        btnAction.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.btn_click));
            }
            return false;
        });

        btnAction.setOnClickListener(v -> {
            if (!isMonitoring.get()) startMonitoring();
            else stopMonitoring();
        });

        createNotificationChannel();

        return view;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context ctx = getContext();
            if (ctx == null) return;
            NotificationChannel channel = new NotificationChannel(
                    "GuardianEye_Channel",
                    "GuardianEye Status",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void updateStatusNotification(String title, String message) {
        Context ctx = getContext();
        if (ctx == null) return;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, "GuardianEye_Channel")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(title)
                .setContentText(message)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, builder.build());
    }

    private void cancelStatusNotification() {
        Context ctx = getContext();
        if (ctx == null) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(1);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (isPreviewExpanded) collapsePreview();
        stopSpeedTracking();
        cancelStatusNotification();

        if (isMonitoring.get() && sessionId >= 0) {
            int durationSec = (int) ((System.currentTimeMillis() - sessionStartTime) / 1000);
            dbHelper.endSession(sessionId, durationSec,
                    sessionWarningCount, sessionCriticalCount,
                    sessionTotalBlinks, sessionTotalYawns, sessionTotalDistractions);
            sessionId = -1;
        }
        isMonitoring.set(false);

        Activity activity = getActivity();
        if (activity != null) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        if (statusCircleFrame != null) statusCircleFrame.clearAnimation();
        if (emergencyOverlay != null) emergencyOverlay.clearAnimation();

        try {
            Context ctx = getContext();
            if (ctx != null) ProcessCameraProvider.getInstance(ctx).get().unbindAll();
        } catch (Exception e) {
            Log.e(Constants.TAG, "Error unbinding camera", e);
        }

        if (cameraExecutor != null) cameraExecutor.shutdown();
        releaseMediaPlayer();

        previewView = null;
        aiCard = null;
        statusText = null;
        eyeValueText = null;
        blinkRateText = null;
        statusCircleFrame = null;
        fullscreenOverlay = null;
        btnCloseFullscreen = null;
        tvFullscreenHint = null;
        btnAction = null;
        emergencyOverlay = null;
    }

    // ─── Media Player ────────────────────────────────────────────────────

    private void setupMediaPlayer() {
        releaseMediaPlayer();
        Context ctx = getContext();
        if (ctx == null) return;

        SharedPreferences prefs = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String soundChoice = prefs.getString(Constants.KEY_ALARM_SOUND, Constants.DEFAULT_ALARM_SOUND);

        int soundResId = R.raw.alarm1;
        switch (soundChoice) {
            case "Sound 2": soundResId = R.raw.alarm2; break;
            case "Sound 3": soundResId = R.raw.alarm3; break;
            case "Sound 4": soundResId = R.raw.alarm4; break;
        }

        mediaPlayer = MediaPlayer.create(ctx, soundResId);
        if (mediaPlayer != null) {
            float volume = prefs.getFloat(Constants.KEY_ALARM_VOLUME, Constants.DEFAULT_ALARM_VOLUME) / 100f;
            mediaPlayer.setVolume(volume, volume);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        }
    }

    private void playVoiceThenAlarm(int voiceResId) {
        Context ctx = getContext();
        if (ctx == null || !isMonitoring.get()) return;

        if (voicePlayer != null) {
            voicePlayer.release();
            voicePlayer = null;
        }

        voicePlayer = MediaPlayer.create(ctx, voiceResId);
        if (voicePlayer != null) {
            voicePlayer.setOnCompletionListener(mp -> {
                if (mediaPlayer != null && !mediaPlayer.isPlaying() && isMonitoring.get()) {
                    mediaPlayer.start();
                }
            });
            voicePlayer.start();
        } else {
            if (mediaPlayer != null && !mediaPlayer.isPlaying()) mediaPlayer.start();
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) { Log.e(Constants.TAG, "Error releasing MediaPlayer", e); }
            mediaPlayer = null;
        }
        if (voicePlayer != null) {
            try {
                if (voicePlayer.isPlaying()) voicePlayer.stop();
                voicePlayer.release();
            } catch (Exception e) { Log.e(Constants.TAG, "Error releasing voicePlayer", e); }
            voicePlayer = null;
        }
    }

    private void startMonitoring() {
        Context ctx = getContext();
        if (ctx == null) return;

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.SEND_SMS});
            return;
        }

        SharedPreferences prefs = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String sensitivity = prefs.getString(Constants.KEY_SENSITIVITY, Constants.DEFAULT_SENSITIVITY);
        activeEarThreshold = Constants.getEarThreshold(sensitivity);
        activeWarningDuration = Constants.getWarningDuration(sensitivity);

        smartDetectionEnabled = prefs.getBoolean(Constants.KEY_SMART_DETECTION_ENABLED, false);
        minSpeedGatingEnabled = smartDetectionEnabled && prefs.getBoolean(Constants.KEY_MIN_SPEED_ENABLED, false);
        minSpeedKmh = prefs.getFloat(Constants.KEY_MIN_SPEED_KMH, Constants.DEFAULT_MIN_SPEED_KMH);
        largestFaceOnly = smartDetectionEnabled && prefs.getBoolean(Constants.KEY_LARGEST_FACE_ONLY, false);

        isMonitoring.set(true);
        minuteStartTime = System.currentTimeMillis();
        sessionStartTime = System.currentTimeMillis();
        blinkCount = 0;
        sessionTotalBlinks = 0;
        sessionTotalYawns = 0;
        sessionTotalDistractions = 0;
        sessionWarningCount = 0;
        sessionCriticalCount = 0;
        warningFired = false;
        criticalFired = false;
        yawnFired = false;
        distractionFired = false;
        isDistracted = false;
        eyeClosedStartTime = 0;
        eyesWereClosed = false;
        sosSent = false;

        if (emergencyOverlay != null) {
            emergencyOverlay.clearAnimation();
            emergencyOverlay.setVisibility(View.GONE);
        }

        setupMediaPlayer();
        sessionId = dbHelper.startSession();
        Toast.makeText(ctx, getString(R.string.session_started, sessionId), Toast.LENGTH_SHORT).show();

        updateStatusNotification("GuardianEye: Running", "Monitoring eye alertness...");

        Activity activity = getActivity();
        if (activity != null) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (aiCard != null) aiCard.setForeground(null);
        if (btnAction != null) {
            btnAction.setText(R.string.stop_monitoring);
            btnAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.status_danger)));
        }
        if (statusCircleFrame != null) statusCircleFrame.startAnimation(pulseAnim);

        startCamera();
        if (minSpeedGatingEnabled) startSpeedTracking();
    }

    private void startSpeedTracking() {
        Context ctx = getContext();
        if (ctx == null) return;
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            minSpeedGatingEnabled = false;
            return;
        }

        fusedSpeedClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        speedCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (!isAdded() || locationResult.getLastLocation() == null) return;
                currentSpeedKmh = locationResult.getLastLocation().getSpeed() * 3.6f;
            }
        };

        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500).build();
        fusedSpeedClient.requestLocationUpdates(request, speedCallback, Looper.getMainLooper());
        isSpeedTrackingActive = true;
    }

    private void stopSpeedTracking() {
        if (fusedSpeedClient != null && speedCallback != null && isSpeedTrackingActive) {
            fusedSpeedClient.removeLocationUpdates(speedCallback);
            isSpeedTrackingActive = false;
        }
        currentSpeedKmh = 0f;
    }

    public boolean isCurrentlyMonitoring() { return isMonitoring.get(); }

    public Rect getPreviewCardRect() {
        if (aiCard == null) return new Rect();
        int[] loc = new int[2];
        aiCard.getLocationInWindow(loc);
        return new Rect(loc[0], loc[1], loc[0] + aiCard.getWidth(), loc[1] + aiCard.getHeight());
    }

    public void onPipModeChanged(boolean inPip) {
        isInPipMode = inPip;
        View root = getView();
        if (root == null || previewView == null) return;

        if (inPip) {
            if (isPreviewExpanded) collapsePreview();
            aiCard.removeView(previewView);
            ((ViewGroup) root).addView(previewView, new FrameLayout.LayoutParams(-1, -1));
            previewView.setElevation(300);
            aiCard.setVisibility(View.GONE);
            if (statusCircleFrame != null) statusCircleFrame.setVisibility(View.GONE);
            if (btnAction != null) btnAction.setVisibility(View.GONE);
            if (emergencyOverlay != null) emergencyOverlay.setVisibility(View.GONE);
            View stats = root.findViewById(R.id.statsContainer);
            if (stats != null) stats.setVisibility(View.GONE);
        } else {
            previewView.setElevation(0);
            ((ViewGroup) root).removeView(previewView);
            aiCard.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
            aiCard.setVisibility(View.VISIBLE);
            if (statusCircleFrame != null) statusCircleFrame.setVisibility(View.VISIBLE);
            if (btnAction != null) btnAction.setVisibility(View.VISIBLE);
            View stats = root.findViewById(R.id.statsContainer);
            if (stats != null) stats.setVisibility(View.VISIBLE);
        }
    }

    void stopMonitoring() {
        isMonitoring.set(false);
        stopSpeedTracking();
        cancelStatusNotification();

        if (sessionId >= 0) {
            int durationSec = (int) ((System.currentTimeMillis() - sessionStartTime) / 1000);
            dbHelper.endSession(sessionId, durationSec, sessionWarningCount, sessionCriticalCount,
                    sessionTotalBlinks, sessionTotalYawns, sessionTotalDistractions);
            Context ctx = getContext();
            if (ctx != null) Toast.makeText(ctx, getString(R.string.session_saved, durationSec), Toast.LENGTH_LONG).show();
            sessionId = -1;
        }

        Activity activity = getActivity();
        if (activity != null) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Context ctx = getContext();
        if (btnAction != null && ctx != null) {
            btnAction.setText(R.string.start_monitoring);
            btnAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent_blue)));
        }
        if (statusText != null && ctx != null) {
            statusText.setText(R.string.status_offline);
            statusText.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        }
        if (statusCircleFrame != null) {
            statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_border);
            statusCircleFrame.clearAnimation();
        }
        if (emergencyOverlay != null) {
            emergencyOverlay.clearAnimation();
            emergencyOverlay.setVisibility(View.GONE);
        }

        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
        if (voicePlayer != null && voicePlayer.isPlaying()) voicePlayer.pause();
        if (isPreviewExpanded) collapsePreview();
        if (aiCard != null && ctx != null) aiCard.setForeground(ContextCompat.getDrawable(ctx, R.drawable.camera_idle_border));

        try {
            if (ctx != null) ProcessCameraProvider.getInstance(ctx).get().unbindAll();
        } catch (Exception e) { Log.e(Constants.TAG, "Error unbinding camera", e); }
    }

    private void expandPreview() {
        if (isPreviewExpanded || previewView == null) return;
        isPreviewExpanded = true;
        aiCard.removeView(previewView);
        fullscreenOverlay.addView(previewView, 0, new FrameLayout.LayoutParams(-1, -1));
        fullscreenOverlay.setVisibility(View.VISIBLE);
        if (tvFullscreenHint != null) {
            tvFullscreenHint.setAlpha(1f);
            tvFullscreenHint.setVisibility(View.VISIBLE);
            tvFullscreenHint.postDelayed(() -> {
                if (tvFullscreenHint != null) tvFullscreenHint.animate().alpha(0f).setDuration(500).withEndAction(() -> {
                    if (tvFullscreenHint != null) tvFullscreenHint.setVisibility(View.GONE);
                }).start();
            }, 3000);
        }
        Activity activity = getActivity();
        if (activity != null) {
            View b = activity.findViewById(R.id.bottom_navigation), t = activity.findViewById(R.id.topBar), n = activity.findViewById(R.id.navDivider);
            if (b != null) b.setVisibility(View.GONE);
            if (t != null) t.setVisibility(View.GONE);
            if (n != null) n.setVisibility(View.GONE);
        }
    }

    private void collapsePreview() {
        if (!isPreviewExpanded || previewView == null) return;
        isPreviewExpanded = false;
        fullscreenOverlay.removeView(previewView);
        aiCard.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        fullscreenOverlay.setVisibility(View.GONE);
        Activity activity = getActivity();
        if (activity != null) {
            View b = activity.findViewById(R.id.bottom_navigation), t = activity.findViewById(R.id.topBar), n = activity.findViewById(R.id.navDivider);
            if (b != null) b.setVisibility(View.VISIBLE);
            if (t != null) t.setVisibility(View.VISIBLE);
            if (n != null) n.setVisibility(View.VISIBLE);
        }
    }

    private void startCamera() {
        Context ctx = getContext();
        if (ctx == null) return;
        ProcessCameraProvider.getInstance(ctx).addListener(() -> {
            try {
                if (!isAdded() || getContext() == null) return;
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(requireContext()).get();
                Preview preview = new Preview.Builder().build();
                if (previewView != null) preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);
                provider.bindToLifecycle(getViewLifecycleOwner(), CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
            } catch (Exception e) { Log.e(Constants.TAG, "Error starting camera", e); }
        }, ContextCompat.getMainExecutor(ctx));
    }

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (!isMonitoring.get() || imageProxy.getImage() == null) { imageProxy.close(); return; }
        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        faceDetector.process(image).addOnSuccessListener(faces -> {
            if (!isAdded() || getActivity() == null) { imageProxy.close(); return; }
            if (!faces.isEmpty()) {
                Face face = (largestFaceOnly && faces.size() > 1) ? selectLargestFace(faces) : faces.get(0);
                Float l = face.getLeftEyeOpenProbability(), r = face.getRightEyeOpenProbability();
                float e = ((l != null ? l : 1.0f) + (r != null ? r : 1.0f)) / 2.0f;
                float ty = face.getHeadEulerAngleY(), tx = face.getHeadEulerAngleX();
                boolean yawn = false;
                FaceLandmark mb = face.getLandmark(FaceLandmark.MOUTH_BOTTOM), nb = face.getLandmark(FaceLandmark.NOSE_BASE);
                if (mb != null && nb != null && (mb.getPosition().y - nb.getPosition().y) > (face.getBoundingBox().height() * Constants.YAWN_RATIO)) yawn = true;
                final boolean fy = yawn;
                Activity activity = getActivity();
                if (activity != null) activity.runOnUiThread(() -> {
                    if (eyeValueText != null) eyeValueText.setText(String.format("EAR: %.2f", e));
                    updateDriverState(e, ty, tx, fy);
                });
            }
            imageProxy.close();
        }).addOnFailureListener(e -> imageProxy.close());
    }

    private Face selectLargestFace(List<Face> faces) {
        Face largest = faces.get(0);
        int max = largest.getBoundingBox().width() * largest.getBoundingBox().height();
        for (Face f : faces) {
            int a = f.getBoundingBox().width() * f.getBoundingBox().height();
            if (a > max) { max = a; largest = f; }
        }
        return largest;
    }

    private void updateDriverState(float ear, float headTurnY, float headTiltX, boolean isYawning) {
        if (!isMonitoring.get() || statusText == null || statusCircleFrame == null) return;
        Context ctx = getContext(); if (ctx == null) return;

        if (minSpeedGatingEnabled && currentSpeedKmh < minSpeedKmh) {
            statusText.setText(R.string.detection_paused_stationary);
            statusText.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
            statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_border);
            eyeClosedStartTime = 0;
            if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
            if (voicePlayer != null && voicePlayer.isPlaying()) voicePlayer.pause();
            updateStatusNotification("GuardianEye:Paused", "Stationary" +
                    "-detection paused.");
            return;
        }

        long currentTime = System.currentTimeMillis();

        if (headTurnY > Constants.HEAD_TURN_THRESHOLD || headTurnY < -Constants.HEAD_TURN_THRESHOLD || headTiltX < Constants.HEAD_TILT_THRESHOLD) {
            if (!isDistracted) { distractionStartTime = currentTime; isDistracted = true; }
            else if (currentTime - distractionStartTime >= Constants.DISTRACTION_DURATION_MS) {
                statusText.setText(R.string.status_distracted);
                statusText.setTextColor(ContextCompat.getColor(ctx, R.color.status_warning));
                statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_yellow);
                if (!distractionFired) {
                    sessionTotalDistractions++;
                    distractionFired = true;
                    playVoiceThenAlarm(R.raw.voice_focus);
                }
                updateStatusNotification("GuardianEye: ALERT", "Eyes off the road!");
                return;
            }
        } else { isDistracted = false; distractionFired = false; }

        if (isYawning) {
            if (!yawnFired) {
                sessionTotalYawns++;
                yawnFired = true;
                playVoiceThenAlarm(R.raw.voice_yawn);
            }
            statusText.setText(R.string.status_yawning);
            statusText.setTextColor(ContextCompat.getColor(ctx, R.color.status_warning));
            statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_yellow);
            updateStatusNotification("GuardianEye: ALERT", "Drowsiness (Yawning) detected!");
            return;
        } else { yawnFired = false; }

        if (ear < activeEarThreshold) {
            eyesWereClosed = true;
            if (eyeClosedStartTime == 0) eyeClosedStartTime = currentTime;
            long closedDuration = currentTime - eyeClosedStartTime;
            if (closedDuration >= Constants.CRITICAL_DURATION_MS) {
                statusText.setText(R.string.status_pull_over);
                statusText.setTextColor(ContextCompat.getColor(ctx, R.color.status_danger));
                statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_red);
                if (emergencyOverlay != null && emergencyOverlay.getVisibility() == View.GONE) { emergencyOverlay.setVisibility(View.VISIBLE); emergencyOverlay.startAnimation(flashAnim); }
                if (!criticalFired) {
                    criticalFired = true; sessionCriticalCount++;
                    playVoiceThenAlarm(R.raw.voice_sos);
                }
                if (!sosSent) { sendEmergencySOS(); sosSent = true; }
                updateStatusNotification("GuardianEye: CRITICAL", "Driver unresponsive! Pull over!");
            } else if (closedDuration >= activeWarningDuration) {
                statusText.setText(R.string.status_wake_up);
                statusText.setTextColor(ContextCompat.getColor(ctx, R.color.status_warning));
                statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_yellow);
                if (!warningFired) {
                    warningFired = true; sessionWarningCount++;
                    playVoiceThenAlarm(R.raw.voice_break);
                }
                updateStatusNotification("GuardianEye: WARNING", "Drowsiness detected! Wake up!");
            }
        } else {
            if (eyesWereClosed) { blinkCount++; sessionTotalBlinks++; eyesWereClosed = false; warningFired = false; criticalFired = false; sosSent = false; }
            eyeClosedStartTime = 0;
            statusText.setText(R.string.status_attentive);
            statusText.setTextColor(ContextCompat.getColor(ctx, R.color.status_safe));
            statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_border);
            if (emergencyOverlay != null && emergencyOverlay.getVisibility() == View.VISIBLE) { emergencyOverlay.clearAnimation(); emergencyOverlay.setVisibility(View.GONE); }
            if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
            if (voicePlayer != null && voicePlayer.isPlaying()) voicePlayer.pause();
            updateStatusNotification("GuardianEye: Running", "Monitoring eye alertness...");
        }

        if (currentTime - minuteStartTime >= Constants.BLINK_RATE_WINDOW_MS) {
            if (blinkRateText != null) blinkRateText.setText(getString(R.string.blink_rate_format, blinkCount));
            blinkCount = 0; minuteStartTime = currentTime;
        }
    }

    private void sendEmergencySOS() {
        Context ctx = getContext(); Activity activity = getActivity(); if (ctx == null || activity == null) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
            String emergencyNumber = prefs.getString(Constants.KEY_EMERGENCY_NUMBER, "");
            if (emergencyNumber.isEmpty()) { activity.runOnUiThread(() -> Toast.makeText(ctx, R.string.sos_failed_no_number, Toast.LENGTH_LONG).show()); return; }
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            Location location = null;
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (location == null) location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            String mapsLink = location != null ? "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude() : "Location unavailable";
            String message = "EMERGENCY: Driver is unresponsive! Last known location: " + mapsLink;
            SmsManager.getDefault().sendTextMessage(emergencyNumber, null, message, null, null);
            final String displayNumber = emergencyNumber;
            activity.runOnUiThread(() -> {
                if (!isAdded() || getContext() == null || isInPipMode) return;
                new android.app.AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(R.string.sos_dialog_title).setMessage(getString(R.string.sos_dialog_message, displayNumber)).setPositiveButton(R.string.dismiss, (dialog, which) -> dialog.dismiss()).setIcon(android.R.drawable.ic_dialog_alert).show();
            });
        } catch (Exception e) { Log.e(Constants.TAG, "Failed to send SOS", e); activity.runOnUiThread(() -> { Context c = getContext(); if (c != null) Toast.makeText(c, R.string.sos_failed, Toast.LENGTH_SHORT).show(); }); }
    }
}