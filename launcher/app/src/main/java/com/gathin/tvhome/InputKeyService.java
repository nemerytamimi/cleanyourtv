package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it, others (KTC and Kalley Realtek Google TV remotes) KEYCODE_TV_INPUT; on Google TV
 * the stock launcher handles both, so with the stock launcher disabled the button does nothing. Buttons listed in
 * input_key_keycodes (config.xml or a theme) open the input switcher the same way, and app_keys start an app (a
 * YouTube button that only the stock launcher answered). Every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private int[] inputKeycodes = new int[0];
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private final Map<String, String> appRedirects = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastRedirect;
    private String pendingSource, pendingTarget;
    private final Runnable redirect = () -> {
        String source = pendingSource, target = pendingTarget;
        if (source == null || target == null) return;
        boolean again = SystemClock.elapsedRealtime() - lastRedirect < 4000;
        if (inFront(source)) {
            // Only ever Back/Home over the replaced app itself, never over the target or anything else.
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> { if (inFront(source)) performGlobalAction(GLOBAL_ACTION_HOME); }, 200);
        }
        if (!again) {
            lastRedirect = SystemClock.elapsedRealtime();
            handler.postDelayed(() -> RemoteKeys.launch(this, target), 500);
        }
    };

    private boolean inFront(String pkg) {
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && root.getPackageName() != null && pkg.contentEquals(root.getPackageName());
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // From config.xml or a theme's config.json; theme.sh rebinds this service after a push.
        Config config = Config.load(this);
        inputKeycodes = config.inputKeycodes;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        appRedirects.clear();
        for (String entry : getResources().getStringArray(R.array.app_redirects)) {
            String[] p = entry.split(":", 2);
            if (p.length == 2 && !p[0].trim().isEmpty()) appRedirects.put(p[0].trim(), p[1].trim());
        }
        // The service is declared with no event types (keys only, nothing to deliver). app_redirects needs to see
        // its source apps come to the front, so ask for window changes from those packages only, and only then.
        if (!appRedirects.isEmpty()) {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
                info.packageNames = appRedirects.keySet().toArray(new String[0]);
                setServiceInfo(info);
            }
        }
    }

    private boolean isInputKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_TV || keyCode == KeyEvent.KEYCODE_TV_INPUT) return true;
        for (int code : inputKeycodes) if (keyCode == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // A scancode entry is more specific than any keycode, so it goes first.
        String scanApp = appScancodes.get(event.getScanCode());
        if (scanApp != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, scanApp);
            return true;
        }
        String app = appKeys.get(event.getKeyCode());
        if (app == null && !isInputKey(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            if (app != null) {
                RemoteKeys.launch(this, app);
            } else {
                try {
                    startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // app_redirects: when a remote button's key never reaches us (the firmware consumes it and starts an app
        // itself, as the Mi Box 4 does with TV and Live Channels), the app it starts is the signal. Go Home first,
        // so Back from the target returns there and not to the app we replaced, then open the target.
        if (appRedirects.isEmpty() || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) return;
        String target = appRedirects.get(event.getPackageName().toString());
        if (target == null) return;
        // The replaced app opens several windows in a row (Live Channels: TvActivity, MainActivity, a privacy screen),
        // so act once they have stopped coming: close it with Back so nothing of it is left under the target, go
        // Home, then open the target. If it comes back right after (Back from the target), only close it again.
        pendingSource = event.getPackageName().toString();
        pendingTarget = target;
        handler.removeCallbacks(redirect);
        handler.postDelayed(redirect, 500);
    }

    @Override
    public void onInterrupt() {
    }
}
