package com.example.drivesafe;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class SettingsFragment extends Fragment {

    private ShapeableImageView ivProfilePhoto;
    private TextInputEditText etUserName;
    private EditText etEmergencyNumber;
    private MaterialSwitch switchTheme;
    private Slider sliderVolume;
    private MaterialButton btnSelectSound;
    private TextView tvSelectedSound;
    private Button btnSaveSettings;

    private SharedPreferences prefs;
    private String selectedSound = Constants.DEFAULT_ALARM_SOUND;

    private final ActivityResultLauncher<String> imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    try {
                        InputStream is = requireContext().getContentResolver().openInputStream(uri);
                        File file = new File(requireContext().getFilesDir(), "profile_pic.jpg");
                        FileOutputStream fos = new FileOutputStream(file);
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                        fos.close();
                        is.close();

                        prefs.edit().putString(Constants.KEY_PROFILE_IMAGE_PATH,
                                file.getAbsolutePath()).apply();
                        ivProfilePhoto.setImageURI(Uri.fromFile(file));
                    } catch (Exception e) {
                        Log.e(Constants.TAG, "Failed to copy profile image", e);
                        Toast.makeText(getContext(), R.string.photo_save_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        prefs = requireActivity().getSharedPreferences(
                Constants.PREFS_NAME, Context.MODE_PRIVATE);

        ivProfilePhoto    = view.findViewById(R.id.ivProfilePhoto);
        etUserName        = view.findViewById(R.id.etUserName);
        etEmergencyNumber = view.findViewById(R.id.etEmergencyNumber);
        switchTheme       = view.findViewById(R.id.switchTheme);
        sliderVolume      = view.findViewById(R.id.sliderVolume);
        btnSelectSound    = view.findViewById(R.id.btnSelectSound);
        tvSelectedSound   = view.findViewById(R.id.tvSelectedSound);
        btnSaveSettings   = view.findViewById(R.id.btnSaveSettings);

        // Load existing settings
        etUserName.setText(prefs.getString(Constants.KEY_PROFILE_NAME, ""));
        etEmergencyNumber.setText(prefs.getString(Constants.KEY_EMERGENCY_NUMBER, ""));
        sliderVolume.setValue(prefs.getFloat(
                Constants.KEY_ALARM_VOLUME, Constants.DEFAULT_ALARM_VOLUME));
        switchTheme.setChecked(prefs.getBoolean(Constants.KEY_DARK_MODE, true));

        selectedSound = prefs.getString(
                Constants.KEY_ALARM_SOUND, Constants.DEFAULT_ALARM_SOUND);
        tvSelectedSound.setText("Selected: " + selectedSound);

        // Load profile image
        String path = prefs.getString(Constants.KEY_PROFILE_IMAGE_PATH, "");
        if (!path.isEmpty()) {
            File file = new File(path);
            if (file.exists()) {
                ivProfilePhoto.setImageURI(Uri.fromFile(file));
            }
        }

        ivProfilePhoto.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));
        btnSelectSound.setOnClickListener(v -> showSoundDialog());

        btnSaveSettings.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.startAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.btn_click));
            }
            return false;
        });

        btnSaveSettings.setOnClickListener(v -> saveSettings());

        return view;
    }

    private void saveSettings() {
        boolean isDarkMode = switchTheme.isChecked();

        SharedPreferences.Editor editor = prefs.edit();
        if (etUserName.getText() != null) {
            editor.putString(Constants.KEY_PROFILE_NAME,
                    etUserName.getText().toString().trim());
        }
        editor.putString(Constants.KEY_EMERGENCY_NUMBER,
                etEmergencyNumber.getText().toString().trim());
        editor.putFloat(Constants.KEY_ALARM_VOLUME, sliderVolume.getValue());
        editor.putString(Constants.KEY_ALARM_SOUND, selectedSound);
        editor.putBoolean(Constants.KEY_DARK_MODE, isDarkMode);
        editor.apply();

        AppCompatDelegate.setDefaultNightMode(isDarkMode
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);

        Toast.makeText(getContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();

        getParentFragmentManager().popBackStack();
    }

    private void showSoundDialog() {
        String[] sounds = {"Sound 1", "Sound 2", "Sound 3", "Sound 4"};
        int checkedItem = 0;
        for (int i = 0; i < sounds.length; i++) {
            if (sounds[i].equals(selectedSound)) checkedItem = i;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.select_alarm_sound)
                .setSingleChoiceItems(sounds, checkedItem, (dialog, which) -> {
                    selectedSound = sounds[which];
                    tvSelectedSound.setText("Selected: " + selectedSound);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}