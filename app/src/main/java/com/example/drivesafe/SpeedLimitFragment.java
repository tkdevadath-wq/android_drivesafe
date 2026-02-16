package com.example.drivesafe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.slider.Slider;

public class SpeedLimitFragment extends Fragment implements LocationListener {

    private TextView speedValue;
    private LocationManager locationManager;
    private float userSpeedLimit = 80.0f;

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
        Slider speedSlider = view.findViewById(R.id.speedSlider);

        // Safe Activity access
        if (getActivity() != null) {
            locationManager = (LocationManager)
                    getActivity().getSystemService(Context.LOCATION_SERVICE);
        }

        if (speedSlider != null) {
            speedSlider.addOnChangeListener(
                    (slider, value, fromUser) -> userSpeedLimit = value);
        }

        startSpeedTracking();
    }

    private void startSpeedTracking() {

        if (getContext() == null) return;

        // 1️⃣ Permission check
        if (ActivityCompat.checkSelfPermission(
                getContext(),
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    101);
            return;
        }

        // 2️⃣ GPS check
        if (locationManager != null &&
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {

            try {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000,
                        1,
                        this
                );
            } catch (Exception e) {
                e.printStackTrace();
            }

        } else {
            Toast.makeText(
                    getContext(),
                    "Please Turn On GPS",
                    Toast.LENGTH_SHORT
            ).show();

            if (speedValue != null) {
                speedValue.setText("0.0");
            }
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {

        if (isAdded() && speedValue != null) {

            float speedKmh = location.getSpeed() * 3.6f;

            speedValue.setText(String.format("%.1f", speedKmh));

            speedValue.setTextColor(
                    speedKmh > userSpeedLimit
                            ? 0xFFFF0000
                            : 0xFFFFFFFF
            );
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
    }
}