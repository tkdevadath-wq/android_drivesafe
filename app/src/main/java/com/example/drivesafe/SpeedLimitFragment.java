package com.example.drivesafe;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.slider.Slider;

public class SpeedLimitFragment extends Fragment {

    private static final String PREFS_NAME = "drivesafe_prefs";
    private static final String KEY_SPEED_LIMIT = "speed_limit";
    private static final float DEFAULT_SPEED_LIMIT = 80.0f;
    private static final long SPEED_LOG_COOLDOWN_MS = 30_000; // 30 seconds between DB logs

    private TextView speedValue, speedLimitLabel, speedStatus;
    private Slider speedSlider;

    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
    private MediaPlayer mediaPlayer;
    private DatabaseHelper dbHelper;

    private float userSpeedLimit = DEFAULT_SPEED_LIMIT;
    private boolean isOverLimit = false;
    private long lastSpeedLogTime = 0;
    private boolean isTrackingActive = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_speed_limit, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        speedValue = view.findViewById(R.id.speedValue);
        speedLimitLabel = view.findViewById(R.id.speedLimitLabel);
        speedStatus = view.findViewById(R.id.speedStatus);
        speedSlider = view.findViewById(R.id.speedSlider);

        dbHelper = DatabaseHelper.getInstance(requireContext());
        mediaPlayer = MediaPlayer.create(getContext(), R.raw.alarm);

        fusedClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        // Load persisted speed limit
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        userSpeedLimit = prefs.getFloat(KEY_SPEED_LIMIT, DEFAULT_SPEED_LIMIT);

        // Round to nearest 5 for slider compatibility
        float sliderValue = Math.round(userSpeedLimit / 5.0f) * 5.0f;
        sliderValue = Math.max(20, Math.min(240, sliderValue));
        speedSlider.setValue(sliderValue);
        userSpeedLimit = sliderValue;
        updateSpeedLimitLabel();

        speedSlider.addOnChangeListener((slider, value, fromUser) -> {
            userSpeedLimit = value;
            updateSpeedLimitLabel();
            // Persist to SharedPreferences
            requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat(KEY_SPEED_LIMIT, value)
                    .apply();
        });

        // Create location callback
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (!isAdded() || locationResult.getLastLocation() == null) return;

                float speedKmh = locationResult.getLastLocation().getSpeed() * 3.6f;
                updateSpeedUI(speedKmh);
            }
        };

        startSpeedTracking();
    }

    private void updateSpeedLimitLabel() {
        if (speedLimitLabel != null) {
            speedLimitLabel.setText(String.format("Speed Limit: %.0f km/h", userSpeedLimit));
        }
    }

    private void updateSpeedUI(float speedKmh) {
        if (!isAdded() || speedValue == null) return;

        speedValue.setText(String.format("%.0f", speedKmh));

        if (speedKmh > userSpeedLimit) {
            // Over the limit
            speedValue.setTextColor(0xFFFF4444);
            speedStatus.setText("⚠ OVER LIMIT!");
            speedStatus.setTextColor(0xFFFF4444);

            if (!isOverLimit) {
                // Just crossed the limit — start alarm
                isOverLimit = true;
                if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                    mediaPlayer.setLooping(true);
                    mediaPlayer.start();
                }
            }

            // Log to DB with cooldown
            long now = System.currentTimeMillis();
            if (now - lastSpeedLogTime > SPEED_LOG_COOLDOWN_MS) {
                lastSpeedLogTime = now;
                dbHelper.addSpeedAlert(speedKmh, userSpeedLimit);
            }

        } else {
            // Under the limit
            speedValue.setTextColor(0xFF00FF99);
            speedStatus.setText("SAFE");
            speedStatus.setTextColor(0xFF00FF99);

            if (isOverLimit) {
                // Just dropped below limit — stop alarm
                isOverLimit = false;
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    mediaPlayer.seekTo(0);
                }
            }
        }
    }

    private void startSpeedTracking() {
        if (getContext() == null) return;

        if (ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 101);
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .build();

        fusedClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper());
        isTrackingActive = true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSpeedTracking();
        } else {
            Toast.makeText(getContext(),
                    "Location permission required for speed tracking",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (fusedClient != null && locationCallback != null && isTrackingActive) {
            fusedClient.removeLocationUpdates(locationCallback);
            isTrackingActive = false;
        }

        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        // Don't close the singleton dbHelper — it's shared
    }
}