package com.example.drivesafe;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.List;

public class DriveHistoryFragment extends Fragment {

    private LinearLayout sessionsSection, sessionsContainer;
    private LinearLayout speedSection, speedContainer;
    private LinearLayout emptyState;
    private Button clearButton;
    private DatabaseHelper dbHelper;
    private LayoutInflater layoutInflater;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        this.layoutInflater = inflater;
        return inflater.inflate(R.layout.fragment_drive_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        sessionsSection  = view.findViewById(R.id.sessionsSection);
        sessionsContainer = view.findViewById(R.id.sessionsContainer);
        speedSection     = view.findViewById(R.id.speedSection);
        speedContainer   = view.findViewById(R.id.speedContainer);
        emptyState       = view.findViewById(R.id.emptyState);
        clearButton      = view.findViewById(R.id.clearButton);

        dbHelper = DatabaseHelper.getInstance(requireContext());

        clearButton.setOnClickListener(v -> {
            dbHelper.clearAll();
            loadData();
        });

        loadData();
    }

    /** Called by show()/hide() fragment transactions — reload when the tab becomes visible. */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && dbHelper != null) {
            loadData();
        }
    }

    /** Also reload when the fragment resumes (e.g. returning to the app). */
    @Override
    public void onResume() {
        super.onResume();
        if (!isHidden() && dbHelper != null) {
            loadData();
        }
    }

    private void loadData() {
        List<DatabaseHelper.Session> sessions = dbHelper.getAllSessions();
        List<DatabaseHelper.SpeedAlert> alerts = dbHelper.getSpeedAlerts();

        Log.d("DriveSafe", "DriveHistoryFragment.loadData() — "
                + sessions.size() + " sessions, " + alerts.size() + " speed alerts");

        boolean hasSessions = !sessions.isEmpty();
        boolean hasAlerts   = !alerts.isEmpty();

        // Empty state
        emptyState.setVisibility((!hasSessions && !hasAlerts) ? View.VISIBLE : View.GONE);
        clearButton.setVisibility(( hasSessions ||  hasAlerts) ? View.VISIBLE : View.GONE);

        // ── Sessions ────────────────────────────────────────────────────────
        sessionsSection.setVisibility(hasSessions ? View.VISIBLE : View.GONE);
        sessionsContainer.removeAllViews();
        for (DatabaseHelper.Session session : sessions) {
            View card = layoutInflater.inflate(
                    R.layout.item_session, sessionsContainer, false);

            ((TextView) card.findViewById(R.id.sessionDateTime))
                    .setText(session.formattedStartTime());
            ((TextView) card.findViewById(R.id.sessionDuration))
                    .setText(session.formattedDuration());
            ((TextView) card.findViewById(R.id.sessionWarningCount))
                    .setText(String.valueOf(session.fatigueWarningCount));
            ((TextView) card.findViewById(R.id.sessionCriticalCount))
                    .setText(String.valueOf(session.fatigueCriticalCount));
            ((TextView) card.findViewById(R.id.sessionBlinkCount))
                    .setText(String.valueOf(session.blinkCount));

            TextView verdict = card.findViewById(R.id.sessionVerdict);
            verdict.setText(session.verdict());
            verdict.setTextColor(session.verdictColor());

            sessionsContainer.addView(card);
        }

        // ── Speed Alerts ─────────────────────────────────────────────────────
        speedSection.setVisibility(hasAlerts ? View.VISIBLE : View.GONE);
        speedContainer.removeAllViews();
        for (DatabaseHelper.SpeedAlert alert : alerts) {
            View row = layoutInflater.inflate(
                    R.layout.item_speed_alert, speedContainer, false);

            float overBy = alert.speedKmh - alert.limitKmh;
            ((TextView) row.findViewById(R.id.alertSpeed))
                    .setText(String.format("%.0f km/h  ·  limit %.0f km/h",
                            alert.speedKmh, alert.limitKmh));
            ((TextView) row.findViewById(R.id.alertTime))
                    .setText(alert.formattedTime());
            ((TextView) row.findViewById(R.id.alertOverBy))
                    .setText(String.format("+%.0f", overBy));

            speedContainer.addView(row);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Don't close the singleton dbHelper — it's shared
        dbHelper = null;
    }
}
