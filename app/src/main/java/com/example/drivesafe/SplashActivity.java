package com.example.drivesafe;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable navigateRunnable = () -> {
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splashLogo);
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.logo_fade_zoom);
        logo.startAnimation(anim);

        handler.postDelayed(navigateRunnable, 3500);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Remove pending callbacks to prevent leaking the Activity
        handler.removeCallbacks(navigateRunnable);
    }
}