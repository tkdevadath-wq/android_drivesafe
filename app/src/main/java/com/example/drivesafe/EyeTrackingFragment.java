package com.example.drivesafe;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EyeTrackingFragment extends Fragment {

    private PreviewView previewView;
    private TextView statusText, eyeValueText, blinkRateText;
    private FrameLayout statusCircleFrame;
    private Button btnAction;

    private FaceDetector faceDetector;
    private MediaPlayer mediaPlayer;
    private ExecutorService cameraExecutor;
    private DatabaseHelper dbHelper;

    private boolean isMonitoring = false;
    private int blinkCount = 0;
    private boolean eyesWereClosed = false;
    private long minuteStartTime = 0;
    private long eyeClosedStartTime = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_eye_tracking, container, false);

        previewView = view.findViewById(R.id.previewView);
        statusText = view.findViewById(R.id.statusText);
        eyeValueText = view.findViewById(R.id.eyeValueText);
        blinkRateText = view.findViewById(R.id.blinkRateText);
        statusCircleFrame = view.findViewById(R.id.statusCircleFrame);
        btnAction = view.findViewById(R.id.btnAction);

        cameraExecutor = Executors.newSingleThreadExecutor();
        mediaPlayer = MediaPlayer.create(getContext(), R.raw.alarm);
        dbHelper = new DatabaseHelper(getContext());

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build();

        faceDetector = FaceDetection.getClient(options);

        btnAction.setOnClickListener(v -> {
            if (!isMonitoring) startMonitoring();
            else stopMonitoring();
        });

        return view;
    }

    private void startMonitoring() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {

            isMonitoring = true;
            minuteStartTime = System.currentTimeMillis();
            btnAction.setText("STOP MONITORING");
            btnAction.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.RED));

            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 100);
        }
    }

    private void stopMonitoring() {
        isMonitoring = false;

        btnAction.setText("START MONITORING");
        btnAction.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#2196F3")));

        statusText.setText("OFFLINE");
        statusText.setTextColor(Color.WHITE);
        statusCircleFrame.setBackgroundResource(R.drawable.circular_neon_border);

        if (mediaPlayer != null && mediaPlayer.isPlaying())
            mediaPlayer.pause();

        try {
            ProcessCameraProvider.getInstance(requireContext())
                    .get().unbindAll();
        } catch (Exception ignored) {}
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(requireContext()).addListener(() -> {
            try {
                ProcessCameraProvider provider =
                        ProcessCameraProvider.getInstance(requireContext()).get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                provider.bindToLifecycle(
                        getViewLifecycleOwner(),
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void analyzeImage(@NonNull ImageProxy imageProxy) {

        if (!isMonitoring || imageProxy.getImage() == null) {
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

                        Face face = faces.get(0);

                        float left = face.getLeftEyeOpenProbability() != null
                                ? face.getLeftEyeOpenProbability() : 1.0f;

                        float right = face.getRightEyeOpenProbability() != null
                                ? face.getRightEyeOpenProbability() : 1.0f;

                        float eyeAvg = (left + right) / 2.0f;

                        requireActivity().runOnUiThread(() -> {
                            eyeValueText.setText(
                                    String.format("EAR: %.2f", eyeAvg));
                            updateBlinkAndFatigue(eyeAvg);
                        });
                    }

                    imageProxy.close();

                }).addOnFailureListener(e -> imageProxy.close());
    }

    private void updateBlinkAndFatigue(float ear) {

        if (ear < 0.25) {

            eyesWereClosed = true;

            if (eyeClosedStartTime == 0)
                eyeClosedStartTime = System.currentTimeMillis();

            long closedDuration =
                    System.currentTimeMillis() - eyeClosedStartTime;

            if (closedDuration >= 10000) {

                statusText.setText("PULL OVER!");
                statusText.setTextColor(Color.RED);
                statusCircleFrame.setBackgroundResource(
                        R.drawable.circular_neon_red);

                if (!mediaPlayer.isPlaying())
                    mediaPlayer.start();

                dbHelper.addLog("FATIGUE", "CRITICAL (10s)");

            } else if (closedDuration >= 2000) {

                statusText.setText("WAKE UP!");
                statusText.setTextColor(Color.YELLOW);
                statusCircleFrame.setBackgroundResource(
                        R.drawable.circular_neon_yellow);

                if (!mediaPlayer.isPlaying())
                    mediaPlayer.start();
            }

        } else {

            if (eyesWereClosed) {
                blinkCount++;
                eyesWereClosed = false;
            }

            eyeClosedStartTime = 0;

            statusText.setText("ATTENTIVE");
            statusText.setTextColor(Color.GREEN);
            statusCircleFrame.setBackgroundResource(
                    R.drawable.circular_neon_border);

            if (mediaPlayer.isPlaying())
                mediaPlayer.pause();
        }

        if (System.currentTimeMillis() - minuteStartTime >= 60000) {
            blinkRateText.setText("BLINK: " + blinkCount + "/min");
            blinkCount = 0;
            minuteStartTime = System.currentTimeMillis();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        isMonitoring = false;

        try {
            ProcessCameraProvider.getInstance(requireContext())
                    .get().unbindAll();
        } catch (Exception ignored) {}

        cameraExecutor.shutdown();

        if (mediaPlayer != null)
            mediaPlayer.release();
    }
}