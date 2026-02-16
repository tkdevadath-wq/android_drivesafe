package com.example.drivesafe;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

public class MapFragment extends Fragment {

    private MapView map;
    private MyLocationNewOverlay mLocationOverlay;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        Configuration.getInstance().setUserAgentValue("com.example.drivesafe");

        View view = inflater.inflate(R.layout.map_fragment, container, false);

        map = view.findViewById(R.id.mapview);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);

        float[] nightMatrix = {
                -1,0,0,0,255,
                0,-1,0,0,255,
                0,0,-1,0,255,
                0,0,0,1,0
        };

        map.getOverlayManager()
                .getTilesOverlay()
                .setColorFilter(new ColorMatrixColorFilter(nightMatrix));

        checkPermissionAndInit();

        map.getController().setZoom(18.0);

        return view;
    }

    private void checkPermissionAndInit() {

        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    102);

        } else {
            initLocationOverlay();
        }
    }

    private void initLocationOverlay() {

        try {
            mLocationOverlay = new MyLocationNewOverlay(
                    new GpsMyLocationProvider(requireContext()),
                    map);

            mLocationOverlay.enableMyLocation();
            mLocationOverlay.enableFollowLocation();
            map.getOverlays().add(mLocationOverlay);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        if (requestCode == 102
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            initLocationOverlay();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (map != null)
            map.onResume();

        if (mLocationOverlay != null &&
                ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {

            mLocationOverlay.enableMyLocation();
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if (map != null)
            map.onPause();

        if (mLocationOverlay != null)
            mLocationOverlay.disableMyLocation();
    }
}