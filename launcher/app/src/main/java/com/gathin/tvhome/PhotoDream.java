package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: a random photo from Lorem Picsum (free, no key; Unsplash photos) every 45 seconds, crossfaded, with
 * a slow zoom and a clock. Android starts it after the screen-off timeout when nothing keeps the screen on (video
 * apps do while they play), and puts the box to sleep after the sleep timeout. Two RGB_565 bitmaps at most, no
 * blur. Offline it shows the clock on black.
 */
public class PhotoDream extends DreamService {
    private static final String TAG = "TvDream";
    private static final long SLIDE_MS = 45_000L;
    private static final Locale AR = Locale.forLanguageTag("ar-u-nu-latn");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private ImageView front, back;
    private TextView clock, date;
    private Bitmap shown;
    private boolean running;

    private final Runnable next = new Runnable() {
        @Override public void run() {
            if (!running) return;
            loadNext();
            main.postDelayed(this, SLIDE_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Date now = new Date();
            clock.setText(new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(PhotoDream.this) ? "HH:mm" : "h:mm", Locale.US).format(now));
            date.setText(new SimpleDateFormat("EEEE، d MMMM", AR).format(now));
            main.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // The two photos swap order inside their own layer, so the shade and clock always stay on top.
        FrameLayout photos = new FrameLayout(this);
        back = image();
        front = image();
        photos.addView(back, new FrameLayout.LayoutParams(-1, -1));
        photos.addView(front, new FrameLayout.LayoutParams(-1, -1));
        root.addView(photos, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x00000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.VERTICAL);
        clock = new TextView(this);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 84);
        clock.setTypeface(ResourcesCompat.getFont(this, R.font.outfit_extralight));
        clock.setTextColor(Color.WHITE);
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(12 * d, 0, 0, 0x80000000);
        date = new TextView(this);
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        date.setTypeface(ResourcesCompat.getFont(this, R.font.outfit_regular));
        date.setTextColor(0xDDFFFFFF);
        date.setShadowLayer(8 * d, 0, 0, 0x80000000);
        corner.addView(clock, new LinearLayout.LayoutParams(-2, -2));   // its own width, not the date's
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = Math.round(6 * d);
        dl.leftMargin = Math.round(6 * d);
        corner.addView(date, dl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        cl.setMargins(Math.round(56 * d), 0, 0, Math.round(48 * d));
        root.addView(corner, cl);
        setContentView(root);
    }

    private ImageView image() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        return v;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        main.post(tick);
        main.post(next);
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        main.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        running = false;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void loadNext() {
        String url = "https://picsum.photos/seed/" + Integer.toHexString(random.nextInt()) + "/1920/1080";
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                Bitmap b;
                try (InputStream in = c.getInputStream()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    b = BitmapFactory.decodeStream(in, null, o);
                } finally {
                    c.disconnect();
                }
                if (b != null) main.post(() -> show(b));
            } catch (Exception e) {
                Log.w(TAG, "photo: " + e.getMessage());
            }
        });
    }

    /** Crossfade to the new photo, with a slow zoom over the slide's time. */
    private void show(Bitmap b) {
        if (!running) { b.recycle(); return; }
        ImageView in = back, out = front;
        in.setImageBitmap(b);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.animate().alpha(1f).setDuration(1800).start();
        in.animate().scaleX(1.08f).scaleY(1.08f).setDuration(SLIDE_MS + 2000).start();
        Bitmap old = shown;
        out.animate().alpha(0f).setDuration(1800).withEndAction(() -> {
            out.setImageDrawable(null);
            if (old != null && !old.isRecycled()) old.recycle();
        }).start();
        in.bringToFront();
        shown = b;
        front = in;
        back = out;
    }
}
