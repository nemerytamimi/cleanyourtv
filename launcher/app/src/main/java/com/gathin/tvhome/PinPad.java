package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/**
 * Kids guard in front of the Home Assistant buttons: a quartz-glass number pad that submits itself on the fourth
 * digit. A correct PIN unlocks the buttons for a few minutes; five wrong tries in a row pause the pad for 30 s.
 * Plain views and gradient shapes only (no blur), so it opens instantly on a slow box.
 */
final class PinPad {
    private static final int LEN = 4;
    private static final int MAX_TRIES = 5;
    private static final long COOLDOWN_MS = 30_000L;

    // Rose quartz to lavender quartz, on smoky crystal.
    private static final int QUARTZ_A = 0xFFF7CAC9, QUARTZ_B = 0xFFC9B6E4, MINT = 0xFF8FE3C8, ERROR = 0xFFFF8A8A;
    private static final int INK = 0xFF1C1626;

    private static long unlockedUntil;     // elapsedRealtime; shared by every pad in the process
    private static int wrongTries;
    private static long cooldownUntil;

    private final Activity activity;
    private String pin;
    private final android.content.SharedPreferences prefs;
    /** VERIFY asks for the PIN; NEW1 and NEW2 take a new one twice (changePin). */
    private static final int VERIFY = 0, NEW1 = 1, NEW2 = 2;
    private int mode = VERIFY;
    private String firstNew;
    private TextView title;
    private final long unlockMs;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Typeface light, regular, medium;

    private PopupWindow popup;
    private LinearLayout card;
    private TextView subtitle;
    private final View[] dots = new View[LEN];
    private final StringBuilder entered = new StringBuilder();
    private Runnable onUnlocked;
    private boolean busy;

    PinPad(Activity activity, String pin, int unlockMinutes) {
        this.activity = activity;
        // A PIN changed on the TV (home settings) wins over the one built in from config.xml.
        prefs = activity.getSharedPreferences("dashboard", android.app.Activity.MODE_PRIVATE);
        String changed = prefs.getString("pin_override", null);
        this.pin = changed != null ? changed : pin == null ? "" : pin.trim();
        this.unlockMs = Math.max(1, unlockMinutes) * 60_000L;
        this.density = activity.getResources().getDisplayMetrics().density;
        light = ResourcesCompat.getFont(activity, R.font.outfit_light);
        regular = ResourcesCompat.getFont(activity, R.font.outfit_regular);
        medium = ResourcesCompat.getFont(activity, R.font.outfit_medium);
    }

    /** Runs the action now when no PIN is set or the guard is still open; otherwise asks for the PIN first. */
    void guard(Runnable action) {
        if (pin.isEmpty() || SystemClock.elapsedRealtime() < unlockedUntil) {
            action.run();
            return;
        }
        onUnlocked = action;
        mode = VERIFY;
        show();
    }

    /** Takes a new PIN, twice, and keeps it on the box; done runs once it is saved. */
    void changePin(Runnable done) {
        onUnlocked = done;
        mode = NEW1;
        firstNew = null;
        show();
    }

    private String promptText() {
        return mode == NEW1 ? "أدخل الرمز الجديد" : mode == NEW2 ? "أعد إدخال الرمز الجديد" : "أدخل الرمز للتحكم بالبيت";
    }

    boolean isShowing() { return popup != null && popup.isShowing(); }

    void dismiss() { if (popup != null) popup.dismiss(); }

    // ---------------------------------------------------------------- look

    private int dp(float v) { return Math.round(v * density); }

    private GradientDrawable gradient(GradientDrawable.Orientation o, int a, int b, float radiusDp) {
        GradientDrawable d = new GradientDrawable(o, new int[]{a, b});
        d.setCornerRadius(radiusDp * density);
        return d;
    }

    private GradientDrawable oval(int a, int b, int stroke) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        d.setShape(GradientDrawable.OVAL);
        if (stroke != 0) d.setStroke(Math.max(1, Math.round(density)), stroke);
        return d;
    }

    /** Smoky crystal with a diagonal sheen and a faint rose edge. */
    private Drawable crystal() {
        GradientDrawable body = gradient(GradientDrawable.Orientation.TOP_BOTTOM, 0xF5302A3C, 0xF7171420, 34);
        body.setStroke(Math.max(1, Math.round(density)), 0x4DF7CAC9);
        GradientDrawable sheen = gradient(GradientDrawable.Orientation.TL_BR, 0x24FFFFFF, 0x00FFFFFF, 34);
        GradientDrawable glow = gradient(GradientDrawable.Orientation.BOTTOM_TOP, 0x1FC9B6E4, 0x00C9B6E4, 34);
        return new LayerDrawable(new Drawable[]{body, glow, sheen});
    }

    private TextView text(float sp, Typeface face, int color) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void show() {
        busy = false;
        entered.setLength(0);
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(0xD905060A);

        // Landscape, for a TV: the keypad on the left, the lock, title and dots on the right.
        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(34), dp(30), dp(44), dp(30));
        card.setBackground(crystal());
        LinearLayout pad = new LinearLayout(activity);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setClipChildren(false);
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout badge = new FrameLayout(activity);
        badge.setBackground(oval(QUARTZ_A, QUARTZ_B, 0));
        ImageView lock = new ImageView(activity);
        lock.setImageResource(R.drawable.ic_lock);
        lock.setColorFilter(INK);
        badge.addView(lock, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        info.addView(badge, new LinearLayout.LayoutParams(dp(60), dp(60)));

        title = text(26, medium, Color.WHITE);
        title.setText(mode == VERIFY ? "رمز الأهل" : "تغيير الرمز");
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-2, -2);
        tLp.topMargin = dp(18);
        info.addView(title, tLp);
        subtitle = text(13, regular, 0x99FFFFFF);
        subtitle.setText(promptText());
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-2, -2);
        sLp.topMargin = dp(8);
        info.addView(subtitle, sLp);

        LinearLayout dotRow = new LinearLayout(activity);
        dotRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < LEN; i++) {
            dots[i] = new View(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(14));
            lp.setMargins(dp(9), 0, dp(9), 0);
            dotRow.addView(dots[i], lp);
        }
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-2, -2);
        dLp.topMargin = dp(26);
        info.addView(dotRow, dLp);
        paintDots(0);

        // 1 2 3 / 4 5 6 / 7 8 9 / · 0 ⌫
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"};
        View five = null;
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            for (int c = 0; c < 3; c++) {
                String k = keys[r * 3 + c];
                View key = k.isEmpty() ? new View(activity) : makeKey(k);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(60), dp(60));
                lp.setMargins(dp(9), dp(6), dp(9), dp(6));
                row.addView(key, lp);
                if ("5".equals(k)) five = key;
            }
            pad.addView(row, new LinearLayout.LayoutParams(-2, -2));
        }
        card.addView(pad, new LinearLayout.LayoutParams(-2, -2));
        View divider = new View(activity);
        divider.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(Math.max(1, Math.round(density)), dp(220));
        divLp.setMargins(dp(30), 0, dp(36), 0);
        card.addView(divider, divLp);
        card.addView(info, new LinearLayout.LayoutParams(dp(250), -2));
        card.setClipChildren(false);

        scrim.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        popup = new PopupWindow(scrim, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOnDismissListener(() -> { popup = null; onUnlocked = null; });
        // The remote's number keys type too; Back closes (PopupWindow handles it).
        scrim.setFocusableInTouchMode(true);
        scrim.setOnKeyListener((v, keyCode, e) -> handleKey(keyCode, e));
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        if (five != null) five.requestFocus();
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        if (SystemClock.elapsedRealtime() < cooldownUntil) showCooldown();
    }

    private View makeKey(String label) {
        TextView key = text("⌫".equals(label) ? 21 : 26, light, Color.WHITE);
        key.setText(label);
        key.setFocusable(true);
        key.setClickable(true);
        styleKey(key, false);
        key.setOnFocusChangeListener((v, f) -> {
            styleKey((TextView) v, f);
            v.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).setDuration(120).start();
        });
        key.setOnClickListener(v -> press(label));
        key.setOnKeyListener((v, keyCode, e) -> handleKey(keyCode, e));
        return key;
    }

    private void styleKey(TextView key, boolean focused) {
        key.setBackground(focused ? oval(QUARTZ_A, QUARTZ_B, 0) : oval(0x1FFFFFFF, 0x0DFFFFFF, 0x33FFFFFF));
        key.setTextColor(focused ? INK : Color.WHITE);
        key.setTypeface(focused ? medium : light);
    }

    private void paintDots(int color) {
        for (int i = 0; i < LEN; i++) {
            boolean on = i < entered.length();
            if (color != 0) dots[i].setBackground(oval(color, color, 0));
            else dots[i].setBackground(on ? oval(QUARTZ_A, QUARTZ_B, 0) : oval(0x00000000, 0x00000000, 0x80FFFFFF));
        }
    }

    // ---------------------------------------------------------------- input

    private boolean handleKey(int keyCode, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            press(String.valueOf(keyCode - KeyEvent.KEYCODE_0));
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            press(String.valueOf(keyCode - KeyEvent.KEYCODE_NUMPAD_0));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            press("⌫");
            return true;
        }
        return false;
    }

    private void press(String k) {
        if (busy) return;
        if (SystemClock.elapsedRealtime() < cooldownUntil) {
            showCooldown();
            return;
        }
        if ("⌫".equals(k)) {
            if (entered.length() > 0) entered.setLength(entered.length() - 1);
            paintDots(0);
            return;
        }
        if (entered.length() >= LEN) return;
        entered.append(k);
        paintDots(0);
        if (entered.length() == LEN) main.postDelayed(this::submit, 140);   // let the last dot show
    }

    private void submit() {
        if (popup == null) return;
        busy = true;
        if (mode == NEW1) {
            firstNew = entered.toString();
            mode = NEW2;
            main.postDelayed(() -> {
                entered.setLength(0);
                paintDots(0);
                subtitle.setText(promptText());
                subtitle.setTextColor(0x99FFFFFF);
                busy = false;
            }, 200);
            return;
        }
        if (mode == NEW2) {
            if (entered.toString().equals(firstNew)) {
                pin = firstNew;
                prefs.edit().putString("pin_override", pin).apply();
                unlockedUntil = SystemClock.elapsedRealtime() + unlockMs;
                paintDots(MINT);
                subtitle.setText("تم حفظ الرمز الجديد");
                subtitle.setTextColor(MINT);
                Runnable done = onUnlocked;
                main.postDelayed(() -> { dismiss(); if (done != null) done.run(); }, 600);
            } else {
                paintDots(ERROR);
                ObjectAnimator.ofFloat(card, View.TRANSLATION_X, 0, -dp(14), dp(14), -dp(10), dp(10), 0).setDuration(380).start();
                mode = NEW1;
                main.postDelayed(() -> {
                    entered.setLength(0);
                    paintDots(0);
                    subtitle.setText("الرمزان غير متطابقين، أدخل الرمز الجديد");
                    subtitle.setTextColor(ERROR);
                    busy = false;
                }, 480);
            }
            return;
        }
        if (entered.toString().equals(pin)) {
            wrongTries = 0;
            unlockedUntil = SystemClock.elapsedRealtime() + unlockMs;
            paintDots(MINT);
            subtitle.setText("تم الفتح لمدة " + unlockMs / 60_000 + " دقائق");
            subtitle.setTextColor(MINT);
            Runnable action = onUnlocked;
            main.postDelayed(() -> {
                dismiss();
                if (action != null) action.run();
            }, 260);
            return;
        }
        wrongTries++;
        paintDots(ERROR);
        ObjectAnimator.ofFloat(card, View.TRANSLATION_X, 0, -dp(14), dp(14), -dp(10), dp(10), -dp(5), dp(5), 0)
                .setDuration(420).start();
        if (wrongTries >= MAX_TRIES) {
            wrongTries = 0;
            cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS;
        }
        main.postDelayed(() -> {
            entered.setLength(0);
            paintDots(0);
            busy = false;
            if (SystemClock.elapsedRealtime() < cooldownUntil) showCooldown();
            else {
                subtitle.setText("رمز غير صحيح، حاول مرة أخرى");
                subtitle.setTextColor(ERROR);
            }
        }, 480);
    }

    private void showCooldown() {
        long left = Math.max(1, (cooldownUntil - SystemClock.elapsedRealtime() + 999) / 1000);
        subtitle.setText("محاولات كثيرة، انتظر " + left + " ثانية");
        subtitle.setTextColor(ERROR);
        main.removeCallbacks(cooldownTick);
        main.postDelayed(cooldownTick, 1000);
    }

    private final Runnable cooldownTick = () -> {
        if (popup == null) return;
        if (SystemClock.elapsedRealtime() < cooldownUntil) showCooldown();
        else {
            subtitle.setText(promptText());
            subtitle.setTextColor(0x99FFFFFF);
        }
    };
}
