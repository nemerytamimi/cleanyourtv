package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The adhan at each prayer time (Fajr, Dhuhr, Asr, Maghrib, Isha): the first adhan_seconds of an audio file the
 * owner puts on the box (files/adhan.mp3, see README), whatever app is in front. The system alarm clock wakes this
 * receiver at the next prayer; it plays, fades out, gives the audio back (a video paused for it resumes) and
 * schedules the next one. Nothing runs between prayers. Rescheduled at boot, on a clock or time-zone change, and
 * whenever Home starts.
 */
public class Adhan extends BroadcastReceiver {
    private static final String TAG = "TvAdhan";
    private static final String ACTION = "com.gathin.tvhome.ADHAN";
    private static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final long LATE_MS = 3 * 60_000L;   // a prayer missed by more than this (box was off) stays silent
    private static final long FADE_MS = 400;

    /** The pushed audio file, or null when there is none. */
    static File audioFile(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return null;
        for (String name : new String[]{"adhan.mp3", "adhan.m4a", "adhan.ogg", "adhan.wav"}) {
            File f = new File(dir, name);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    /** Schedules the alarm for the next prayer (or cancels it when the adhan is off). */
    static void schedule(Context c) {
        Context app = c.getApplicationContext();
        AlarmManager am = app.getSystemService(AlarmManager.class);
        Intent i = new Intent(app, Adhan.class).setAction(ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(app, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am == null) return;
        if (!app.getResources().getBoolean(R.bool.adhan_enabled)) {
            am.cancel(pi);
            return;
        }
        long now = System.currentTimeMillis();
        long next = 0;
        int which = -1;
        for (int day = 0; day < 2 && next == 0; day++) {
            LocalDate date = LocalDate.now().plusDays(day);
            int[] t = times(app, date);
            for (int k = 0; k < 6; k++) {
                if (k == PrayerTimes.SUNRISE) continue;
                long at = date.atStartOfDay(ZoneId.systemDefault()).plusMinutes(t[k]).toInstant().toEpochMilli();
                if (at > now + 5000) { next = at; which = k; break; }
            }
        }
        if (next == 0) return;
        i.putExtra("at", next).putExtra("prayer", which);
        pi = PendingIntent.getBroadcast(app, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setWindow(AlarmManager.RTC_WAKEUP, next, 60_000L, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        }
        Log.i(TAG, "next adhan: " + NAMES[which] + " at " + ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), ZoneId.systemDefault()));
    }

    private static int[] times(Context c, LocalDate date) {
        Resources r = c.getResources();
        double tz = ZoneId.systemDefault().getRules().getOffset(date.atTime(12, 0)).getTotalSeconds() / 3600.0;
        return PrayerTimes.compute(date,
                Double.parseDouble(r.getString(R.string.dash_lat)), Double.parseDouble(r.getString(R.string.dash_lng)), tz,
                Double.parseDouble(r.getString(R.string.prayer_fajr_angle)),
                Double.parseDouble(r.getString(R.string.prayer_isha_angle)), r.getIntArray(R.array.prayer_offsets));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        if (!ACTION.equals(intent.getAction())) {        // boot, clock or time zone changed
            schedule(app);
            return;
        }
        long at = intent.getLongExtra("at", 0);
        schedule(app);                                     // the next one, whatever happens here
        if (!app.getResources().getBoolean(R.bool.adhan_enabled)) return;
        if (at > 0 && System.currentTimeMillis() - at > LATE_MS) return;
        File file = audioFile(app);
        if (file == null) {
            Log.w(TAG, "no adhan file in " + app.getExternalFilesDir(null));
            return;
        }
        play(app, file, goAsync());
    }

    /** Plays the first adhan_seconds with a fade at the end; the broadcast stays alive until it has finished. */
    private static void play(Context app, File file, PendingResult result) {
        int seconds = app.getResources().getInteger(R.integer.adhan_seconds);
        Handler main = new Handler(Looper.getMainLooper());
        AudioManager audio = app.getSystemService(AudioManager.class);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs).build();
        MediaPlayer player = new MediaPlayer();
        final boolean[] done = {false};
        // Wake the box (screen on, so HDMI-CEC can turn the TV on too) if it was asleep; released at the end.
        PowerManager pm = app.getSystemService(PowerManager.class);
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wake = pm == null ? null : pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "tvhome:adhan");
        if (wake != null) wake.acquire(seconds * 1000L + 10_000L);
        Runnable stop = () -> {
            if (done[0]) return;
            done[0] = true;
            try { player.stop(); } catch (RuntimeException ignored) { }
            player.release();
            if (audio != null) audio.abandonAudioFocusRequest(focus);
            if (wake != null && wake.isHeld()) wake.release();
            result.finish();
        };
        try {
            player.setAudioAttributes(attrs);
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(mp -> stop.run());
            player.setOnErrorListener((mp, what, extra) -> { stop.run(); return true; });
            player.prepare();
            if (audio != null) audio.requestAudioFocus(focus);   // a playing video pauses, and resumes after
            player.start();
            Log.i(TAG, "adhan playing " + file.getName());
            long endMs = seconds * 1000L;
            // Fade out over the last FADE_MS, in small steps.
            for (int step = 1; step <= 10; step++) {
                float v = 1f - step / 10f;
                main.postDelayed(() -> { if (!done[0]) player.setVolume(v, v); }, endMs - FADE_MS + step * FADE_MS / 10);
            }
            main.postDelayed(stop, endMs + 50);
        } catch (Exception e) {
            Log.w(TAG, "adhan: " + e);
            stop.run();
        }
    }
}
