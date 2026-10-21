package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Answers a link the firmware sends for a remote button whose app is disabled, and opens another app instead.
 * The Mi Box 4 firmware turns its Netflix button into nflx://www.netflix.com/; with Netflix disabled nothing
 * else claims that link, so this (declared only in the mibox4 manifest) gets it and starts link_redirect_app.
 * No UI: it starts the app and finishes.
 */
public class RedirectActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String pkg = getString(R.string.link_redirect_app);
        if (!pkg.isEmpty()) {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(launch);
                } catch (RuntimeException e) {
                    Log.w("TvRedirect", "start " + pkg + ": " + e);
                }
            }
        }
        finish();
    }
}
