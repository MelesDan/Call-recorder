package com.example.callrecorder;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that watches the phone-call state and records
 * while a call is active. A persistent notification is always shown.
 */
public class RecorderService extends Service {

    public static final String ACTION_START = "com.example.callrecorder.START";
    private static final String CHANNEL_ID = "call_recorder";
    private static final int NOTIF_ID = 1;

    private TelephonyManager tm;
    private PhoneStateListener legacyListener;
    private Object modernCallback;
    private boolean listening = false;

    private MediaRecorder recorder;
    private ParcelFileDescriptor pfd;
    private Uri currentUri;
    private boolean recording = false;

    @TargetApi(31)
    private class ModernCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            handleState(state);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        try {
            Notification n = buildNotification(recording ? "Recording call…" : "Waiting for calls…");
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Exception e) {
            // Android refused to start us in the background; user must reopen the app.
            stopSelf();
            return START_NOT_STICKY;
        }
        startListening();
        return START_STICKY;
    }

    private void startListening() {
        if (listening) return;
        tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                ModernCallback cb = new ModernCallback();
                tm.registerTelephonyCallback(getMainExecutor(), cb);
                modernCallback = cb;
            } else {
                legacyListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        handleState(state);
                    }
                };
                tm.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
            listening = true;
        } catch (SecurityException e) {
            stopSelf();
        }
    }

    private void handleState(int state) {
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            if (!recording) startRecording();
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            if (recording) stopRecording();
        }
    }

    private void startRecording() {
        SharedPreferences prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        boolean micOnly = prefs.getBoolean("mic_only", false);

        // Try the call-audio source first (works only on some phones/ROMs),
        // then fall back to the microphone.
        int[] sources;
        String[] names;
        if (micOnly) {
            sources = new int[]{MediaRecorder.AudioSource.MIC};
            names = new String[]{"MIC"};
        } else {
            sources = new int[]{
                    MediaRecorder.AudioSource.VOICE_CALL,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC};
            names = new String[]{"CALL", "VOICE", "MIC"};
        }

        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());

        for (int i = 0; i < sources.length; i++) {
            MediaRecorder r = null;
            ParcelFileDescriptor p = null;
            Uri uri = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, "Call_" + ts + "_" + names[i] + ".m4a");
                v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/CallRecordings");
                v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("Could not create file");
                p = getContentResolver().openFileDescriptor(uri, "w");
                if (p == null) throw new IllegalStateException("Could not open file");

                r = (Build.VERSION.SDK_INT >= 31) ? new MediaRecorder(this) : new MediaRecorder();
                r.setAudioSource(sources[i]);
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioEncodingBitRate(128000);
                r.setAudioSamplingRate(44100);
                r.setOutputFile(p.getFileDescriptor());
                r.prepare();
                r.start();

                recorder = r;
                pfd = p;
                currentUri = uri;
                recording = true;
                notifyText("Recording call… (" + names[i] + ")");
                return;
            } catch (Exception e) {
                if (r != null) {
                    try { r.release(); } catch (Exception ignored) { }
                }
                if (p != null) {
                    try { p.close(); } catch (Exception ignored) { }
                }
                if (uri != null) {
                    try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                }
            }
        }
        notifyText("Could not record this call on this phone");
    }

    private void stopRecording() {
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false; // stopped too quickly / nothing recorded
        }
        try { recorder.release(); } catch (Exception ignored) { }
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }

        try {
            if (ok) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.IS_PENDING, 0);
                getContentResolver().update(currentUri, v, null, null);
                notifyText("Saved. Waiting for calls…");
            } else {
                getContentResolver().delete(currentUri, null, null);
                notifyText("Waiting for calls…");
            }
        } catch (Exception ignored) { }

        recorder = null;
        pfd = null;
        currentUri = null;
        recording = false;
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Call recorder", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Recorder")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void notifyText(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        if (recording) stopRecording();
        if (tm != null) {
            try {
                if (Build.VERSION.SDK_INT >= 31 && modernCallback != null) {
                    tm.unregisterTelephonyCallback((TelephonyCallback) modernCallback);
                } else if (legacyListener != null) {
                    tm.listen(legacyListener, PhoneStateListener.LISTEN_NONE);
                }
            } catch (Exception ignored) { }
        }
        listening = false;
        super.onDestroy();
    }
}
