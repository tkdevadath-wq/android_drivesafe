package com.example.drivesafe;

import android.Manifest;
import android.app.Activity;
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
    private ExecutorService cameraExecutor;
    private DatabaseHelper dbHelper;

    // ─── Animations ──────────────────────────────────────────────────────
    private Animation pulseAnim;
    private Animation flashAnim;

    // ─── Thread-safe monitoring flag ─────────────────────────────────────
    // Read on camera executor thread, written on main thread
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

    // ─── Smart Detection: minimum speed gating ───────────────────────────
    private boolean smartDetectionEnabled = false;
    private boolean minSpeedGatingEnabled = false;
    private float minSpeedKmh = Constants.DEFAULT_MIN_SPEED_KMH;
    private volatile float currentSpeedKmh = 0f;
    private FusedLocationProviderClient fusedSpeedClient;
    private LocationCallback speedCallback;
    private boolean isSpeedTrackingActive = false;

    // ─── Smart Detection: largest face priority ──────────────────────────
    private boolean largestFaceOnly = false;

    // ─── Permission Launcher (replaces deprecated requestPermissions) ────
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
                                Toast.makeText(ctx,
                                        R.string.permissions_required,
                                        Toast.LENGTH_LONG).show();
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

        // Tap mini preview to expand fullscreen (only during monitoring)
        aiCard.setOnClickListener(v -> {
            if (isMonitoring.get()) expandPreview();
        });
        btnCloseFullscreen.setOnClickListener(v -> collapsePreview());

        // Button bounce animation
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

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Collapse fullscreen if still expanded
        if (isPreviewExpanded) collapsePreview();

        // Stop speed tracking
        stopSpeedTracking();

        // Save session if still monitoring
        if (isMonitoring.get() && sessionId >= 0) {
            int durationSec = (int) ((System.currentTimeMillis() - sessionStartTime) / 1000);
            dbHelper.endSession(sessionId, durationSec,
                    sessionWarningCount, sessionCriticalCount,
                    sessionTotalBlinks, sessionTotalYawns, sessionTotalDistractions);
            sessionId = -1;
        }
        isMonitoring.set(false);

        // Clear keep-screen-on
        Activity activity = getActivity();
        if (activity != null) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Stop animations to prevent leaks
        if (statusCircleFrame != null) statusCircleFrame.clearAnimation();
        if (emergencyOverlay != null) emergencyOverlay.clearAnimation();

        // Unbind camera
        try {
            Context ctx = getContext();
            if (ctx != null) {
                ProcessCameraProvider.getInstance(ctx).get().unbindAll();
            }
        } catch (Exception e) {
            Log.e(Constants.TAG, "Error unbinding camera", e);
        }

        if (cameraExecutor != null) cameraExecutor.shutdown();
        releaseMediaPlayer();

        // Null out views to prevent leaks
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

        SharedPreferences prefs = ctx.getSharedPreferences(
                Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String soundChoice = prefs.getString(Constants.KEY_ALARM_SOUND,
                Constants.DEFAULT_ALARM_SOUND);

        int soundResId = R.raw.alarm1;
        switch (soundChoice) {
            case "Sound 2": soundResId = R.raw.alarm2; break;
            case "Sound 3": soundResId = R.raw.alarm3; break;
            case "Sound 4": soundResId = R.raw.alarm4; break;
        }

        mediaPlayer = MediaPlayer.create(ctx, soundResId);
        if (mediaPlayer != null) {
            float volume = prefs.getFloat(Constants.KEY_ALARM_VOLUME,
                    Constants.DEFAULT_ALARM_VOLUME) / 100f;
            mediaPlayer.setVolume(volume, volume);

            // Set audio stream to ALARM for reliable loud playback
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());

            // Also set device alarm stream volume proportionally
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                int targetVol = Math.round(volume * maxVol);
                am.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0);
            }
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(Constants.TAG, "Error releasing MediaPlayer", e);
            }
            mediaPlayer = null;
        }
    }

    private void startMonitoring() {
        Context ctx = getContext();
        if (ctx == null) return;

        // Check permissions
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.SEND_SMS
            });
            return;
        }

        // ── Load settings-driven parameters ──────────────────────────────
        SharedPreferences prefs = ctx.getSharedPreferences(
                Constants.PREFS_NAME, Context.MODE_PRIVATE);

        // Sensitivity
        String sensitivity = prefs.getString(
                Constants.KEY_SENSITIVITY, Constants.DEFAULT_SENSITIVITY);
        activeEarThreshold = Constants.getEarThreshold(sensitivity);
        activeWarningDuration = Constants.getWarningDuration(sensitivity);

        // Smart Detection
        smartDetectionEnabled = prefs.getBoolean(
                Constants.KEY_SMART_DETECTION_ENABLED, false);
        minSpeedGatingEnabled = smartDetectionEnabled
                && prefs.getBoolean(Constants.KEY_MIN_SPEED_ENABLED, false);
        minSpeedKmh = prefs.getFloat(
                Constants.KEY_MIN_SPEED_KMH, Constants.DEFAULT_MIN_SPEED_KMH);
        largestFaceOnly = smartDetectionEnabled
                && prefs.getBoolean(Constants.KEY_LARGEST_FACE_ONLY, false);

        // Reset all state
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

        // Hide emergency overlay
        if (emergencyOverlay != null) {
            emergencyOverlay.clearAnimation();
            emergencyOverlay.setVisibility(View.GONE);
        }

        setupMediaPlayer();

        sessionId = dbHelper.startSession();
        Toast.makeText(ctx, getString(R.string.session_started, sessionId),
                Toast.LENGTH_SHORT).show();

        // Keep screen on during monitoring (critical for driving safety)
        Activity activity = getActivity();
        if (activity != null) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Remove idle border — camera preview is now visible
        if (aiCard != null) {
            aiCard.setForeground(null);
        }

        // Update UI
        if (btnAction != null) {
            btnAction.setText(R.string.stop_monitoring);
            btnAction.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(ctx, R.color.status_danger)));
        }
        if (statusCircleFrame != null) {
            statusCircleFrame.startAnimation(pulseAnim);
        }

        startCamera();

        // Start GPS speed tracking if minimum-speed gating is enabled
        if (minSpeedGatingEnabled) {
            startSpeedTracking();
        }
    }

    // ─── Speed Tracking for Smart Detection ─────────────────────────────

    private void startSpeedTracking() {
        Context ctx = getContext();
        if (ctx == null) return;

        if (ActivityCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Speed gating silently disabled if no location permission
            Log.w(Constants.TAG, "No location permission — speed gating disabled");
            minSpeedGatingEnabled = false;
            return;
        }

        fusedSpeedClient = LocationServices.getFusedLocationProviderClient(
                requireActivity());

        speedCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (!isAdded() || locationResult.getLastLocation() == null) return;
                currentSpeedKmh = locationResult.getLastLocation().getSpeed() * 3.6f;
            }
        };

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .build();

        fusedSpeedClient.requestLocationUpdates(
                request, speedCallback, Looper.getMainLooper());
        isSpeedTrackingActive = true;
    }

    private void stopSpeedTracking() {
        if (fusedSpeedClient != null && speedCallback != null && isSpeedTrackingActive) {
            fusedSpeedClient.removeLocationUpdates(speedCallback);
            isSpeedTrackingActive = false;
        }
        currentSpeedKmh = 0f;
    }

    // ─── Public PiP Helpers ─────────────────────────────────────────────

    /** Whether detection is currently running. Used by MainActivity to decide PiP entry. */
    public boolean isCurrentlyMonitoring() {
        return isMonitoring.get();
    }

    /**
     * Returns the screen-coordinate Rect of the mini camera card.
     * Used as the source rect hint for the PiP morph animation.
     */
    public Rect getPreviewCardRect() {
        if (aiCard == null) return new Rect();
        int[] loc = new int[2];
        aiCard.getLocationInWindow(loc);
        return new Rect(loc[0], loc[1],
                loc[0] + aiCard.getWidth(),
                loc[1] + aiCard.getHeight());
    }

    /**
     * Called by MainActivity when PiP mode changes.
     * Entering PiP: collapse fullscreen if open, hide non-camera UI,
     *   reparent PreviewView to fill the entire fragment root.
     * Leaving PiP: restore everything back to normal layout.
     */
    public void onPipModeChanged(boolean inPip) {
        isInPipMode = inPip;
        View root = getView();
        if (root == null || previewView == null) return;

        if (inPip) {
            // Collapse fullscreen overlay first if it was open
            if (isPreviewExpanded) collapsePreview();

            // Move PreviewView from ai_card → fragment root (fills PiP window)
            aiCard.removeView(previewView);
            ((ViewGroup) root).addView(previewView,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
            previewView.setElevation(300); // Above everything

            // Hide all non-camera UI
            aiCard.setVisibility(View.GONE);
            if (statusCircleFrame != null) statusCircleFrame.setVisibility(View.GONE);
            if (btnAction != null) btnAction.setVisibility(View.GONE);
            if (emergencyOverlay != null) emergencyOverlay.setVisibility(View.GONE);
            // statsContainer is a sibling in the ConstraintLayout
            View stats = root.findViewById(R.id.statsContainer);
            if (stats != null) stats.setVisibility(View.GONE);
        } else {
            // Restore PreviewView back into ai_card
            previewView.setElevation(0);
            ((ViewGroup) root).removeView(previewView);
            aiCard.addView(previewView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            // Show all UI again
            aiCard.setVisibility(View.VISIBLE);
            if (statusCircleFrame != null) statusCircleFrame.setVisibility(View.VISIBLE);
            if (btnAction != null) btnAction.setVisibility(View.VISIBLE);
            View stats = root.findViewById(R.id.statsContainer);
            if (stats != null) stats.setVisibility(View.VISIBLE);
        }
    }

    void stopMonitoring() {
        isMonitoring.set(false);

        // Stop speed tracking if it was active
        stopSpeedTracking();

        if (sessionId >= 0) {
            int durationSec = (int) ((System.currentTimeMillis() - sessionStartTime) / 1000);
            dbHelper.endSession(sessionId, durationSec,
                    sessionWarningCount, sessionCriticalCount,
                    sessionTotalBlinks, sessionTotalYawns, sessionTotalDistractions);
            Context ctx = getContext();
            if (ctx != null) {
                Toast.makeText(ctx, getString(R.string.session_saved, durationSec),
                        Toast.LENGTH_LONG).show();
            }
            sessionId = -1;
        }

        // Clear keep-screen-on
        Activity activity = getActivity();
        if (activity != null) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Update UI (null-safe)
        Context ctx = getContext();
        if (btnAction != null && ctx != null) {
            btnAction.setText(R.string.start_monitoring);
            btnAction.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(ctx, R.color.accent_blue)));
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

        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }

        // Collapse fullscreen if open
        if (isPreviewExpanded) collapsePreview();

        // Re-show idle border — camera preview is gone
        if (aiCard != null && ctx != null) {
            aiCard.setForeground(ContextCompat.getDrawable(ctx,
                    R.drawable.camera_idle_border));
        }

        // Unbind camera
        try {
            if (ctx != null) {
                ProcessCameraProvider.getInstance(ctx).get().unbindAll();
            }
        } catch (Exception e) {
            Log.e(Constants.TAG, "Error unbinding camera on stop", e);
        }
    }

    // ─── Fullscreen Preview Expand / Collapse ────────────────────────────

    /**
     * Reparent the PreviewView into the fullscreen overlay.
     * The camera binding (Preview + ImageAnalysis) stays intact — detection
     * continues uninterrupted because the surface provider doesn't change.
     */
    private void expandPreview() {
        if (isPreviewExpanded || previewView == null) return;
        isPreviewExpanded = true;

        // Move PreviewView from ai_card → fullscreenOverlay
        aiCard.removeView(previewView);
        fullscreenOverlay.addView(previewView, 0,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        fullscreenOverlay.setVisibility(View.VISIBLE);

        // Reset & show hint
        if (tvFullscreenHint != null) {
            tvFullscreenHint.setAlpha(1f);
            tvFullscreenHint.setVisibility(View.VISIBLE);
            tvFullscreenHint.postDelayed(() -> {
                if (tvFullscreenHint != null) {
                    tvFullscreenHint.animate()
                            .alpha(0f)
                            .setDuration(500)
                            .withEndAction(() -> {
                                if (tvFullscreenHint != null)
                                    tvFullscreenHint.setVisibility(View.GONE);
                            }).start();
                }
            }, 3000);
        }

        // Hide bottom nav & top bar so the preview is truly fullscreen
        Activity activity = getActivity();
        if (activity != null) {
            View bottomNav = activity.findViewById(R.id.bottom_navigation);
            View topBar = activity.findViewById(R.id.topBar);
            View navDivider = activity.findViewById(R.id.navDivider);
            if (bottomNav != null) bottomNav.setVisibility(View.GONE);
            if (topBar != null) topBar.setVisibility(View.GONE);
            if (navDivider != null) navDivider.setVisibility(View.GONE);
        }
    }

    /**
     * Move PreviewView back into the mini card and restore normal UI.
     */
    private void collapsePreview() {
        if (!isPreviewExpanded || previewView == null) return;
        isPreviewExpanded = false;

        // Move PreviewView from fullscreenOverlay → ai_card
        fullscreenOverlay.removeView(previewView);
        aiCard.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        fullscreenOverlay.setVisibility(View.GONE);

        // Restore bottom nav & top bar
        Activity activity = getActivity();
        if (activity != null) {
            View bottomNav = activity.findViewById(R.id.bottom_navigation);
            View topBar = activity.findViewById(R.id.topBar);
            View navDivider = activity.findViewById(R.id.navDivider);
            if (bottomNav != null) bottomNav.setVisibility(View.VISIBLE);
            if (topBar != null) topBar.setVisibility(View.VISIBLE);
            if (navDivider != null) navDivider.setVisibility(View.VISIBLE);
        }
    }

    // ─── Camera ──────────────────────────────────────────────────────────

    private void startCamera() {
        Context ctx = getContext();
        if (ctx == null) return;

        ProcessCameraProvider.getInstance(ctx).addListener(() -> {
            try {
                if (!isAdded() || getContext() == null) return;

                ProcessCameraProvider provider =
                        ProcessCameraProvider.getInstance(requireContext()).get();
                Preview preview = new Preview.Builder().build();

                if (previewView != null) {
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                }

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                provider.bindToLifecycle(getViewLifecycleOwner(),
                        CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
            } catch (Exception e) {
                Log.e(Constants.TAG, "Error starting camera", e);
            }
        }, ContextCompat.getMainExecutor(ctx));
    }

    // ─── Image Analysis (runs on cameraExecutor thread) ──────────────────

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (!isMonitoring.get() || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees());

        faceDetector.process(image)
                .addOnSuccessListener(faces -> {
                    if (!isAdded() || getActivity() == null) {
                        imageProxy.close();
                        return;
                    }

                    if (!faces.isEmpty()) {
                        // Pick the face to analyze: largest bounding box or first
                        Face face;
                        if (largestFaceOnly && faces.size() > 1) {
                            face = selectLargestFace(faces);
                        } else {
                            face = faces.get(0);
                        }

                        Float leftProb = face.getLeftEyeOpenProbability();
                        Float rightProb = face.getRightEyeOpenProbability();
                        float left = leftProb != null ? leftProb : 1.0f;
                        float right = rightProb != null ? rightProb : 1.0f;
                        float eyeAvg = (left + right) / 2.0f;

                        float headTurnY = face.getHeadEulerAngleY();
                        float headTiltX = face.getHeadEulerAngleX();

                        boolean isYawning = false;
                        FaceLandmark mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM);
                        FaceLandmark noseBase = face.getLandmark(FaceLandmark.NOSE_BASE);
                        if (mouthBottom != null && noseBase != null) {
                            float mouthGap = mouthBottom.getPosition().y
                                    - noseBase.getPosition().y;
                            if (mouthGap > (face.getBoundingBox().height()
                                    * Constants.YAWN_RATIO)) {
                                isYawning = true;
                            }
                        }

                        final boolean finalIsYawning = isYawning;

                        Activity activity = getActivity();
                        if (activity != null) {
                            activity.runOnUiThread(() -> {
                                if (eyeValueText != null) {
                                    eyeValueText.setText(
                                            String.format("EAR: %.2f", eyeAvg));
                                }
                                updateDriverState(eyeAvg, headTurnY,
                                        headTiltX, finalIsYawning);
                            });
                        }
                    }
                    imageProxy.close();
                })
                .addOnFailureListener(e -> imageProxy.close());
    }

    /**
     * Picks the face with the largest bounding-box area (closest to camera = driver).
     */
    private Face selectLargestFace(List<Face> faces) {
        Face largest = faces.get(0);
        int maxArea = largest.getBoundingBox().width() * largest.getBoundingBox().height();
        for (int i = 1; i < faces.size(); i++) {
            Face f = faces.get(i);
            int area = f.getBoundingBox().width() * f.getBoundingBox().height();
            if (area > maxArea) {
                maxArea = area;
                largest = f;
            }
        }
        return largest;
    }

    // ─── Driver State Logic (main thread) ────────────────────────────────

    private void updateDriverState(float ear, float headTurnY,
                                   float headTiltX, boolean isYawning) {
        // Ignore stale frames that arrive after monitoring stopped
        if (!isMonitoring.get()) return;
        // Guard: views might be null if fragment is being destroyed
        if (statusText == null || statusCircleFrame == null) return;
        Context ctx = getContext();
        if (ctx == null) return;

        // --- Speed Gating: pause detection when vehicle is below minimum speed ---
        if (minSpeedGatingEnabled && currentSpeedKmh < minSpeedKmh) {
            statusText.setText(R.string.detection_paused_stationary);
            statusText.setTextColor(
                    ContextCompat.getColor(ctx, R.color.text_secondary));
            statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_border);
            // Reset eye closure timing so it doesn't accumulate while paused
            eyeClosedStartTime = 0;
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
            return;
        }

        long currentTime = System.currentTimeMillis();

        // --- Distraction Detection ---
        if (headTurnY > Constants.HEAD_TURN_THRESHOLD
                || headTurnY < -Constants.HEAD_TURN_THRESHOLD
                || headTiltX < Constants.HEAD_TILT_THRESHOLD) {
            if (!isDistracted) {
                distractionStartTime = currentTime;
                isDistracted = true;
            } else if (currentTime - distractionStartTime
                    >= Constants.DISTRACTION_DURATION_MS) {
                statusText.setText(R.string.status_distracted);
                statusText.setTextColor(
                        ContextCompat.getColor(ctx, R.color.status_warning));
                statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_yellow);

                if (!distractionFired) {
                    sessionTotalDistractions++;
                    distractionFired = true;
                }
                if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                return;
            }
        } else {
            isDistracted = false;
            distractionFired = false;
        }

        // --- Yawn Detection ---
        if (isYawning) {
            if (!yawnFired) {
                sessionTotalYawns++;
                yawnFired = true;
            }
            statusText.setText(R.string.status_yawning);
            statusText.setTextColor(
                    ContextCompat.getColor(ctx, R.color.status_warning));
            statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_yellow);
            return;
        } else {
            yawnFired = false;
        }

        // --- Eye Closure Detection ---
        if (ear < activeEarThreshold) {
            eyesWereClosed = true;
            if (eyeClosedStartTime == 0) eyeClosedStartTime = currentTime;
            long closedDuration = currentTime - eyeClosedStartTime;

            if (closedDuration >= Constants.CRITICAL_DURATION_MS) {
                // CRITICAL: PULL OVER
                statusText.setText(R.string.status_pull_over);
                statusText.setTextColor(
                        ContextCompat.getColor(ctx, R.color.status_danger));
                statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_red);

                // Trigger emergency flash overlay
                if (emergencyOverlay != null
                        && emergencyOverlay.getVisibility() == View.GONE) {
                    emergencyOverlay.setVisibility(View.VISIBLE);
                    emergencyOverlay.startAnimation(flashAnim);
                }

                if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                if (!criticalFired) {
                    criticalFired = true;
                    sessionCriticalCount++;
                }
                if (!sosSent) {
                    sendEmergencySOS();
                    sosSent = true;
                }

            } else if (closedDuration >= activeWarningDuration) {
                // WARNING: WAKE UP
                statusText.setText(R.string.status_wake_up);
                statusText.setTextColor(
                        ContextCompat.getColor(ctx, R.color.status_warning));
                statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_yellow);
                if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                if (!warningFired) {
                    warningFired = true;
                    sessionWarningCount++;
                }
            }
        } else {
            // DRIVER IS AWAKE
            if (eyesWereClosed) {
                blinkCount++;
                sessionTotalBlinks++;
                eyesWereClosed = false;
                warningFired = false;
                criticalFired = false;
                sosSent = false;
            }
            eyeClosedStartTime = 0;

            statusText.setText(R.string.status_attentive);
            statusText.setTextColor(
                    ContextCompat.getColor(ctx, R.color.status_safe));
            statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_border);

            // Stop emergency flash
            if (emergencyOverlay != null
                    && emergencyOverlay.getVisibility() == View.VISIBLE) {
                emergencyOverlay.clearAnimation();
                emergencyOverlay.setVisibility(View.GONE);
            }

            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        }

        // --- Blink Rate Update ---
        if (currentTime - minuteStartTime >= Constants.BLINK_RATE_WINDOW_MS) {
            if (blinkRateText != null) {
                blinkRateText.setText(
                        getString(R.string.blink_rate_format, blinkCount));
            }
            blinkCount = 0;
            minuteStartTime = currentTime;
        }
    }

    // ─── Emergency SOS ───────────────────────────────────────────────────

    private void sendEmergencySOS() {
        Context ctx = getContext();
        Activity activity = getActivity();
        if (ctx == null || activity == null) return;

        try {
            SharedPreferences prefs = ctx.getSharedPreferences(
                    Constants.PREFS_NAME, Context.MODE_PRIVATE);
            String emergencyNumber = prefs.getString(
                    Constants.KEY_EMERGENCY_NUMBER, "");

            if (emergencyNumber.isEmpty()) {
                activity.runOnUiThread(() ->
                        Toast.makeText(ctx,
                                R.string.sos_failed_no_number,
                                Toast.LENGTH_LONG).show()
                );
                return;
            }

            // Get last known location
            LocationManager lm = (LocationManager)
                    ctx.getSystemService(Context.LOCATION_SERVICE);
            Location location = null;

            if (ContextCompat.checkSelfPermission(ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (location == null) {
                    location = lm.getLastKnownLocation(
                            LocationManager.NETWORK_PROVIDER);
                }
            }

            String mapsLink = "Location unavailable";
            if (location != null) {
                mapsLink = "https://maps.google.com/?q="
                        + location.getLatitude() + "," + location.getLongitude();
            }

            String message = "EMERGENCY: Driver is unresponsive! Last known location: "
                    + mapsLink;

            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(emergencyNumber, null, message, null, null);
            Log.d(Constants.TAG, "SOS SMS sent to " + emergencyNumber);

            // Show emergency dialog on UI thread (skip in PiP — dialogs crash there)
            final String displayNumber = emergencyNumber;
            activity.runOnUiThread(() -> {
                if (!isAdded() || getContext() == null || isInPipMode) return;
                new android.app.AlertDialog.Builder(ctx,
                        android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(R.string.sos_dialog_title)
                        .setMessage(getString(R.string.sos_dialog_message,
                                displayNumber))
                        .setPositiveButton(R.string.dismiss,
                                (dialog, which) -> dialog.dismiss())
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
            });

        } catch (Exception e) {
            Log.e(Constants.TAG, "Failed to send SOS", e);
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Context c = getContext();
                    if (c != null) {
                        Toast.makeText(c, R.string.sos_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    }
}