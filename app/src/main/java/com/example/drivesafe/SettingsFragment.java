package com.example.drivesafe;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class SettingsFragment extends Fragment {

    // ─── Profile & Safety ────────────────────────────────────────────────
    private ShapeableImageView ivProfilePhoto;
    private TextInputEditText etUserName;
    private EditText etEmergencyNumber;

    // ─── Detection Sensitivity ───────────────────────────────────────────
    private MaterialButtonToggleGroup toggleSensitivity;
    private TextView tvSensitivityDesc;
    private String selectedSensitivity = Constants.DEFAULT_SENSITIVITY;

    // ─── Preferences ─────────────────────────────────────────────────────
    private MaterialSwitch switchTheme;
    private Slider sliderVolume;
    private TextView tvVolumePercent;
    private MaterialButton btnSelectSound;
    private TextView tvSelectedSound;
    private ImageButton btnPreviewSound;
    private Button btnSaveSettings;

    // ─── Smart Detection ─────────────────────────────────────────────────
    private MaterialSwitch switchSmartDetection;
    private LinearLayout smartDetectionContent;
    private MaterialSwitch switchMinSpeed;
    private LinearLayout minSpeedSliderContainer;
    private Slider sliderMinSpeed;
    private TextView tvMinSpeedLabel;
    private MaterialSwitch switchLargestFace;

    // ─── Core ────────────────────────────────────────────────────────────
    private SharedPreferences prefs;
    private String selectedSound = Constants.DEFAULT_ALARM_SOUND;
    private MediaPlayer previewPlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());

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

        // ─── Bind all views ──────────────────────────────────────────────
        ivProfilePhoto    = view.findViewById(R.id.ivProfilePhoto);
        etUserName        = view.findViewById(R.id.etUserName);
        etEmergencyNumber = view.findViewById(R.id.etEmergencyNumber);

        toggleSensitivity = view.findViewById(R.id.toggleSensitivity);
        tvSensitivityDesc = view.findViewById(R.id.tvSensitivityDesc);

        switchTheme       = view.findViewById(R.id.switchTheme);
        sliderVolume      = view.findViewById(R.id.sliderVolume);
        tvVolumePercent   = view.findViewById(R.id.tvVolumePercent);
        btnSelectSound    = view.findViewById(R.id.btnSelectSound);
        tvSelectedSound   = view.findViewById(R.id.tvSelectedSound);
        btnPreviewSound   = view.findViewById(R.id.btnPreviewSound);
        btnSaveSettings   = view.findViewById(R.id.btnSaveSettings);

        switchSmartDetection  = view.findViewById(R.id.switchSmartDetection);
        smartDetectionContent = view.findViewById(R.id.smartDetectionContent);
        switchMinSpeed        = view.findViewById(R.id.switchMinSpeed);
        minSpeedSliderContainer = view.findViewById(R.id.minSpeedSliderContainer);
        sliderMinSpeed        = view.findViewById(R.id.sliderMinSpeed);
        tvMinSpeedLabel       = view.findViewById(R.id.tvMinSpeedLabel);
        switchLargestFace     = view.findViewById(R.id.switchLargestFace);

        // ─── Load existing settings ──────────────────────────────────────
        etUserName.setText(prefs.getString(Constants.KEY_PROFILE_NAME, ""));
        etEmergencyNumber.setText(prefs.getString(Constants.KEY_EMERGENCY_NUMBER, ""));
        switchTheme.setChecked(prefs.getBoolean(Constants.KEY_DARK_MODE, true));

        // Volume
        float volume = prefs.getFloat(Constants.KEY_ALARM_VOLUME, Constants.DEFAULT_ALARM_VOLUME);
        sliderVolume.setValue(volume);
        tvVolumePercent.setText(String.format(getString(R.string.volume_format), (int) volume));

        // Alarm sound
        selectedSound = prefs.getString(
                Constants.KEY_ALARM_SOUND, Constants.DEFAULT_ALARM_SOUND);
        tvSelectedSound.setText("Selected: " + selectedSound);

        // Sensitivity
        selectedSensitivity = prefs.getString(
                Constants.KEY_SENSITIVITY, Constants.DEFAULT_SENSITIVITY);
        applySensitivityUI(selectedSensitivity);

        // Smart Detection
        boolean smartEnabled = prefs.getBoolean(Constants.KEY_SMART_DETECTION_ENABLED, false);
        switchSmartDetection.setChecked(smartEnabled);
        smartDetectionContent.setVisibility(smartEnabled ? View.VISIBLE : View.GONE);

        boolean minSpeedEnabled = prefs.getBoolean(Constants.KEY_MIN_SPEED_ENABLED, false);
        switchMinSpeed.setChecked(minSpeedEnabled);
        minSpeedSliderContainer.setVisibility(minSpeedEnabled ? View.VISIBLE : View.GONE);

        float minSpeed = prefs.getFloat(Constants.KEY_MIN_SPEED_KMH, Constants.DEFAULT_MIN_SPEED_KMH);
        // Round to nearest step for slider
        float minSpeedClamped = Math.max(5, Math.min(50, Math.round(minSpeed / 5.0f) * 5.0f));
        sliderMinSpeed.setValue(minSpeedClamped);
        tvMinSpeedLabel.setText(String.format(getString(R.string.min_speed_format), minSpeedClamped));

        switchLargestFace.setChecked(prefs.getBoolean(Constants.KEY_LARGEST_FACE_ONLY, false));

        // Load profile image
        String path = prefs.getString(Constants.KEY_PROFILE_IMAGE_PATH, "");
        if (!path.isEmpty()) {
            File file = new File(path);
            if (file.exists()) {
                ivProfilePhoto.setImageURI(Uri.fromFile(file));
            }
        }

        // ─── Listeners ──────────────────────────────────────────────────

        ivProfilePhoto.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));
        btnSelectSound.setOnClickListener(v -> showSoundDialog());
        btnPreviewSound.setOnClickListener(v -> playPreviewSound(selectedSound));

        // Volume slider: live update label + device alarm stream volume
        sliderVolume.addOnChangeListener((slider, value, fromUser) -> {
            tvVolumePercent.setText(String.format(getString(R.string.volume_format), (int) value));
            if (fromUser) {
                applyDeviceVolume(value);
            }
        });

        // Sensitivity toggle
        toggleSensitivity.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnSensLow) {
                selectedSensitivity = Constants.SENSITIVITY_LOW;
                tvSensitivityDesc.setText(R.string.sensitivity_low_desc);
            } else if (checkedId == R.id.btnSensHigh) {
                selectedSensitivity = Constants.SENSITIVITY_HIGH;
                tvSensitivityDesc.setText(R.string.sensitivity_high_desc);
            } else {
                selectedSensitivity = Constants.SENSITIVITY_MEDIUM;
                tvSensitivityDesc.setText(R.string.sensitivity_medium_desc);
            }
        });

        // Smart Detection master toggle with animation
        switchSmartDetection.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ViewGroup parent = (ViewGroup) smartDetectionContent.getParent().getParent();
            TransitionManager.beginDelayedTransition(parent);
            smartDetectionContent.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Min speed sub-toggle
        switchMinSpeed.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ViewGroup parent = (ViewGroup) minSpeedSliderContainer.getParent();
            TransitionManager.beginDelayedTransition(parent);
            minSpeedSliderContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Min speed slider label
        sliderMinSpeed.addOnChangeListener((slider, value, fromUser) ->
                tvMinSpeedLabel.setText(String.format(getString(R.string.min_speed_format), value))
        );

        // Save button
        btnSaveSettings.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.startAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.btn_click));
            }
            return false;
        });
        btnSaveSettings.setOnClickListener(v -> saveSettings());

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        releasePreviewPlayer();
    }

    // ─── Sensitivity UI Helper ───────────────────────────────────────────

    private void applySensitivityUI(String sensitivity) {
        int checkedId;
        int descRes;
        switch (sensitivity) {
            case Constants.SENSITIVITY_LOW:
                checkedId = R.id.btnSensLow;
                descRes = R.string.sensitivity_low_desc;
                break;
            case Constants.SENSITIVITY_HIGH:
                checkedId = R.id.btnSensHigh;
                descRes = R.string.sensitivity_high_desc;
                break;
            default:
                checkedId = R.id.btnSensMed;
                descRes = R.string.sensitivity_medium_desc;
                break;
        }
        toggleSensitivity.check(checkedId);
        tvSensitivityDesc.setText(descRes);
    }

    // ─── Volume / AudioManager ───────────────────────────────────────────

    private void applyDeviceVolume(float percent) {
        Context ctx = getContext();
        if (ctx == null) return;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int targetVol = Math.round((percent / 100f) * maxVol);
        am.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0);
    }

    // ─── Sound Preview ───────────────────────────────────────────────────

    private void playPreviewSound(String soundName) {
        releasePreviewPlayer();
        Context ctx = getContext();
        if (ctx == null) return;

        int resId = getSoundResId(soundName);
        previewPlayer = MediaPlayer.create(ctx, resId);
        if (previewPlayer == null) return;

        // Apply current volume slider value
        float vol = sliderVolume.getValue() / 100f;
        previewPlayer.setVolume(vol, vol);
        previewPlayer.setOnCompletionListener(mp -> releasePreviewPlayer());
        previewPlayer.start();

        // Auto-stop after 3 seconds if still playing
        handler.postDelayed(this::releasePreviewPlayer, 3000);
    }

    private void releasePreviewPlayer() {
        handler.removeCallbacksAndMessages(null);
        if (previewPlayer != null) {
            try {
                if (previewPlayer.isPlaying()) previewPlayer.stop();
                previewPlayer.release();
            } catch (Exception ignored) { }
            previewPlayer = null;
        }
    }

    private int getSoundResId(String soundName) {
        switch (soundName) {
            case "Sound 2": return R.raw.alarm2;
            case "Sound 3": return R.raw.alarm3;
            case "Sound 4": return R.raw.alarm4;
            default:        return R.raw.alarm1;
        }
    }

    // ─── Save Settings ───────────────────────────────────────────────────

    private void saveSettings() {
        boolean isDarkMode = switchTheme.isChecked();

        SharedPreferences.Editor editor = prefs.edit();

        // Profile
        if (etUserName.getText() != null) {
            editor.putString(Constants.KEY_PROFILE_NAME,
                    etUserName.getText().toString().trim());
        }
        editor.putString(Constants.KEY_EMERGENCY_NUMBER,
                etEmergencyNumber.getText().toString().trim());

        // Preferences
        editor.putFloat(Constants.KEY_ALARM_VOLUME, sliderVolume.getValue());
        editor.putString(Constants.KEY_ALARM_SOUND, selectedSound);
        editor.putBoolean(Constants.KEY_DARK_MODE, isDarkMode);

        // Sensitivity
        editor.putString(Constants.KEY_SENSITIVITY, selectedSensitivity);

        // Smart Detection
        editor.putBoolean(Constants.KEY_SMART_DETECTION_ENABLED,
                switchSmartDetection.isChecked());
        editor.putBoolean(Constants.KEY_MIN_SPEED_ENABLED,
                switchMinSpeed.isChecked());
        editor.putFloat(Constants.KEY_MIN_SPEED_KMH, sliderMinSpeed.getValue());
        editor.putBoolean(Constants.KEY_LARGEST_FACE_ONLY,
                switchLargestFace.isChecked());

        editor.apply();

        // Apply device volume
        applyDeviceVolume(sliderVolume.getValue());

        AppCompatDelegate.setDefaultNightMode(isDarkMode
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);

        Toast.makeText(getContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();

        getParentFragmentManager().popBackStack();
    }

    // ─── Sound Picker Dialog ─────────────────────────────────────────────

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
                    // Play a preview of the selected sound
                    playPreviewSound(selectedSound);
                })
                .setNegativeButton(R.string.cancel, (dialog, which) ->
                        releasePreviewPlayer())
                .setOnDismissListener(dialog -> {
                    // Stop preview after a short delay if dialog dismissed
                    handler.postDelayed(this::releasePreviewPlayer, 3000);
                })
                .show();
    }
}