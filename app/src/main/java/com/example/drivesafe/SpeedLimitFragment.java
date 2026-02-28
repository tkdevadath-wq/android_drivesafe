package com.example.drivesafe;



import android.Manifest;

import android.content.Context;

import android.content.SharedPreferences;

import android.content.pm.PackageManager;

import android.media.AudioAttributes;

import android.media.AudioManager;

import android.media.MediaPlayer;

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

import androidx.core.content.ContextCompat;

import androidx.fragment.app.Fragment;



import com.google.android.gms.location.FusedLocationProviderClient;

import com.google.android.gms.location.LocationCallback;

import com.google.android.gms.location.LocationRequest;

import com.google.android.gms.location.LocationResult;

import com.google.android.gms.location.LocationServices;

import com.google.android.gms.location.Priority;

import com.google.android.material.slider.Slider;



public class SpeedLimitFragment extends Fragment {



    private TextView speedValue, speedLimitLabel, speedStatus;

    private Slider speedSlider;



    private FusedLocationProviderClient fusedClient;

    private LocationCallback locationCallback;

    private MediaPlayer mediaPlayer;

    private DatabaseHelper dbHelper;



    private float userSpeedLimit = Constants.DEFAULT_SPEED_LIMIT;

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

        setupMediaPlayer();



        fusedClient = LocationServices.getFusedLocationProviderClient(requireActivity());



// Load persisted speed limit

        SharedPreferences prefs = requireContext()

                .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);

        userSpeedLimit = prefs.getFloat(Constants.KEY_SPEED_LIMIT,

                Constants.DEFAULT_SPEED_LIMIT);



// Round to nearest 5 for slider compatibility

        float sliderValue = Math.round(userSpeedLimit / 5.0f) * 5.0f;

        sliderValue = Math.max(20, Math.min(240, sliderValue));

        speedSlider.setValue(sliderValue);

        userSpeedLimit = sliderValue;

        updateSpeedLimitLabel();



        speedSlider.addOnChangeListener((slider, value, fromUser) -> {

            userSpeedLimit = value;

            updateSpeedLimitLabel();

            requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

                    .edit()

                    .putFloat(Constants.KEY_SPEED_LIMIT, value)

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



    /** Pause GPS when this fragment is hidden via show/hide transactions. */

    @Override

    public void onHiddenChanged(boolean hidden) {

        super.onHiddenChanged(hidden);

        if (hidden) {

            pauseSpeedTracking();

        } else {

            resumeSpeedTracking();

        }

    }



    private void updateSpeedLimitLabel() {

        if (speedLimitLabel != null) {

            speedLimitLabel.setText(

                    String.format(getString(R.string.speed_limit_format), userSpeedLimit));

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

                if (mediaPlayer != null && !mediaPlayer.isPlaying()) {

                    mediaPlayer.setLooping(true);

                    mediaPlayer.start();

                }

            }



// Log to DB with cooldown

            long now = System.currentTimeMillis();

            if (now - lastSpeedLogTime > Constants.SPEED_LOG_COOLDOWN_MS) {

                lastSpeedLogTime = now;

                dbHelper.addSpeedAlert(speedKmh, userSpeedLimit);

            }



        } else {

            speedValue.setTextColor(ContextCompat.getColor(ctx, R.color.speed_safe));

            speedStatus.setText(R.string.speed_safe);

            speedStatus.setTextColor(ContextCompat.getColor(ctx, R.color.speed_safe));



            if (isOverLimit) {

                isOverLimit = false;

                if (mediaPlayer != null && mediaPlayer.isPlaying()) {

                    mediaPlayer.pause();

                    mediaPlayer.seekTo(0);

                }

            }

        }

    }



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



    private void pauseSpeedTracking() {

        if (fusedClient != null && locationCallback != null && isTrackingActive) {

            fusedClient.removeLocationUpdates(locationCallback);

            isTrackingActive = false;

        }

// Stop alarm when leaving this tab

        if (mediaPlayer != null && mediaPlayer.isPlaying()) {

            mediaPlayer.pause();

            mediaPlayer.seekTo(0);

        }

        isOverLimit = false;

    }



    private void resumeSpeedTracking() {

        if (!isTrackingActive && fusedClient != null && locationCallback != null) {

            startSpeedTracking();

        }

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

            Context ctx = getContext();

            if (ctx != null) {

                Toast.makeText(ctx, R.string.location_permission_required,

                        Toast.LENGTH_SHORT).show();

            }

        }

    }



    @Override

    public void onDestroyView() {

        super.onDestroyView();



        pauseSpeedTracking();

        releaseMediaPlayer();

// Don't close the singleton dbHelper — it's shared

    }

}