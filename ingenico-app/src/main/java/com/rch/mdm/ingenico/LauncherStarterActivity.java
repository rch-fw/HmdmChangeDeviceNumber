package com.rch.mdm.ingenico;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Invisible activity which starts the MDM launcher and finishes at once.
 *
 * <p>MainActivity brings itself back to the front once the launcher activity is up, but then
 * it's in the background, and Android 10+ lets a background app start an activity only within
 * 10 seconds after one of its activities was started or finished. Starting the launcher from
 * this short-lived activity opens that window right before MainActivity needs it.</p>
 */
public class LauncherStarterActivity extends Activity {

    static final String EXTRA_LAUNCHER_INTENT = "launcherIntent";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent launcher = getIntent().getParcelableExtra(EXTRA_LAUNCHER_INTENT);
        if (launcher != null) {
            try {
                startActivity(launcher);
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(MainActivity.TAG, "FAILED to start the MDM launcher", e);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
