package com.example.callrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 100;

    private SharedPreferences prefs;
    private Switch autoSwitch;
    private TextView statusView;
    private TextView emptyView;
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private final List<Rec> recs = new ArrayList<>();
    private boolean ignoreSwitch = false;

    private MediaPlayer player;
    private Uri playingUri;

    static class Rec {
        Uri uri;
        String label;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("Call Recorder");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        autoSwitch = new Switch(this);
        autoSwitch.setText("Auto-record phone calls");
        autoSwitch.setTextSize(16);
        autoSwitch.setPadding(0, dp(12), 0, dp(4));
        root.addView(autoSwitch);

        statusView = new TextView(this);
        statusView.setPadding(0, 0, 0, dp(8));
        root.addView(statusView);

        CheckBox micOnly = new CheckBox(this);
        micOnly.setText("Microphone only (turn on speakerphone during calls)");
        micOnly.setChecked(prefs.getBoolean("mic_only", false));
        micOnly.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean("mic_only", checked).apply());
        root.addView(micOnly);

        Button battery = new Button(this);
        battery.setText("Keep running in background (battery settings)");
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        root.addView(battery);

        TextView notice = new TextView(this);
        notice.setTextSize(12);
        notice.setPadding(0, dp(8), 0, dp(8));
        notice.setText("Only record calls where you have the other person's consent — "
                + "laws differ by country.\n"
                + "File tags: CALL = both sides, VOICE/MIC = microphone (Android 10+ often blocks "
                + "the other person's audio; speakerphone helps).");
        root.addView(notice);

        TextView header = new TextView(this);
        header.setText("Recordings");
        header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, dp(8), 0, dp(4));
        root.addView(header);

        emptyView = new TextView(this);
        emptyView.setText("No recordings yet.");
        root.addView(emptyView);

        listView = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, pos, id) -> showActions(recs.get(pos)));
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        autoSwitch.setOnCheckedChangeListener((v, on) -> {
            if (ignoreSwitch) return;
            if (on) {
                if (hasCorePerms()) enableService();
                else requestPerms();
            } else {
                disableService();
            }
            updateStatus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean auto = prefs.getBoolean("auto", false) && hasCorePerms();
        setSwitch(auto);
        if (auto) startForegroundService(serviceIntent());
        updateStatus();
        loadRecordings();
    }

    @Override
    protected void onPause() {
        stopPlayback();
        super.onPause();
    }

    // ---------- permissions & service ----------

    private boolean hasCorePerms() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.RECORD_AUDIO);
        p.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(p.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;
        if (hasCorePerms()) {
            enableService();
        } else {
            setSwitch(false);
            prefs.edit().putBoolean("auto", false).apply();
            toast("Microphone and phone permissions are required.");
        }
        updateStatus();
    }

    private Intent serviceIntent() {
        return new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_START);
    }

    private void enableService() {
        prefs.edit().putBoolean("auto", true).apply();
        startForegroundService(serviceIntent());
    }

    private void disableService() {
        prefs.edit().putBoolean("auto", false).apply();
        stopService(new Intent(this, RecorderService.class));
    }

    private void setSwitch(boolean on) {
        ignoreSwitch = true;
        autoSwitch.setChecked(on);
        ignoreSwitch = false;
    }

    private void updateStatus() {
        statusView.setText(autoSwitch.isChecked()
                ? "Active — recording starts automatically when a call connects."
                : "Off — turn the switch on to start.");
    }

    // ---------- recordings list ----------

    private void loadRecordings() {
        recs.clear();
        adapter.clear();

        String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION
        };
        try (Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?", new String[]{"Call_%"},
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    long added = c.getLong(2);
                    long dur = c.getLong(3);

                    String src = "";
                    int us = name.lastIndexOf('_');
                    int dot = name.lastIndexOf('.');
                    if (us >= 0 && dot > us) src = name.substring(us + 1, dot);

                    StringBuilder sb = new StringBuilder();
                    sb.append(DateFormat.format("MMM d, yyyy  HH:mm", new Date(added * 1000)));
                    if (dur > 0) sb.append("  •  ").append(fmt(dur));
                    if (!src.isEmpty()) sb.append("  •  ").append(src);

                    Rec r = new Rec();
                    r.uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    r.label = sb.toString();
                    recs.add(r);
                    adapter.add(r.label);
                }
            }
        } catch (Exception e) {
            toast("Could not load recordings: " + e.getMessage());
        }
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(recs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showActions(final Rec r) {
        boolean playing = player != null && r.uri.equals(playingUri);
        new AlertDialog.Builder(this)
                .setTitle(r.label)
                .setItems(new String[]{playing ? "Stop" : "Play", "Share", "Delete"}, (d, which) -> {
                    if (which == 0) togglePlay(r);
                    else if (which == 1) share(r);
                    else confirmDelete(r);
                })
                .show();
    }

    private void togglePlay(Rec r) {
        if (player != null && r.uri.equals(playingUri)) {
            stopPlayback();
            return;
        }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(this, r.uri);
            player.setOnCompletionListener(mp -> stopPlayback());
            player.prepare();
            player.start();
            playingUri = r.uri;
            toast("Playing…");
        } catch (Exception e) {
            stopPlayback();
            toast("Can't play: " + e.getMessage());
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
        }
        player = null;
        playingUri = null;
    }

    private void share(Rec r) {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("audio/mp4");
        s.putExtra(Intent.EXTRA_STREAM, r.uri);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, "Share recording"));
    }

    private void confirmDelete(final Rec r) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this recording?")
                .setPositiveButton("Delete", (d, w) -> {
                    try {
                        stopPlayback();
                        getContentResolver().delete(r.uri, null, null);
                        loadRecordings();
                    } catch (Exception e) {
                        toast("Can't delete here. Use the Files app (Music/CallRecordings).");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- helpers ----------

    private String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}
