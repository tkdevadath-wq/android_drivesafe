package com.example.drivesafe;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

public class SpeedLimitFragment extends Fragment {

    private TextView speedValue, speedLimitLabel, speedStatus;
    private Slider speedSlider;
    private MaterialSwitch switchSpeedAlert;

    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
    private MediaPlayer mediaPlayer;
    private MediaPlayer voicePlayer;
    private DatabaseHelper dbHelper;

    private float userSpeedLimit = Constants.DEFAULT_SPEED_LIMIT;
    private boolean isOverLimit = false;
    private long lastSpeedLogTime = 0;
    private boolean isTrackingActive = false;
    private boolean isAlertEnabled = true;

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
        switchSpeedAlert = view.findViewById(R.id.switchSpeedAlert);

        dbHelper = DatabaseHelper.getInstance(requireContext());
        setupMediaPlayer();
        createNotificationChannel();

        fusedClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        SharedPreferences prefs = requireContext()
                .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);

        userSpeedLimit = prefs.getFloat(Constants.KEY_SPEED_LIMIT, Constants.DEFAULT_SPEED_LIMIT);
        isAlertEnabled = prefs.getBoolean(Constants.KEY_SPEED_ALERT_ENABLED, true);

        if (switchSpeedAlert != null) {
            switchSpeedAlert.setChecked(isAlertEnabled);
            switchSpeedAlert.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isAlertEnabled = isChecked;
                prefs.edit().putBoolean(Constants.KEY_SPEED_ALERT_ENABLED, isChecked).apply();

                if (!isChecked) {
                    resetToSafeUI();
                    cancelStatusNotification();
                } else {
                    updateStatusNotification("GuardianEye: Active", "Monitoring speed limit...");
                }

                Toast.makeText(getContext(), isChecked ? "Monitoring Enabled" : "Monitoring Disabled", Toast.LENGTH_SHORT).show();
            });
        }

        float sliderValue = Math.round(userSpeedLimit / 5.0f) * 5.0f;
        sliderValue = Math.max(20, Math.min(240, sliderValue));
        speedSlider.setValue(sliderValue);
        userSpeedLimit = sliderValue;
        updateSpeedLimitLabel();

        speedSlider.addOnChangeListener((slider, value, fromUser) -> {
            userSpeedLimit = value;
            updateSpeedLimitLabel();
            prefs.edit().putFloat(Constants.KEY_SPEED_LIMIT, value).apply();
        });

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (!isAdded() || locationResult.getLastLocation() == null) return;

                float speedKmh = locationResult.getLastLocation().getSpeed() * 3.6f;

                if (isAlertEnabled) {
                    updateSpeedUI(speedKmh);
                    String msg = (speedKmh > userSpeedLimit) ? "SLOW DOWN!" : "Speed is safe";
                    updateStatusNotification("GuardianEye: Active",
                            String.format("%.1f km/h | %s", speedKmh, msg));
                } else {
                    if (speedValue != null) {
                        speedValue.setText(String.format(getString(R.string.speed_format), speedKmh));
                    }
                    resetToSafeUI();
                }
            }
        };

        startSpeedTracking();
    }

    // ─── Notification Helper Methods ─────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    Constants.CHANNEL_ID,
                    Constants.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = requireContext().getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void updateStatusNotification(String title, String message) {
        if (!isAdded()) return;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(requireContext(), Constants.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title)
                .setContentText(message)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        NotificationManager nm = (NotificationManager) requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(Constants.NOTIFICATION_ID, builder.build());
    }

    private void cancelStatusNotification() {
        if (!isAdded()) return;
        NotificationManager nm = (NotificationManager) requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(Constants.NOTIFICATION_ID);
    }

    // ─── Speed Alert Logic ──────────────────────────────────────────────────

    private void updateSpeedLimitLabel() {
        if (speedLimitLabel != null) {
            speedLimitLabel.setText(String.format(getString(R.string.speed_limit_format), userSpeedLimit));
        }
    }

    private void resetToSafeUI() {
        if (!isAdded() || speedValue == null || speedStatus == null) return;
        Context ctx = getContext();
        if (ctx == null) return;

        speedValue.setTextColor(ContextCompat.getColor(ctx, R.color.speed_safe));
        speedStatus.setText(isAlertEnabled ? getString(R.string.speed_safe) : "DISABLED");
        speedStatus.setTextColor(ContextCompat.getColor(ctx, R.color.speed_safe));

        if (isOverLimit) {
            isOverLimit = false;
            stopAllAudio();
        }
    }

    private void updateSpeedUI(float speedKmh) {
        if (!isAdded() || speedValue == null) return;
        Context ctx = getContext();
        if (ctx == null) return;

        speedValue.setText(String.format(getString(R.string.speed_format), speedKmh));

        if (speedKmh > userSpeedLimit) {
            speedValue.setTextColor(ContextCompat.getColor(ctx, R.color.speed_over_limit));
            speedStatus.setText(R.string.speed_over_limit);
            speedStatus.setTextColor(ContextCompat.getColor(ctx, R.color.speed_over_limit));

            if (!isOverLimit) {
                isOverLimit = true;
                playVoiceThenAlarm(R.raw.voice_speed);
            }

            long now = System.currentTimeMillis();
            if (now - lastSpeedLogTime > Constants.SPEED_LOG_COOLDOWN_MS) {
                lastSpeedLogTime = now;
                dbHelper.addSpeedAlert(speedKmh, userSpeedLimit);
            }
        } else {
            resetToSafeUI();
        }
    }

    // ─── Audio Management ────────────────────────────────────────────────

    private void setupMediaPlayer() {
        releaseMediaPlayer();
        Context ctx = getContext();
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String soundChoice = prefs.getString(Constants.KEY_ALARM_SOUND, Constants.DEFAULT_ALARM_SOUND);
        int soundResId = R.raw.alarm1;
        if (soundChoice != null) {
            switch (soundChoice) {
                case "Sound 2": soundResId = R.raw.alarm2; break;
                case "Sound 3": soundResId = R.raw.alarm3; break;
                case "Sound 4": soundResId = R.raw.alarm4; break;
            }
        }
        mediaPlayer = MediaPlayer.create(ctx, soundResId);
        if (mediaPlayer != null) {
            mediaPlayer.setLooping(true);
            float volume = prefs.getFloat(Constants.KEY_ALARM_VOLUME, Constants.DEFAULT_ALARM_VOLUME) / 100f;
            mediaPlayer.setVolume(volume, volume);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        }
    }

    private void playVoiceThenAlarm(int voiceResId) {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        if (voicePlayer != null && voicePlayer.isPlaying()) return;

        releaseVoicePlayer();
        voicePlayer = MediaPlayer.create(ctx, voiceResId);
        if (voicePlayer != null) {
            voicePlayer.setOnCompletionListener(mp -> {
                if (isOverLimit && mediaPlayer != null && !mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
            });
            voicePlayer.start();
        } else {
            if (mediaPlayer != null && !mediaPlayer.isPlaying()) mediaPlayer.start();
        }
    }

    private void stopAllAudio() {
        if (voicePlayer != null && voicePlayer.isPlaying()) voicePlayer.pause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            mediaPlayer.seekTo(0);
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try { if (mediaPlayer.isPlaying()) mediaPlayer.stop(); mediaPlayer.release(); }
            catch (Exception e) { Log.e("SpeedLimitFragment", "Error releasing MediaPlayer", e); }
            mediaPlayer = null;
        }
    }

    private void releaseVoicePlayer() {
        if (voicePlayer != null) {
            try { if (voicePlayer.isPlaying()) voicePlayer.stop(); voicePlayer.release(); }
            catch (Exception e) { Log.e("SpeedLimitFragment", "Error releasing VoicePlayer", e); }
            voicePlayer = null;
        }
    }

    // ─── Lifecycle & Tracking ──────────────────────────────────────────

    private void startSpeedTracking() {
        if (getContext() == null) return;
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 101);
            return;
        }
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).setMinUpdateIntervalMillis(500).build();
        fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        isTrackingActive = true;
    }

    private void pauseSpeedTracking() {
        if (fusedClient != null && locationCallback != null && isTrackingActive) {
            fusedClient.removeLocationUpdates(locationCallback);
            isTrackingActive = false;
        }
        stopAllAudio();
        isOverLimit = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSpeedTracking();
        } else {
            Context ctx = getContext();
            if (ctx != null) Toast.makeText(ctx, R.string.location_permission_required, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        pauseSpeedTracking();
        releaseMediaPlayer();
        releaseVoicePlayer();
        cancelStatusNotification();
    }
}