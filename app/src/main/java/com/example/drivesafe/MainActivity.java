package com.example.drivesafe;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private Fragment eyeFragment, speedFragment, historyFragment;
    private Fragment activeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Let the app draw edge-to-edge, then apply system bar insets as
        // padding on the root LinearLayout.  This pushes the header below
        // the status bar and the bottom nav above the gesture/nav bar.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        View rootView = findViewById(R.id.rootLayout);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Apply system bar insets as padding on the root LinearLayout
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        Log.d("DriveSafe", "MainActivity onCreate — layout v3 loaded");

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);

        checkPermissionsAndGps();

        if (savedInstanceState == null) {
            eyeFragment = new EyeTrackingFragment();
            speedFragment = new SpeedLimitFragment();
            historyFragment = new DriveHistoryFragment();

            getSupportFragmentManager().beginTransaction()
                    .add(R.id.nav_host_fragment, historyFragment, "history").hide(historyFragment)
                    .add(R.id.nav_host_fragment, speedFragment, "speed").hide(speedFragment)
                    .add(R.id.nav_host_fragment, eyeFragment, "eye")
                    .commit();

            activeFragment = eyeFragment;
        }

        bottomNav.setOnItemSelectedListener(item -> {
            Fragment target = null;
            int id = item.getItemId();

            if (id == R.id.nav_eye) {
                target = eyeFragment;
            } else if (id == R.id.nav_speed) {
                target = speedFragment;
            } else if (id == R.id.nav_history) {
                target = historyFragment;
            }

            if (target != null && target != activeFragment) {
                getSupportFragmentManager().beginTransaction()
                        .hide(activeFragment)
                        .show(target)
                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                        .commit();
                activeFragment = target;
            }
            return true;
        });
    }

    private void checkPermissionsAndGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.CAMERA
                    }, 101);
        } else {
            checkGpsEnabled();
        }
    }

    private void checkGpsEnabled() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean isGpsEnabled = false;

        try {
            if (lm != null) {
                isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception ignored) {}

        if (!isGpsEnabled) {
            Toast.makeText(this,
                    "Please turn on GPS/Location",
                    Toast.LENGTH_LONG).show();

            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
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
            checkGpsEnabled();
        }
    }
}