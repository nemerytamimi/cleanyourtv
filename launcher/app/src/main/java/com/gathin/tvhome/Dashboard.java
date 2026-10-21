package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.icu.util.IslamicCalendar;
import android.icu.util.ULocale;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's upper half: greeting, a large clock with the Gregorian and Hijri dates, a weather card, a
 * prayer card (next prayer, countdown, progress, all six times) and a row of Home Assistant tiles, one of which
 * can open a panel with more controls.
 *
 * Kept light for a slow TV: plain views, shape and vector drawables, no blur. The clock and countdown move on the
 * system's minute tick (no timers of our own); prayer times are computed on the device; weather is fetched at
 * most every 30 minutes and Home Assistant read once a minute, both only while Home is on screen.
 */
final class Dashboard {
    private static final String TAG = "TvDash";
    private static final long WEATHER_MAX_AGE_MS = 30 * 60_000L;
    private static final String[] PRAYER_NAMES = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final String[] HIJRI_MONTHS = {"محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى",
            "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"};
    private static final Locale AR = Locale.forLanguageTag("ar-u-nu-latn");

    private static final int TEXT = 0xFFFFFFFF, TEXT_2 = 0xCCFFFFFF, TEXT_3 = 0x8CFFFFFF, TEXT_4 = 0x59FFFFFF;
    private static final int ACCENT = 0xFF7FE0C6;      // prayer
    private static final int WARM = 0xFFFFC56B;        // something that is on
    private static final int CARD_TOP = 0x26FFFFFF, CARD_BOTTOM = 0x0FFFFFFF, CARD_STROKE = 0x24FFFFFF;

    interface Host {
        void focusDockFromDashboard();
        void focusTopBarFromDashboard();
    }

    private final Activity activity;
    private final Host host;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final Typeface extraLight, light, regular, medium;

    private final double lat, lng, fajrAngle, ishaAngle;
    private final int[] offsets;
    private final String place, haUrl, groupLabel, groupSensorSpec;
    private final String[] haSpecs, groupSpecs;
    private final boolean hijri;

    final LinearLayout view;
    private TextView greeting, clock, ampm, dateLine, weatherRange;
    private ImageView weatherIcon;
    private TextView weatherTemp, weatherDesc, weatherMeta;
    private TextView nextLabel, nextName, nextTime, nextIn;
    private View progressFill, progressRest;
    private final TextView[] miniName = new TextView[6], miniTime = new TextView[6];
    private HorizontalScrollView tileScroll;
    private LinearLayout tileRow;

    private final List<Item> tiles = new ArrayList<>();
    private final List<Item> groupItems = new ArrayList<>();
    private Item groupSensor;
    private int lastTile = 0;
    private int contentWidth;
    private int[] todayTimes;
    private LocalDate todayDate;
    private boolean loading, resumed;
    private static boolean loggedTiles;
    private PopupWindow panel;
    private final PinPad pinPad;
    private LinearLayout panelRows;
    private TextView panelTitle;

    /** One Home Assistant entity (or the group tile). */
    private static final class Item {
        String entityId, domain, label, state = "", unit = "";
        int position = -1, features;    // covers: current_position (0 closed .. 100 open), supported_features
        boolean on, group;
        View tile;
        ImageView icon;
        View iconWrap;
        TextView name, status;
    }

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { onMinute(); }
    };

    Dashboard(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        Resources res = activity.getResources();
        density = res.getDisplayMetrics().density;
        prefs = activity.getSharedPreferences("dashboard", Activity.MODE_PRIVATE);
        extraLight = ResourcesCompat.getFont(activity, R.font.outfit_extralight);
        light = ResourcesCompat.getFont(activity, R.font.outfit_light);
        regular = ResourcesCompat.getFont(activity, R.font.outfit_regular);
        medium = ResourcesCompat.getFont(activity, R.font.outfit_medium);
        lat = Double.parseDouble(res.getString(R.string.dash_lat));
        lng = Double.parseDouble(res.getString(R.string.dash_lng));
        place = res.getString(R.string.dash_place);
        fajrAngle = Double.parseDouble(res.getString(R.string.prayer_fajr_angle));
        ishaAngle = Double.parseDouble(res.getString(R.string.prayer_isha_angle));
        offsets = res.getIntArray(R.array.prayer_offsets);
        hijri = res.getBoolean(R.bool.hijri_date);
        haUrl = res.getString(R.string.ha_url).replaceAll("/+$", "");
        haSpecs = res.getStringArray(R.array.ha_items);
        groupLabel = res.getString(R.string.ha_group_label);
        groupSensorSpec = res.getString(R.string.ha_group_sensor);
        groupSpecs = res.getStringArray(R.array.ha_group_items);
        pinPad = new PinPad(activity, res.getString(R.string.ha_pin), res.getInteger(R.integer.ha_pin_minutes));
        view = build();
    }

    // ================================================================ layout

    private int dp(float v) { return Math.round(v * density); }

    private GradientDrawable shape(float radiusDp, int top, int bottom, int stroke) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        d.setCornerRadius(radiusDp * density);
        if (stroke != 0) d.setStroke(Math.max(1, Math.round(density)), stroke);
        return d;
    }

    private GradientDrawable card() { return shape(24, CARD_TOP, CARD_BOTTOM, CARD_STROKE); }

    private TextView text(float sp, Typeface face, int color) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** One line that shrinks (down to 10sp) to fit instead of ellipsizing; singleLine would disable autosize. */
    private static void fitOneLine(TextView t, int maxSp) {
        t.setSingleLine(false);
        t.setMaxLines(1);
        t.setEllipsize(null);
        t.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR);
        t.setAutoSizeTextTypeUniformWithConfiguration(10, maxSp, 1, TypedValue.COMPLEX_UNIT_SP);
    }

    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }

    private LinearLayout row(int gravity) {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(gravity);
        l.setBaselineAligned(false);
        return l;
    }

    private LinearLayout column(int gravity) {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(gravity);
        return l;
    }

    private LinearLayout build() {
        LinearLayout root = column(Gravity.LEFT);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        // A: greeting, level with the top bar's pills.
        greeting = text(22, light, TEXT);
        dateLine = text(16, regular, TEXT_3);
        LinearLayout a = row(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        a.addView(greeting, wrap());
        View dot = new View(activity);
        dot.setBackground(shape(2, 0x40FFFFFF, 0x40FFFFFF, 0));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(4), dp(4));
        dotLp.setMargins(dp(16), 0, dp(16), 0);
        a.addView(dot, dotLp);
        a.addView(dateLine, wrap());
        root.addView(a, new LinearLayout.LayoutParams(-1, dp(42)));

        // B: clock | weather | prayer.
        LinearLayout b = row(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, dp(160));
        bLp.topMargin = dp(10);
        root.addView(b, bLp);

        LinearLayout clockCol = column(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        LinearLayout clockRow = row(Gravity.BOTTOM);
        clock = text(92, extraLight, TEXT);
        clock.setLetterSpacing(-0.04f);
        ampm = text(20, regular, TEXT_3);
        LinearLayout.LayoutParams apLp = wrap();
        apLp.setMarginStart(dp(10));
        apLp.bottomMargin = dp(14);
        clockRow.addView(clock, wrap());
        clockRow.addView(ampm, apLp);
        clockCol.addView(clockRow, wrap());
        b.addView(clockCol, new LinearLayout.LayoutParams(0, -1, 1));

        b.addView(buildWeather(), cardLp(240));
        b.addView(buildPrayer(), cardLp(372));

        // C: Home Assistant tiles.
        tileScroll = new HorizontalScrollView(activity);
        tileScroll.setHorizontalScrollBarEnabled(false);
        tileScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        tileScroll.setClipChildren(false);
        tileScroll.setClipToPadding(false);
        tileScroll.setFocusable(false);
        tileRow = row(Gravity.CENTER_VERTICAL);
        tileRow.setClipChildren(false);
        tileRow.setClipToPadding(false);
        tileRow.setPadding(0, dp(4), 0, dp(4));
        tileScroll.addView(tileRow, new FrameLayout.LayoutParams(-2, -1));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, dp(78));
        cLp.topMargin = dp(10);
        root.addView(tileScroll, cLp);
        tileScroll.setVisibility(View.INVISIBLE);
        return root;
    }

    private LinearLayout.LayoutParams cardLp(int widthDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp), -1);
        lp.setMarginStart(dp(16));
        return lp;
    }

    private View buildWeather() {
        LinearLayout card = column(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(12), dp(18), dp(12));
        card.setBackground(card());
        LinearLayout top = row(Gravity.CENTER_VERTICAL);
        weatherIcon = new ImageView(activity);
        top.addView(weatherIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        weatherTemp = text(44, light, TEXT);
        weatherTemp.setLetterSpacing(-0.03f);
        LinearLayout.LayoutParams tLp = wrap();
        tLp.setMarginStart(dp(14));
        top.addView(weatherTemp, tLp);
        card.addView(top, wrap());
        // Description and range apart: mixed in one RTL line the arrows and degrees come out reversed.
        LinearLayout descRow = row(Gravity.CENTER_VERTICAL);
        weatherRange = text(15, regular, TEXT_2);
        weatherRange.setTextDirection(View.TEXT_DIRECTION_LTR);
        weatherDesc = text(15, medium, TEXT_2);
        descRow.addView(weatherRange, wrap());
        LinearLayout.LayoutParams wdLp = wrap();
        wdLp.setMarginStart(dp(12));
        descRow.addView(weatherDesc, wdLp);
        LinearLayout.LayoutParams dLp = wrap();
        dLp.topMargin = dp(8);
        card.addView(descRow, dLp);
        weatherMeta = text(13, regular, TEXT_3);
        LinearLayout.LayoutParams mLp = wrap();
        mLp.topMargin = dp(5);
        card.addView(weatherMeta, mLp);
        return card;
    }

    private View buildPrayer() {
        LinearLayout card = column(Gravity.RIGHT);
        card.setPadding(dp(20), dp(12), dp(20), dp(10));
        card.setBackground(card());

        // Next prayer, right-aligned (Arabic reading order); the countdown on the left of the same line.
        LinearLayout head = row(Gravity.CENTER_VERTICAL);
        nextIn = text(15, medium, ACCENT);
        head.addView(nextIn, wrap());
        head.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout nameCol = column(Gravity.RIGHT);
        nextLabel = text(12, regular, TEXT_3);
        nextLabel.setText("الصلاة القادمة  ·  " + place);
        LinearLayout big = row(Gravity.BOTTOM);
        nextTime = text(28, light, TEXT);
        nextName = text(24, medium, TEXT);
        LinearLayout.LayoutParams nLp = wrap();
        nLp.setMarginStart(dp(12));
        big.addView(nextTime, wrap());
        big.addView(nextName, nLp);
        nameCol.addView(nextLabel, wrap());
        LinearLayout.LayoutParams bigLp = wrap();
        bigLp.topMargin = dp(4);
        nameCol.addView(big, bigLp);
        head.addView(nameCol, wrap());
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // Progress from the previous prayer to the next.
        LinearLayout bar = row(Gravity.CENTER_VERTICAL);
        bar.setBackground(shape(2, 0x1FFFFFFF, 0x1FFFFFFF, 0));
        progressRest = new View(activity);
        progressFill = new View(activity);
        progressFill.setBackground(shape(2, ACCENT, 0xFF4FB79A, 0));
        bar.addView(progressRest, new LinearLayout.LayoutParams(0, -1, 0));
        bar.addView(progressFill, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(4));
        barLp.topMargin = dp(8);
        card.addView(bar, barLp);

        // All six, Fajr on the right.
        LinearLayout mini = row(Gravity.BOTTOM);
        for (int k = 5; k >= 0; k--) {
            LinearLayout cell = column(Gravity.CENTER_HORIZONTAL);
            miniName[k] = text(11, regular, TEXT_3);
            miniName[k].setText(PRAYER_NAMES[k]);
            miniTime[k] = text(15, regular, TEXT_2);
            LinearLayout.LayoutParams t = wrap();
            t.topMargin = dp(3);
            cell.addView(miniName[k], wrap());
            cell.addView(miniTime[k], t);
            mini.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(-1, 0, 1);
        miniLp.topMargin = dp(6);
        card.addView(mini, miniLp);
        return card;
    }

    /** Places the dashboard at the top of the screen, under the main layout's pills. */
    void attach(FrameLayout parent, int top, int side) {
        // Padding inside, so a focused tile's zoom is not clipped at the screen-side edges.
        int pad = dp(10);
        view.setPadding(pad, 0, pad, 0);
        contentWidth = activity.getResources().getDisplayMetrics().widthPixels - 2 * side;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2);
        lp.topMargin = top;
        lp.leftMargin = side - pad;
        lp.rightMargin = side - pad;
        if (view.getParent() == null) parent.addView(view, 3, lp);
        else view.setLayoutParams(lp);
    }

    // ================================================================ lifecycle

    void onResume() {
        resumed = true;
        try {
            activity.registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        } catch (RuntimeException ignored) {
        }
        updateClock();
        updatePrayer();
        Adhan.schedule(activity);
        showCachedWeather();
        refreshWeatherIfStale();
        refreshHomeAssistant();
    }

    void onPause() {
        resumed = false;
        try {
            activity.unregisterReceiver(tick);
        } catch (RuntimeException ignored) {
        }
        if (panel != null) panel.dismiss();
        pinPad.dismiss();
        if (blindPopup != null) blindPopup.dismiss();
        if (settingsPopup != null) settingsPopup.dismiss();
    }

    private void onMinute() {
        if (!resumed) return;
        updateClock();
        updatePrayer();
        refreshWeatherIfStale();
        refreshHomeAssistant();
    }

    void destroy() { io.shutdownNow(); }

    // ================================================================ clock and dates

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY), m = now.get(Calendar.MINUTE);
        boolean h24 = DateFormat.is24HourFormat(activity);
        int shown = h24 ? h : (h % 12 == 0 ? 12 : h % 12);
        clock.setText(String.format(Locale.US, h24 ? "%02d:%02d" : "%d:%02d", shown, m));
        ampm.setText(h24 ? "" : (h < 12 ? "ص" : "م"));
        greeting.setText(h < 4 ? "طابت ليلتك" : h < 12 ? "صباح الخير" : "مساء الخير");
        String g = new SimpleDateFormat("EEEE، d MMMM", AR).format(now.getTime());
        String hd = hijriDate();
        dateLine.setText(hd == null ? g : g + "   ·   " + hd);
    }

    private String hijriDate() {
        if (!hijri) return null;
        IslamicCalendar cal = new IslamicCalendar(ULocale.forLanguageTag("ar"));
        cal.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        cal.setTime(new Date());
        return cal.get(IslamicCalendar.DAY_OF_MONTH) + " " + HIJRI_MONTHS[cal.get(IslamicCalendar.MONTH)]
                + " " + cal.get(IslamicCalendar.YEAR) + " هـ";
    }

    // ================================================================ prayer times

    private String hm(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        if (!DateFormat.is24HourFormat(activity)) h = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.US, "%d:%02d", h, m);
    }

    private void updatePrayer() {
        LocalDate today = LocalDate.now();
        if (!today.equals(todayDate)) {
            double tz = ZoneId.systemDefault().getRules().getOffset(today.atTime(12, 0)).getTotalSeconds() / 3600.0;
            todayTimes = PrayerTimes.compute(today, lat, lng, tz, fajrAngle, ishaAngle, offsets);
            todayDate = today;
            for (int k = 0; k < 6; k++) miniTime[k].setText(hm(todayTimes[k]));
        }
        LocalTime t = LocalTime.now();
        int now = t.getHour() * 60 + t.getMinute();
        int next = -1;
        for (int k = 0; k < 6; k++) {
            if (k == PrayerTimes.SUNRISE) continue;
            if (todayTimes[k] > now) { next = k; break; }
        }
        int nextAt, prevAt;
        if (next == -1) {                                   // after Isha: tomorrow's Fajr
            next = PrayerTimes.FAJR;
            nextAt = todayTimes[PrayerTimes.FAJR] + 1440;
            prevAt = todayTimes[PrayerTimes.ISHA];
        } else if (next == PrayerTimes.FAJR) {               // after midnight, before Fajr
            nextAt = todayTimes[PrayerTimes.FAJR];
            prevAt = todayTimes[PrayerTimes.ISHA] - 1440;
        } else {
            nextAt = todayTimes[next];
            int p = next - 1;
            if (p == PrayerTimes.SUNRISE) p = PrayerTimes.FAJR;
            prevAt = todayTimes[p];
        }
        int left = nextAt - now;
        nextName.setText(PRAYER_NAMES[next]);
        nextTime.setText(hm(todayTimes[next]));
        nextIn.setText(left >= 60 ? "بعد " + left / 60 + " س " + left % 60 + " د" : "بعد " + left + " دقيقة");
        float frac = Math.max(0f, Math.min(1f, (now - prevAt) / (float) Math.max(1, nextAt - prevAt)));
        // The fill grows from the right, the way Arabic reads.
        ((LinearLayout.LayoutParams) progressRest.getLayoutParams()).weight = 1 - frac;
        ((LinearLayout.LayoutParams) progressFill.getLayoutParams()).weight = frac;
        progressFill.requestLayout();
        for (int k = 0; k < 6; k++) {
            boolean n = k == next;
            miniName[k].setTextColor(n ? ACCENT : k == PrayerTimes.SUNRISE ? TEXT_4 : TEXT_3);
            miniTime[k].setTextColor(n ? ACCENT : k == PrayerTimes.SUNRISE ? TEXT_3 : TEXT_2);
            miniTime[k].setTypeface(n ? medium : regular);
        }
    }

    // ================================================================ weather (Open-Meteo, no key)

    private void showCachedWeather() {
        String json = prefs.getString("weather", null);
        if (json == null) {
            setWeatherIcon(R.drawable.ic_wx_cloud, 0xFFDDE6F0);
            weatherTemp.setText("--°");
            weatherDesc.setText(place);
            weatherRange.setText("");
            weatherMeta.setText("");
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            int code = cur.getInt("weather_code");
            boolean day = cur.optInt("is_day", 1) == 1;
            weatherTemp.setText(Math.round(cur.getDouble("temperature_2m")) + "°");
            if (code == 0 || code == 1) setWeatherIcon(day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon, day ? 0xFFFFD27A : 0xFFD6DEFF);
            else if (code >= 95) setWeatherIcon(R.drawable.ic_wx_storm, 0xFFB7C4FF);
            else if (code >= 51 && code <= 67 || code >= 80 && code <= 82) setWeatherIcon(R.drawable.ic_wx_rain, 0xFF9CCBFF);
            else setWeatherIcon(R.drawable.ic_wx_cloud, 0xFFDDE6F0);
            weatherDesc.setText(describe(code));
            weatherRange.setText("↑" + Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0))
                    + "°  ↓" + Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)) + "°");
            weatherMeta.setText("رطوبة " + cur.optInt("relative_humidity_2m") + "%   ·   رياح "
                    + Math.round(cur.optDouble("wind_speed_10m", 0)) + " كم/س");
        } catch (Exception e) {
            Log.w(TAG, "weather cache: " + e);
        }
    }

    private void setWeatherIcon(int res, int color) {
        weatherIcon.setImageResource(res);
        weatherIcon.setImageTintList(ColorStateList.valueOf(color));
    }

    private void refreshWeatherIfStale() {
        if (System.currentTimeMillis() - prefs.getLong("weather_at", 0) < WEATHER_MAX_AGE_MS
                && prefs.getString("weather", "").contains("relative_humidity_2m")) return;
        String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1", lat, lng);
        io.execute(() -> {
            try {
                String body = http("GET", url, null, null);
                new JSONObject(body).getJSONObject("current");   // only cache what parses
                prefs.edit().putString("weather", body).putLong("weather_at", System.currentTimeMillis()).apply();
                main.post(this::showCachedWeather);
            } catch (Exception e) {
                Log.w(TAG, "weather: " + e);
            }
        });
    }

    private static String describe(int code) {
        if (code == 0) return "صافٍ";
        if (code == 1) return "صافٍ غالبًا";
        if (code == 2) return "غائم جزئيًا";
        if (code == 3) return "غائم";
        if (code == 45 || code == 48) return "ضباب";
        if (code >= 51 && code <= 57) return "رذاذ";
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return "مطر";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "ثلج";
        if (code >= 95) return "عاصفة رعدية";
        return "";
    }

    // ================================================================ Home Assistant: data

    /** The token, moved on first sight from the pushed file into private storage (see README). */
    private String token() {
        try {
            File priv = new File(activity.getFilesDir(), "ha_token");
            File dir = activity.getExternalFilesDir(null);
            File pushed = dir == null ? null : new File(dir, "ha_token");
            if (pushed != null && pushed.isFile()) {
                byte[] b = readAll(new FileInputStream(pushed));
                try (OutputStream out = new FileOutputStream(priv)) { out.write(b); }
                //noinspection ResultOfMethodCallIgnored
                pushed.delete();
            }
            if (!priv.isFile()) return null;
            String t = new String(readAll(new FileInputStream(priv)), StandardCharsets.UTF_8).trim();
            return t.isEmpty() ? null : t;
        } catch (Exception e) {
            Log.w(TAG, "token: " + e);
            return null;
        }
    }

    private void refreshHomeAssistant() {
        if (loading) return;
        loading = true;
        io.execute(() -> {
            Loaded result = null;
            try {
                String token = token();
                if (token != null) result = load(token);
            } catch (Exception e) {
                Log.w(TAG, "home assistant: " + e);
            }
            final Loaded r = result;
            main.post(() -> {
                loading = false;
                if (r != null) show(r);
            });
        });
    }

    private static final class Loaded {
        final List<Item> tiles = new ArrayList<>(), group = new ArrayList<>();
        Item sensor;
    }

    private static boolean exactId(String spec) {
        return !spec.startsWith("~") && !spec.startsWith("@") && spec.contains(".") && !spec.contains(":");
    }

    /**
     * Resolves the configured specs (exact ids need no lookup; names are matched once against /api/states and
     * remembered until the config changes), then reads each entity's state.
     */
    private Loaded load(String token) throws Exception {
        List<String> all = new ArrayList<>();
        for (String s : tileSpecs()) all.add("t\t" + s);
        for (String s : groupSensorSpec.split(",")) all.add("s\t" + s.trim());
        for (String s : groupSpecs) all.add("g\t" + s);
        String specs = TextUtils.join("\n", all);

        String cached = specs.equals(prefs.getString("ha_specs2", null)) ? prefs.getString("ha_resolved2", null) : null;
        if (cached == null) {
            boolean needList = false;
            for (String line : all) {
                String spec = line.substring(2).split("\\|", 2)[0].trim();
                if (!spec.isEmpty() && !spec.equals("@group") && !exactId(spec)) needList = true;
            }
            JSONArray states = needList ? new JSONArray(http("GET", haUrl + "/api/states", token, null)) : new JSONArray();
            for (int i = 0; i < states.length(); i++) {
                JSONObject e = states.getJSONObject(i);
                if (useful(e)) Log.i(TAG, "entity " + e.getString("entity_id") + " | " + friendly(e));
            }
            StringBuilder store = new StringBuilder();
            for (String line : all) {
                String section = line.substring(0, 1);
                String spec = line.substring(2), label = "";
                int bar = spec.indexOf('|');
                if (bar >= 0) { label = spec.substring(bar + 1).trim(); spec = spec.substring(0, bar); }
                spec = spec.trim();
                if (spec.isEmpty()) continue;
                if (spec.equals("@group") || exactId(spec)) {
                    store.append(section).append('\t').append(spec).append('\t').append(label).append('\n');
                    continue;
                }
                for (int i = 0; i < states.length(); i++) {
                    JSONObject e = states.getJSONObject(i);
                    if (matches(spec, e)) {
                        store.append(section).append('\t').append(e.getString("entity_id")).append('\t').append(label).append('\n');
                        if (section.equals("s")) break;          // one sensor for the group tile
                    }
                }
            }
            cached = store.toString();
            prefs.edit().putString("ha_resolved2", cached).putString("ha_specs2", specs).apply();
            Log.i(TAG, "resolved:\n" + cached);
        }

        if (!loggedTiles) {
            loggedTiles = true;
            Log.i(TAG, "tiles:\n" + cached);
        }
        Loaded out = new Loaded();
        List<String> seen = new ArrayList<>();
        for (String line : cached.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p.length < 2 || p[1].trim().isEmpty()) continue;
            String section = p[0].trim(), id = p[1].trim(), label = p.length > 2 ? p[2].trim() : "";
            if (id.equals("@group")) {
                Item g = new Item();
                g.group = true;
                g.entityId = "@group";
                g.domain = "group";
                g.label = groupLabel;
                out.tiles.add(g);
                continue;
            }
            if (seen.contains(section + id)) continue;
            seen.add(section + id);
            Item it = read(token, id, label);
            if (section.equals("t")) out.tiles.add(it);
            else if (section.equals("g")) out.group.add(it);
            else {
                Log.i(TAG, "sensor " + id + " = " + it.state + " " + it.unit);
                // The first sensor with a reading wins; an unavailable one only stands in until then.
                if (out.sensor == null || (!hasReading(out.sensor) && hasReading(it))) out.sensor = it;
            }
        }
        Item settings = new Item();
        settings.entityId = SETTINGS_ID;
        settings.domain = "settings";
        settings.label = "إعدادات المنزل";
        out.tiles.add(settings);
        return out;
    }

    private static final String SETTINGS_ID = "@settings";

    /** The tiles: chosen on the TV (home settings) if that was ever saved, else ha_items from config.xml. */
    private String[] tileSpecs() {
        String override = prefs.getString("tiles_override", null);
        return override == null ? haSpecs : override.split("\n");
    }

    private Item read(String token, String id, String label) {
        Item it = new Item();
        it.entityId = id;
        it.domain = id.substring(0, Math.max(0, id.indexOf('.')));
        it.label = label.isEmpty() ? id : label;
        try {
            JSONObject e = new JSONObject(http("GET", haUrl + "/api/states/" + id, token, null));
            JSONObject attr = e.optJSONObject("attributes");
            if (label.isEmpty() && attr != null) it.label = attr.optString("friendly_name", id);
            it.state = e.optString("state", "");
            it.unit = attr == null ? "" : attr.optString("unit_of_measurement", "");
            if (attr != null) {
                it.position = attr.optInt("current_position", -1);
                it.features = attr.optInt("supported_features", 0);
            }
        } catch (Exception ex) {
            Log.w(TAG, "read " + id + ": " + ex.getMessage());
            it.state = "unavailable";
        }
        it.on = "on".equals(it.state) || "open".equals(it.state) || "cleaning".equals(it.state) || "heat".equals(it.state);
        return it;
    }

    private static boolean hasReading(Item it) {
        return !it.state.isEmpty() && !"unavailable".equals(it.state) && !"unknown".equals(it.state);
    }

    private static String friendly(JSONObject e) {
        JSONObject a = e.optJSONObject("attributes");
        String id = e.optString("entity_id");
        return a == null ? id : a.optString("friendly_name", id);
    }

    private static boolean useful(JSONObject e) {
        String id = e.optString("entity_id");
        String domain = id.substring(0, Math.max(0, id.indexOf('.')));
        switch (domain) {
            case "light": case "switch": case "fan": case "cover": case "button": case "input_button":
            case "input_boolean": case "script": case "scene": case "automation": case "vacuum":
            case "water_heater": case "climate":
                return true;
            case "sensor":
                JSONObject a = e.optJSONObject("attributes");
                if (a == null) return false;
                String unit = a.optString("unit_of_measurement");
                return "power".equals(a.optString("device_class")) || "W".equals(unit) || "kW".equals(unit);
            default:
                return false;
        }
    }

    private static boolean matches(String spec, JSONObject e) {
        String id = e.optString("entity_id"), name = friendly(e);
        if (spec.startsWith("~")) {
            String q = spec.substring(1).trim().toLowerCase(Locale.ROOT);
            return useful(e) && (name.toLowerCase(Locale.ROOT).contains(q) || id.toLowerCase(Locale.ROOT).contains(q));
        }
        int colon = spec.indexOf(':');
        if (colon > 0) {
            return id.startsWith(spec.substring(0, colon) + ".") && name.equalsIgnoreCase(spec.substring(colon + 1).trim());
        }
        return id.equals(spec);
    }

    // ================================================================ Home Assistant: tiles

    private void show(Loaded r) {
        groupSensor = r.sensor;
        // Group items keep their row views while the panel is open.
        if (groupItems.size() == r.group.size()) {
            for (int i = 0; i < r.group.size(); i++) copyState(r.group.get(i), groupItems.get(i));
        } else {
            groupItems.clear();
            groupItems.addAll(r.group);
        }
        boolean same = r.tiles.size() == tiles.size();
        for (int i = 0; same && i < r.tiles.size(); i++) same = r.tiles.get(i).entityId.equals(tiles.get(i).entityId);
        if (!same) {
            boolean hadFocus = tileRow.hasFocus();
            tileRow.removeAllViews();
            tiles.clear();
            int count = r.tiles.size();
            int avail = contentWidth > 0 ? contentWidth : activity.getResources().getDisplayMetrics().widthPixels - dp(112);
            int gap = dp(14);
            int per = Math.max(1, Math.min(count, 5));
            int width = Math.max(dp(150), (avail - gap * (per - 1)) / per);
            for (int i = 0; i < count; i++) {
                Item it = r.tiles.get(i);
                makeTile(it);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, -1);
                if (i < count - 1) lp.setMarginEnd(gap);
                tileRow.addView(it.tile, lp);
                tiles.add(it);
            }
            tileScroll.setVisibility(tiles.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            if (hadFocus) focusFromBelow();
        } else {
            for (int i = 0; i < tiles.size(); i++) copyState(r.tiles.get(i), tiles.get(i));
        }
        for (Item it : tiles) bindTile(it);
        if (panel != null && panel.isShowing()) bindPanel();
        if (blindPopup != null && blindPopup.isShowing()) bindBlind(false);
    }

    private static void copyState(Item from, Item to) {
        to.state = from.state;
        to.on = from.on;
        to.unit = from.unit;
        to.label = from.label;
        to.position = from.position;
        to.features = from.features;
    }

    private int iconFor(Item it) {
        if (it.group) return R.drawable.ic_ha_server;
        if (SETTINGS_ID.equals(it.entityId)) return R.drawable.ic_settings;
        String n = (it.label + " " + it.entityId).toLowerCase(Locale.ROOT);
        if (n.contains("boiler") || n.contains("سخان") || n.contains("heater") || "water_heater".equals(it.domain))
            return R.drawable.ic_ha_flame;
        switch (it.domain) {
            case "light": return R.drawable.ic_ha_bulb;
            case "fan": return R.drawable.ic_ha_fan;
            case "cover": return R.drawable.ic_ha_blind;
            case "sensor": return R.drawable.ic_ha_bolt;
            case "script": case "scene": case "button": case "input_button": return R.drawable.ic_ha_play;
            default: return R.drawable.ic_ha_power;
        }
    }

    private void makeTile(Item it) {
        LinearLayout tile = row(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(12), 0, dp(12), 0);
        tile.setFocusable(true);
        tile.setClickable(true);
        FrameLayout wrap = new FrameLayout(activity);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconFor(it));
        wrap.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        tile.addView(wrap, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = column(Gravity.LEFT);
        it.name = text(15, medium, TEXT);
        // Long names (or a large system font scale) shrink to fit the tile instead of being cut off.
        fitOneLine(it.name, 15);
        it.status = text(13, regular, TEXT_3);
        fitOneLine(it.status, 13);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, dp(18));
        sLp.topMargin = dp(5);
        texts.addView(it.name, new LinearLayout.LayoutParams(-1, dp(22)));
        texts.addView(it.status, sLp);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, -2, 1);
        tLp.setMarginStart(dp(10));
        tile.addView(texts, tLp);
        it.tile = tile;
        it.icon = icon;
        it.iconWrap = wrap;
        // Behind the kids guard when ha_pin is set: one PIN opens the buttons for ha_pin_minutes.
        tile.setOnClickListener(v -> pinPad.guard(() -> {
            if (SETTINGS_ID.equals(it.entityId)) openSettings();
            else if (it.group) openPanel();
            else if (hasPosition(it)) openBlind(it);
            else act(it);
        }));
        tile.setOnKeyListener(this::tileKey);
        tile.setOnFocusChangeListener((v, f) -> {
            if (f) lastTile = Math.max(0, tiles.indexOf(it));
            v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(140).start();
            bindTile(it);
        });
    }

    private String statusOf(Item it) {
        if (SETTINGS_ID.equals(it.entityId)) return "الرمز والأجهزة والسكون";
        if (it.group) {
            String s = groupSensor == null || "unavailable".equals(groupSensor.state) ? ""
                    : number(groupSensor.state) + (groupSensor.unit.isEmpty() ? "" : " " + groupSensor.unit);
            Item sw = groupSwitch();
            String power = sw == null ? "" : (sw.on ? "يعمل" : "مطفأ");
            // Isolated, so "44.7 W" keeps its order next to Arabic text.
            if (!s.isEmpty()) return power.isEmpty() ? s : "\u2066" + s + "\u2069  ·  " + power;
            return power.isEmpty() ? "تحكم" : power;
        }
        if ("unavailable".equals(it.state) || it.state.isEmpty()) return "غير متاح";
        switch (it.domain) {
            case "sensor": return number(it.state) + (it.unit.isEmpty() ? "" : " " + it.unit);
            case "button": case "input_button": case "script": case "scene": return "تشغيل";
            case "cover":
                if ("opening".equals(it.state)) return "تُفتح…";
                if ("closing".equals(it.state)) return "تُغلق…";
                if (it.position == 0 || "closed".equals(it.state)) return "مغلقة";
                if (it.position > 0 && it.position < 100) return "مفتوحة  \u2066" + it.position + "%\u2069";
                return "open".equals(it.state) ? "مفتوحة" : it.state;
            case "vacuum": return "docked".equals(it.state) ? "في القاعدة" : it.state;
            default: return "on".equals(it.state) ? "يعمل" : "off".equals(it.state) ? "مطفأ" : it.state;
        }
    }

    /** The group's on/off switch, if it has one: its state colours the group tile. */
    private Item groupSwitch() {
        for (Item g : groupItems) if ("switch".equals(g.domain)) return g;
        return null;
    }

    private void bindTile(Item it) {
        if (it.tile == null) return;
        boolean focused = it.tile.isFocused();
        Item sw = it.group ? groupSwitch() : null;
        boolean on = it.group ? (sw != null && sw.on) : it.on;
        it.name.setText(it.label);
        it.status.setText(statusOf(it));
        it.name.setTextColor(focused ? 0xFF111114 : TEXT);
        it.status.setTextColor(focused ? 0x99111114 : on ? WARM : TEXT_3);
        it.icon.setImageTintList(ColorStateList.valueOf(on ? 0xFF1A1206 : focused ? 0xFF111114 : TEXT_2));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(on ? WARM : focused ? 0x14000000 : 0x1AFFFFFF);
        it.iconWrap.setBackground(circle);
        it.tile.setBackground(focused ? shape(22, 0xFAFFFFFF, 0xF0F2F2F5, 0)
                : on ? shape(22, 0x33FFC56B, 0x14FFC56B, 0x4DFFC56B)
                : shape(22, CARD_TOP, CARD_BOTTOM, CARD_STROKE));
    }

    private static String number(String s) {
        try {
            double v = Double.parseDouble(s);
            return v == Math.rint(v) || Math.abs(v) >= 100 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private void act(Item it) {
        Log.i(TAG, "press " + it.entityId);
        String service;
        switch (it.domain) {
            case "button": case "input_button": service = "press"; break;
            case "script": case "scene": service = "turn_on"; break;
            case "vacuum": service = "docked".equals(it.state) ? "start" : "return_to_base"; break;
            default: service = "toggle";
        }
        // Show the change at once; the state read a moment later corrects it if Home Assistant disagrees.
        if ("toggle".equals(service)) {
            it.on = !it.on;
            if ("on".equals(it.state) || "off".equals(it.state)) it.state = it.on ? "on" : "off";
            else if ("open".equals(it.state) || "closed".equals(it.state)) it.state = it.on ? "open" : "closed";
        }
        for (Item t : tiles) bindTile(t);
        if (panel != null && panel.isShowing()) bindPanel();
        View v = it.tile;
        if (v != null) {
            float rest = tiles.contains(it) ? 1.04f : 1f;
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction(() ->
                    v.animate().scaleX(v.isFocused() ? rest : 1f).scaleY(v.isFocused() ? rest : 1f).setDuration(140).start()).start();
        }
        io.execute(() -> {
            try {
                String token = token();
                if (token != null) {
                    http("POST", haUrl + "/api/services/" + it.domain + "/" + service, token,
                            "{\"entity_id\":\"" + it.entityId + "\"}");
                    Log.i(TAG, it.domain + "." + service + " " + it.entityId + " ok");
                } else {
                    Log.w(TAG, "no token");
                }
            } catch (Exception e) {
                Log.w(TAG, "service " + it.entityId + ": " + e.getMessage());
            }
            main.postDelayed(this::refreshHomeAssistant, 1200);
        });
    }

    // ================================================================ Home Assistant: blind position

    private static final int COVER_SET_POSITION = 4;
    private PopupWindow blindPopup;
    private Item blindItem;
    private BlindView blindView;
    private TextView blindPct, blindState;
    private View sliderFill, sliderRest, slider;
    private int blindPending = -1;
    private final Runnable blindPoll = new Runnable() {
        @Override public void run() {
            if (blindPopup == null) return;
            refreshHomeAssistant();
            main.postDelayed(this, 2000);
        }
    };

    private static boolean hasPosition(Item it) {
        return "cover".equals(it.domain) && (it.features & COVER_SET_POSITION) != 0 && it.position >= 0;
    }

    /**
     * Position picker for a blind: a drawing of the window, the percentage, a slider (Left/Right 5% at a time, OK
     * applies) and presets that apply at once. Reads the real position every 2 s while open, so the blind can be
     * watched moving.
     */
    private void openBlind(Item it) {
        blindItem = it;
        blindPending = it.position;
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(0xB3000000);
        LinearLayout box = row(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(32), dp(28), dp(36), dp(28));
        box.setBackground(shape(30, 0xF2262629, 0xF5151517, 0x33FFFFFF));

        blindView = new BlindView(activity);
        box.addView(blindView, new LinearLayout.LayoutParams(dp(150), dp(196)));

        LinearLayout right = column(Gravity.LEFT);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(430), -2);
        rLp.setMarginStart(dp(32));
        box.addView(right, rLp);

        LinearLayout head = row(Gravity.BOTTOM);
        blindPct = text(56, light, TEXT);
        blindPct.setLetterSpacing(-0.03f);
        head.addView(blindPct, wrap());
        LinearLayout names = column(Gravity.LEFT);
        TextView name = text(20, medium, TEXT);
        name.setText(it.label);
        blindState = text(14, regular, TEXT_3);
        names.addView(name, wrap());
        LinearLayout.LayoutParams stLp = wrap();
        stLp.topMargin = dp(4);
        names.addView(blindState, stLp);
        LinearLayout.LayoutParams nLp = wrap();
        nLp.setMarginStart(dp(18));
        nLp.bottomMargin = dp(10);
        head.addView(names, nLp);
        right.addView(head, wrap());

        // Slider: a focusable track; the fill shows the value being chosen.
        FrameLayout track = new FrameLayout(activity);
        track.setFocusable(true);
        track.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout bar = row(Gravity.CENTER_VERTICAL);
        bar.setBackground(shape(10, 0x26FFFFFF, 0x1AFFFFFF, 0));
        sliderFill = new View(activity);
        sliderFill.setBackground(shape(10, 0xFFF7CAC9, 0xFFC9B6E4, 0));
        sliderRest = new View(activity);
        bar.addView(sliderFill, new LinearLayout.LayoutParams(0, -1, 1));
        bar.addView(sliderRest, new LinearLayout.LayoutParams(0, -1, 1));
        track.addView(bar, new FrameLayout.LayoutParams(-1, dp(20), Gravity.CENTER_VERTICAL));
        track.setOnFocusChangeListener((v, f) -> v.setBackground(f ? shape(16, 0x1FFFFFFF, 0x1FFFFFFF, 0xCCF7CAC9) : null));
        track.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 5 : -5;
                blindPending = Math.max(0, Math.min(100, (blindPending < 0 ? 0 : blindPending) + step));
                bindBlind(true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                setBlind(blindPending);
                return true;
            }
            return false;
        });
        slider = track;
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, dp(40));
        tLp.topMargin = dp(18);
        right.addView(track, tLp);

        // Presets: closed .. open, then stop.
        LinearLayout presets = row(Gravity.CENTER_VERTICAL);
        int[] values = {0, 25, 50, 75, 100, -1};
        for (int v : values) {
            TextView chip = text(15, medium, TEXT);
            chip.setGravity(Gravity.CENTER);
            chip.setText(v == -1 ? "إيقاف" : v == 0 ? "إغلاق" : v == 100 ? "فتح" : v + "%");
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setBackground(shape(18, 0x1FFFFFFF, 0x12FFFFFF, 0x26FFFFFF));
            chip.setOnFocusChangeListener((view, f) -> {
                ((TextView) view).setTextColor(f ? 0xFF1C1626 : TEXT);
                view.setBackground(f ? shape(18, 0xFFF7CAC9, 0xFFC9B6E4, 0) : shape(18, 0x1FFFFFFF, 0x12FFFFFF, 0x26FFFFFF));
            });
            chip.setOnClickListener(view -> {
                if (v == -1) stopBlind();
                else { blindPending = v; setBlind(v); }
            });
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(40), 1);
            cLp.setMargins(dp(4), 0, dp(4), 0);
            presets.addView(chip, cLp);
        }
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, -2);
        pLp.topMargin = dp(16);
        right.addView(presets, pLp);

        TextView hint = text(12, regular, TEXT_4);
        hint.setText("◀ ▶ لاختيار النسبة  ·  OK للتطبيق  ·  رجوع للإغلاق");
        LinearLayout.LayoutParams hLp = wrap();
        hLp.topMargin = dp(16);
        right.addView(hint, hLp);

        scrim.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        blindPopup = new PopupWindow(scrim, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        blindPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        blindPopup.setOnDismissListener(() -> {
            blindPopup = null;
            blindItem = null;
            main.removeCallbacks(blindPoll);
            focusFromBelow();
        });
        bindBlind(true);
        blindPopup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        track.requestFocus();
        main.postDelayed(blindPoll, 2000);
    }

    /** Draws the panel; pending true shows the value being chosen, false follows the blind's real position. */
    private void bindBlind(boolean pending) {
        Item it = blindItem;
        if (it == null || blindPct == null) return;
        if (!pending && !slider.isFocused()) blindPending = it.position;
        int show = blindPending >= 0 ? blindPending : Math.max(0, it.position);
        blindPct.setText(show + "%");
        blindState.setText(statusOf(it));
        blindView.setPosition(Math.max(0, it.position));
        blindView.setTarget(show);
        ((LinearLayout.LayoutParams) sliderFill.getLayoutParams()).weight = Math.max(0.0001f, show);
        ((LinearLayout.LayoutParams) sliderRest.getLayoutParams()).weight = Math.max(0.0001f, 100 - show);
        sliderFill.requestLayout();
        sliderFill.setVisibility(show == 0 ? View.INVISIBLE : View.VISIBLE);
    }

    private void setBlind(int position) {
        Item it = blindItem;
        if (it == null) return;
        Log.i(TAG, "cover " + it.entityId + " -> " + position);
        coverService(it, "set_cover_position", ",\"position\":" + position);
    }

    private void stopBlind() {
        Item it = blindItem;
        if (it == null) return;
        coverService(it, "stop_cover", "");
    }

    private void coverService(Item it, String service, String extra) {
        io.execute(() -> {
            try {
                String token = token();
                if (token != null) {
                    http("POST", haUrl + "/api/services/cover/" + service, token,
                            "{\"entity_id\":\"" + it.entityId + "\"" + extra + "}");
                    Log.i(TAG, "cover." + service + " " + it.entityId + " ok");
                }
            } catch (Exception e) {
                Log.w(TAG, "cover." + service + " " + it.entityId + ": " + e.getMessage());
            }
            main.postDelayed(this::refreshHomeAssistant, 800);
        });
    }

    // ================================================================ home settings

    private PopupWindow settingsPopup;
    private static final int[] SAVER_MIN = {5, 10, 15, 30, 60, -1};
    private static final int[] SLEEP_MIN = {30, 60, 120, 240, -1};

    /** A glass dialog with a title and a vertical list; returns the list to fill. */
    private LinearLayout dialog(String titleText, String hintText, int widthDp, PopupWindow.OnDismissListener onDismiss) {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(0xB3000000);
        LinearLayout box = column(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(18));
        box.setBackground(shape(28, 0xF2262629, 0xF5151517, 0x33FFFFFF));
        TextView t = text(21, medium, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setText(titleText);
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (hintText != null) {
            TextView h = text(12, regular, TEXT_3);
            h.setGravity(Gravity.CENTER);
            h.setText(hintText);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
            hl.topMargin = dp(6);
            box.addView(h, hl);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = column(Gravity.CENTER_HORIZONTAL);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(12);
        box.addView(scroll, sl);
        int maxH = activity.getResources().getDisplayMetrics().heightPixels - dp(80);
        box.setLayoutParams(new FrameLayout.LayoutParams(dp(widthDp), -2, Gravity.CENTER));
        scrim.addView(box);
        box.post(() -> {
            if (box.getHeight() > maxH) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
                lp.height = maxH;
                box.setLayoutParams(lp);
            }
        });
        PopupWindow pw = new PopupWindow(scrim, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOnDismissListener(onDismiss);
        pw.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        list.setTag(pw);
        return list;
    }

    /** One focusable row: icon, label, value on the right. */
    private LinearLayout settingsRow(LinearLayout list, int icon, String label, String value, View.OnClickListener click) {
        LinearLayout r = row(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), 0, dp(18), 0);
        r.setFocusable(true);
        r.setClickable(true);
        ImageView iv = new ImageView(activity);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(TEXT_2));
        r.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView name = text(16, medium, TEXT);
        name.setText(label);
        name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);   // next to the icon, whatever the script
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1);
        nl.setMarginStart(dp(14));
        nl.setMarginEnd(dp(16));
        r.addView(name, nl);
        TextView val = text(14, regular, TEXT_3);
        val.setText(value);
        val.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        r.addView(val, wrap());
        r.setBackground(shape(16, 0x14FFFFFF, 0x0AFFFFFF, 0));
        r.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? shape(16, 0xFAFFFFFF, 0xF0F2F2F5, 0) : shape(16, 0x14FFFFFF, 0x0AFFFFFF, 0));
            name.setTextColor(f ? 0xFF111114 : TEXT);
            val.setTextColor(f ? 0x99111114 : TEXT_3);
            iv.setImageTintList(ColorStateList.valueOf(f ? 0xFF111114 : TEXT_2));
        });
        r.setOnClickListener(click);
        r.setOnKeyListener((v, keyCode, e) -> keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.topMargin = dp(6);
        list.addView(r, lp);
        return r;
    }

    private static String minutesText(int min) {
        if (min < 0) return "أبداً";
        if (min < 60) return min + " دقيقة";
        return min / 60 == 1 ? "ساعة" : min / 60 + " ساعات";
    }

    private int currentSaverMin() {
        int ms = android.provider.Settings.System.getInt(activity.getContentResolver(),
                android.provider.Settings.System.SCREEN_OFF_TIMEOUT, Integer.MAX_VALUE);
        return ms >= 24 * 3600_000 ? -1 : ms / 60_000;
    }

    private int currentSleepMin() {
        int ms = android.provider.Settings.Secure.getInt(activity.getContentResolver(), "sleep_timeout", Integer.MAX_VALUE);
        return ms <= 0 || ms >= 24 * 3600_000 ? -1 : ms / 60_000;
    }

    private static int nextOf(int[] values, int current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return values[(i + 1) % values.length];
        return values[0];
    }

    private void openSettings() {
        LinearLayout list = dialog("إعدادات المنزل", null, 520, () -> { settingsPopup = null; focusFromBelow(); });
        settingsPopup = (PopupWindow) list.getTag();
        View first = settingsRow(list, R.drawable.ic_lock, "تغيير رمز الأهل", "", v -> {
            settingsPopup.dismiss();
            pinPad.changePin(null);
        });
        settingsRow(list, R.drawable.ic_ha_bulb, "الأجهزة الظاهرة", tileSpecs().length + " عنصر", v -> {
            settingsPopup.dismiss();
            openDevices();
        });
        final TextView[] saverVal = new TextView[1], sleepVal = new TextView[1];
        LinearLayout saver = settingsRow(list, R.drawable.ic_picture, "شاشة التوقف بعد", minutesText(currentSaverMin()), null);
        saverVal[0] = (TextView) saver.getChildAt(2);
        saver.setOnClickListener(v -> {
            int next = nextOf(SAVER_MIN, currentSaverMin());
            try {
                android.provider.Settings.System.putInt(activity.getContentResolver(),
                        android.provider.Settings.System.SCREEN_OFF_TIMEOUT, next < 0 ? Integer.MAX_VALUE : next * 60_000);
                saverVal[0].setText(minutesText(currentSaverMin()));
            } catch (SecurityException e) {
                saverVal[0].setText("غير مسموح");
            }
        });
        LinearLayout sleep = settingsRow(list, R.drawable.ic_ha_power, "السكون بعد شاشة التوقف", minutesText(currentSleepMin()), null);
        sleepVal[0] = (TextView) sleep.getChildAt(2);
        sleep.setOnClickListener(v -> {
            int next = nextOf(SLEEP_MIN, currentSleepMin());
            try {
                android.provider.Settings.Secure.putInt(activity.getContentResolver(), "sleep_timeout",
                        next < 0 ? Integer.MAX_VALUE : next * 60_000);
                sleepVal[0].setText(minutesText(currentSleepMin()));
            } catch (SecurityException e) {
                sleepVal[0].setText("غير مسموح");
            }
        });
        first.requestFocus();
    }

    /** One row of the device chooser. */
    private static final class Choice {
        String spec, id, name, domain;
        boolean checked;
        int order;     // order in which it was ticked, for new ones
    }

    private void openDevices() {
        LinearLayout list = dialog("الأجهزة الظاهرة", "OK لإظهار أو إخفاء  ·  رجوع للحفظ", 620, null);
        PopupWindow pw = (PopupWindow) list.getTag();
        TextView loadingText = text(15, regular, TEXT_3);
        loadingText.setText("جارٍ التحميل…");
        list.addView(loadingText, wrap());
        List<Choice> choices = new ArrayList<>();
        final boolean[] changed = {false}, reset = {false};
        pw.setOnDismissListener(() -> {
            if (reset[0]) {
                prefs.edit().remove("tiles_override").apply();
            } else if (changed[0]) {
                List<Choice> keep = new ArrayList<>();
                for (Choice c : choices) if (c.checked && c.order == 0) keep.add(c);   // existing, in their order
                List<Choice> added = new ArrayList<>();
                for (Choice c : choices) if (c.checked && c.order > 0) added.add(c);
                java.util.Collections.sort(added, (a, b) -> a.order - b.order);
                keep.addAll(added);
                StringBuilder sb = new StringBuilder();
                for (Choice c : keep) sb.append(sb.length() > 0 ? "\n" : "").append(c.spec);
                prefs.edit().putString("tiles_override", sb.toString()).apply();
            }
            focusFromBelow();
            refreshHomeAssistant();
        });
        io.execute(() -> {
            try {
                String token = token();
                if (token == null) return;
                JSONArray states = new JSONArray(http("GET", haUrl + "/api/states", token, null));
                // Current tiles first, as they are.
                java.util.Set<String> current = new java.util.HashSet<>();
                for (String spec : tileSpecs()) {
                    String id = spec.split("\\|", 2)[0].trim();
                    if (id.isEmpty()) continue;
                    Choice c = new Choice();
                    c.spec = spec;
                    c.id = id;
                    c.checked = true;
                    c.domain = id.contains(".") ? id.substring(0, id.indexOf('.')) : "group";
                    c.name = spec.contains("|") ? spec.substring(spec.indexOf('|') + 1).trim() : id;
                    if (id.equals("@group")) c.name = groupLabel;
                    current.add(id);
                    choices.add(c);
                }
                // Then everything else that can be a tile, lights and blinds first.
                List<Choice> rest = new ArrayList<>();
                for (int i = 0; i < states.length(); i++) {
                    JSONObject e = states.getJSONObject(i);
                    String id = e.getString("entity_id");
                    String name = friendly(e);
                    String low = name.toLowerCase(Locale.ROOT);
                    if (!useful(e) || current.contains(id) || low.contains("hidden") || low.contains("backlight")
                            || low.contains("child lock") || low.contains("identify")) continue;
                    Choice c = new Choice();
                    c.id = id;
                    c.spec = id + "|";
                    c.name = name;
                    c.domain = id.substring(0, id.indexOf('.'));
                    rest.add(c);
                }
                // A current spec given by name or pattern shows its friendly name where it resolves to one entity.
                for (Choice c : choices) {
                    if (c.name.equals(c.id)) {
                        for (int i = 0; i < states.length(); i++) {
                            JSONObject e = states.getJSONObject(i);
                            if (e.getString("entity_id").equals(c.id)) c.name = friendly(e);
                        }
                    }
                }
                String rank = "light cover switch fan climate water_heater input_boolean script scene button input_button automation vacuum sensor";
                java.util.Collections.sort(rest, (a, b) -> {
                    int ra = rank.indexOf(a.domain), rb = rank.indexOf(b.domain);
                    return ra != rb ? ra - rb : a.name.compareTo(b.name);
                });
                choices.addAll(rest);
                main.post(() -> {
                    list.removeAllViews();
                    settingsRow(list, R.drawable.ic_swap_vert, "استعادة الافتراضي", "من الإعدادات", v -> {
                        reset[0] = true;
                        pw.dismiss();
                    });
                    final int[] counter = {0};
                    View firstChoice = null;
                    for (Choice c : choices) {
                        Item probe = new Item();
                        probe.entityId = c.id;
                        probe.domain = c.domain;
                        probe.label = c.name;
                        probe.group = c.id.equals("@group");
                        LinearLayout r = settingsRow(list, iconFor(probe), c.name, c.checked ? "✓ ظاهر" : "مخفي", null);
                        TextView val = (TextView) r.getChildAt(2);
                        r.setOnClickListener(v -> {
                            c.checked = !c.checked;
                            if (c.checked && !current.contains(c.id)) c.order = ++counter[0];
                            if (!c.checked) c.order = current.contains(c.id) ? 0 : c.order;
                            changed[0] = true;
                            val.setText(c.checked ? "✓ ظاهر" : "مخفي");
                        });
                        if (firstChoice == null) firstChoice = r;
                    }
                    if (firstChoice != null) firstChoice.requestFocus();
                });
            } catch (Exception e) {
                Log.w(TAG, "devices: " + e);
                main.post(() -> loadingText.setText("تعذّر التحميل من Home Assistant"));
            }
        });
    }

    // ================================================================ Home Assistant: group panel

    private void openPanel() {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(0xB3000000);
        LinearLayout box = column(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        box.setBackground(shape(28, 0xF2262629, 0xF5151517, 0x33FFFFFF));
        panelTitle = text(20, medium, TEXT);
        panelTitle.setGravity(Gravity.CENTER);
        box.addView(panelTitle, new LinearLayout.LayoutParams(-1, -2));
        panelRows = column(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
        rLp.topMargin = dp(10);
        box.addView(panelRows, rLp);
        for (Item it : groupItems) {
            LinearLayout r = row(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(16), 0, dp(18), 0);
            boolean actionable = !"sensor".equals(it.domain);
            r.setFocusable(actionable);
            r.setClickable(actionable);
            FrameLayout wrap = new FrameLayout(activity);
            ImageView icon = new ImageView(activity);
            icon.setImageResource(iconFor(it));
            wrap.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            r.addView(wrap, new LinearLayout.LayoutParams(dp(32), dp(32)));
            it.name = text(15, medium, TEXT);
            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0, -2, 1);
            nLp.setMarginStart(dp(12));
            r.addView(it.name, nLp);
            it.status = text(14, regular, TEXT_3);
            r.addView(it.status, wrap());
            it.tile = r;
            it.icon = icon;
            it.iconWrap = wrap;
            if (actionable) r.setOnClickListener(v -> act(it));
            r.setOnFocusChangeListener((v, f) -> bindPanelRow(it));
            r.setOnKeyListener((v, keyCode, e) -> keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
            lp.topMargin = dp(4);
            panelRows.addView(r, lp);
        }
        scrim.addView(box, new FrameLayout.LayoutParams(dp(500), -2, Gravity.CENTER));
        panel = new PopupWindow(scrim, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        panel.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        panel.setOnDismissListener(() -> {
            for (Item it : groupItems) it.tile = null;
            panel = null;
            panelTitle = null;
            focusFromBelow();
        });
        bindPanel();
        panel.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        for (int i = 0; i < panelRows.getChildCount(); i++) {
            if (panelRows.getChildAt(i).isFocusable()) { panelRows.getChildAt(i).requestFocus(); break; }
        }
        refreshHomeAssistant();
    }

    private void bindPanel() {
        if (panelTitle == null) return;
        String s = groupSensor == null || "unavailable".equals(groupSensor.state) ? ""
                : "   ·   \u2066" + number(groupSensor.state) + (groupSensor.unit.isEmpty() ? "" : " " + groupSensor.unit) + "\u2069";
        panelTitle.setText(groupLabel + s);
        for (Item it : groupItems) bindPanelRow(it);
        for (Item t : tiles) if (t.group) bindTile(t);
    }

    private void bindPanelRow(Item it) {
        if (it.tile == null) return;
        boolean focused = it.tile.isFocused();
        it.name.setText(it.label);
        it.status.setText(statusOf(it));
        it.name.setTextColor(focused ? 0xFF111114 : TEXT);
        it.status.setTextColor(focused ? 0x99111114 : it.on ? WARM : TEXT_3);
        it.icon.setImageTintList(ColorStateList.valueOf(it.on ? 0xFF1A1206 : focused ? 0xFF111114 : TEXT_2));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(it.on ? WARM : focused ? 0x14000000 : 0x1AFFFFFF);
        it.iconWrap.setBackground(circle);
        it.tile.setBackground(focused ? shape(16, 0xFAFFFFFF, 0xF0F2F2F5, 0) : shape(16, 0x14FFFFFF, 0x0AFFFFFF, 0));
    }

    // ================================================================ focus

    /** Up from the dock, or Down from the top bar: the tile last used. */
    boolean focusFromBelow() {
        if (tiles.isEmpty() || view.getAlpha() < 0.15f || tileScroll.getVisibility() != View.VISIBLE) return false;
        int i = Math.min(Math.max(0, lastTile), tiles.size() - 1);
        tiles.get(i).tile.requestFocus();
        return true;
    }

    private boolean tileKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int i = -1;
        for (int k = 0; k < tiles.size(); k++) if (tiles.get(k).tile == v) i = k;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: host.focusTopBarFromDashboard(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: host.focusDockFromDashboard(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (i > 0) tiles.get(i - 1).tile.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (i >= 0 && i < tiles.size() - 1) tiles.get(i + 1).tile.requestFocus();
                return true;
            default: return false;
        }
    }

    // ================================================================ http

    private static String http(String method, String url, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            c.disconnect();
            throw new java.io.IOException("HTTP " + code + " " + method + " " + url.replaceAll("\\?.*", ""));
        }
        try (InputStream in = c.getInputStream()) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
