package com.code2hack.prototype.hotspot.dealer;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class DealerPrototypeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DealerTransportService service;
    private boolean bound;
    private TextView state;
    private EditText host;
    private CheckBox wakeLock;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((DealerTransportService.LocalBinder) binder).service();
            bound = true;
            if (host.getText().length() == 0) {
                host.setText(service.effectiveHost());
            }
            wakeLock.setChecked(service.isWakeLockHeld());
            render();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermission();
        setContentView(buildUi());

        Intent transport = new Intent(this, DealerTransportService.class);
        startForegroundService(transport);
        bindService(transport, connection, Context.BIND_AUTO_CREATE);
        handler.post(this::render);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (bound) {
            unbindService(connection);
        }
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("PROTOTYPE — Fold6 hotspot client");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        host = new EditText(this);
        host.setHint("Glasses IP (default 10.84.179.154)");
        host.setSingleLine(true);
        content.addView(host);
        content.addView(button("Apply glasses IP / reconnect", () -> {
            if (service != null) {
                service.setHostOverride(host.getText().toString());
            }
        }));
        content.addView(button("Run 5,000 Fold6 probes", () -> {
            if (service != null) {
                service.runBurst(5_000);
            }
        }));
        content.addView(button("Request 5,000 glasses probes", () -> {
            if (service != null) {
                try {
                    service.requestPeerBurst(5_000);
                } catch (IllegalStateException error) {
                    state.setText("Peer-burst request failed: " + error.getMessage());
                }
            }
        }));
        content.addView(button("Drop TCP connection", () -> {
            if (service != null) {
                service.dropConnection();
            }
        }));
        content.addView(button("Reset counters", () -> {
            if (service != null) {
                service.resetCounters();
            }
        }));

        wakeLock = new CheckBox(this);
        wakeLock.setText("Hold partial wake lock");
        wakeLock.setChecked(false);
        wakeLock.setOnCheckedChangeListener((button, checked) -> {
            if (service != null) {
                service.setWakeLockEnabled(checked);
            }
        });
        content.addView(wakeLock);

        state = new TextView(this);
        state.setTextSize(14);
        state.setTypeface(Typeface.MONOSPACE);
        state.setTextIsSelectable(true);
        content.addView(state);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(ignored -> action.run());
        return button;
    }

    private void render() {
        if (state != null) {
            PowerManager power = getSystemService(PowerManager.class);
            String value = service == null
                    ? "binding foreground service…"
                    : service.snapshot()
                    + "screen_interactive: " + power.isInteractive() + "\n";
            state.setText(value);
        }
        handler.postDelayed(this::render, 1_000);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    1
            );
        }
    }
}
