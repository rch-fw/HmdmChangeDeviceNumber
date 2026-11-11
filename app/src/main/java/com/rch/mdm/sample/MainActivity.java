package com.rch.mdm.sample;

import static android.view.View.VISIBLE;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.hmdm.HeadwindMDM;
import com.hmdm.MDMError;
import com.rch.mdm.MdmConfig;
import com.rch.mdm.MdmException;
import com.rch.mdm.MdmService;

/**
 * Sample app for {@code mdmlib}.
 *
 * <p>The MDM server URL and the device numbers are hard-coded (same values as the
 * original {@code change_dev_number.py}). Only the username and password are asked
 * for at runtime, through a dialog.</p>
 */
public class MainActivity extends AppCompatActivity implements HeadwindMDM.EventHandler{

    private static final String TAG = "MooneySample";

    // --- Hard-coded configuration (from change_dev_number.py) ---
   

    private TextView logView;
    private ProgressBar progress;
    private Button changeButton;

    private Button getButton;

    private Button rebootButton;

    /** Created once we know the credentials; reused for the lifetime of the activity. */
    private MdmService mdmService;


    HeadwindMDM headwindMDM;

    TextView deviceInfo;

    boolean hmdmConnected =false;

    /** Set by startLauncher(): come back to the foreground once the launcher activity is up. */
    private boolean returnFromLauncher = false;

    // "Start MDM launcher" is shown only while the launcher is not running:
    // stopped (force-stopped or never run), checked before connecting because binding to its
    // service clears the stopped state, or not configured (never started its main activity)
    private Button startLauncherButton;
    /** Whether startLauncher() brings this app back to the front (saved in the preferences). */
    private CheckBox returnFromLauncherCheck;
    private static final String PREFS = "sample";
    private static final String PREF_RETURN_FROM_LAUNCHER = "returnFromLauncher";
    private boolean launcherStopped = false;
    private boolean launcherNotConfigured = false;
    private boolean launcherStarted = false;

    /** False until the launcher startup update is over (startup gate). */
    boolean startupReady = false;

    private View startupPanel;
    private TextView startupStatus;
    private ProgressBar startupProgress;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceInfo = findViewById(R.id.deviceInfo);

        headwindMDM= HeadwindMDM.getInstance();
        // Before connect(): the key is also used to query the privileged configuration
        if (!BuildConfig.HMDM_API_KEY.isEmpty()) {
            headwindMDM.setApiKey(BuildConfig.HMDM_API_KEY);
        }

        // Before connect(): binding to the launcher's service takes it out of the stopped state
        launcherStopped = isLauncherStopped();

        if (!headwindMDM.isConnected()) {
            if (!headwindMDM.connect(this, MainActivity.this)) {
            }
        }

        startupPanel = findViewById(R.id.startupPanel);
        startupStatus = findViewById(R.id.startupStatus);
        startupProgress = findViewById(R.id.startupProgress);



        logView = findViewById(R.id.log);
        progress = findViewById(R.id.progress);
        changeButton = findViewById(R.id.changeButton);
        changeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                askCredentialsThenRun();
            }
        });

        getButton = findViewById(R.id.getButton);
        getButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                headwindMDM.disconnect(MainActivity.this);
                headwindMDM.connect(MainActivity.this, MainActivity.this);
            }
        });

        startLauncherButton = findViewById(R.id.startLauncherButton);
        startLauncherButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startLauncher();
            }
        });
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        returnFromLauncherCheck = findViewById(R.id.returnFromLauncherCheck);
        returnFromLauncherCheck.setChecked(prefs.getBoolean(PREF_RETURN_FROM_LAUNCHER, true));
        returnFromLauncherCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.edit().putBoolean(PREF_RETURN_FROM_LAUNCHER, checked).apply();
            }
        });
        updateStartLauncherButton();

        rebootButton = findViewById(R.id.rebootButton);
        rebootButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmReboot();
            }
        });

        // Last: onReady() may be called right away and uses the views above
        awaitStartupUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back here without onStop(): the launcher didn't start
        returnFromLauncher = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (returnFromLauncher) {
            // onStop() comes after the launcher activity's onResume(), where it has already
            // started its startup work (enrollment, configuration update), which goes on in
            // the background. Bring this activity back to the front: allowed from the
            // background because LauncherStarterActivity has just finished.
            returnFromLauncher = false;
            if (returnFromLauncherCheck.isChecked()) {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                log("Back to the foreground after starting the launcher");
            }
            launcherStarted = true;
            updateStartLauncherButton();
        }
    }

    @Override
    protected void onDestroy() {
        if (mdmService != null) {
            mdmService.shutdown();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------

    /**
     * Startup gate. After boot the launcher installs the app updates, and installing an app
     * kills it. So until onReady() the app must not start anything which must not be
     * interrupted (here: the buttons stay hidden).
     */
    private void awaitStartupUpdate() {
        headwindMDM.awaitStartupUpdate(new HeadwindMDM.StartupGateListener() {
            @Override public void onWaiting() {
                log("Startup gate: the launcher is updating apps, waiting");
                startupStatus.setText("Waiting for the app updates...");
            }

            @Override public void onAppRemoving(String pkg, String name) {
                log("Startup gate: removing " + pkg);
                startupStatus.setText("Removing " + name);
            }

            @Override public void onAppDownloading(String pkg, String name) {
                log("Startup gate: downloading " + pkg);
                startupStatus.setText("Downloading " + name);
            }

            @Override public void onAppInstalling(String pkg, String name) {
                log("Startup gate: installing " + pkg);
                startupStatus.setText("Installing " + name);
            }

            @Override public void onDownloadProgress(int progress, long total, long current) {
                startupProgress.setIndeterminate(false);
                startupProgress.setProgress(progress);
            }

            @Override public void onReady(int reason) {
                switch (reason) {
                    case HeadwindMDM.STARTUP_READY:
                        log("Startup gate: open, startup update over");
                        break;
                    case HeadwindMDM.STARTUP_TIMEOUT:
                        log("Startup gate: open, no answer from the launcher (timeout)");
                        break;
                    case HeadwindMDM.STARTUP_NO_MDM:
                        log("Startup gate: open, MDM agent not available");
                        break;
                }
                startupReady = true;
                startupPanel.setVisibility(View.GONE);
                updateButtons();
            }
        });
    }

    private void updateButtons() {
        // These buttons also need the MDM agent
        int visibility = startupReady && hmdmConnected ? VISIBLE : View.GONE;
        changeButton.setVisibility(visibility);
        getButton.setVisibility(visibility);
        rebootButton.setVisibility(visibility);
    }

    // ------------------------------------------------------------------

    /** MDM launcher packages, in the order the hmdm library tries them: standard, Ingenico flavor. */
    private static final String[] LAUNCHER_PACKAGES = {
            "com.hmdm.launcher", "com.hmdm.launcher.ingenico" };

    /**
     * Starts the MDM launcher (whichever of {@link #LAUNCHER_PACKAGES} is installed) with an
     * explicit intent to one of its activities. Starting a component explicitly takes a
     * force-stopped app out of the stopped state (broadcasts are not delivered to a stopped
     * app), so the launcher restarts its services.
     */
    private void startLauncher() {
        for (String pkg : LAUNCHER_PACKAGES) {
            Intent intent = launcherIntent(pkg);
            if (intent == null) {
                continue;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            returnFromLauncher = true;
            startActivity(new Intent(this, LauncherStarterActivity.class)
                    .putExtra(LauncherStarterActivity.EXTRA_LAUNCHER_INTENT, intent));
            log("Starting " + intent.getComponent());
            return;
        }
        log("FAILED to start the MDM launcher: none of " + TextUtils.join(", ", LAUNCHER_PACKAGES)
                + " is installed with an activity to start");
    }

    /** Intent to the main activity of {@code pkg}, or null if not installed or none. */
    /** True if the installed MDM launcher is in the stopped state (force-stopped or never run). */
    private boolean isLauncherStopped() {
        for (String pkg : LAUNCHER_PACKAGES) {
            try {
                ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
                return (info.flags & ApplicationInfo.FLAG_STOPPED) != 0;
            } catch (PackageManager.NameNotFoundException e) {
                // Try the next package
            }
        }
        return false;   // no launcher installed: nothing to start
    }

    private void updateStartLauncherButton() {
        boolean notRunning = launcherStopped || launcherNotConfigured;
        int visibility = notRunning && !launcherStarted ? VISIBLE : View.GONE;
        startLauncherButton.setVisibility(visibility);
        returnFromLauncherCheck.setVisibility(visibility);
    }

    private Intent launcherIntent(String pkg) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) {
            return intent;
        }
        // The launcher may declare only a HOME activity, without the LAUNCHER category
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(pkg);
        List<ResolveInfo> activities = getPackageManager().queryIntentActivities(home, 0);
        if (activities.isEmpty()) {
            return null;
        }
        ResolveInfo info = activities.get(0);
        return home.setClassName(info.activityInfo.packageName, info.activityInfo.name);
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reboot device")
                .setMessage("Reboot the device now? App updates waiting for the next boot are installed at startup.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reboot", (dialog, which) -> reboot())
                .show();
    }

    /**
     * Asks the launcher to reboot through a "reboot" push message. The launcher reboots only
     * if it is device owner; true here means only that the message was accepted (API key ok).
     */
    private void reboot() {
        if (BuildConfig.HMDM_API_KEY.isEmpty()) {
            log("FAILED to request reboot: no API key, set hmdm.apiKey in local.properties");
            return;
        }
        log("Requesting reboot...");
        if (headwindMDM.sendPush("reboot", null)) {
            log("Reboot requested");
        } else {
            log("FAILED to request reboot: MDM agent not connected or wrong API key (error "
                    + headwindMDM.getMdmAgentError() + ")");
        }
    }

    // ------------------------------------------------------------------

    private void askCredentialsThenRun() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_credentials, null);
        final EditText usernameField = dialogView.findViewById(R.id.username);
        final EditText passwordField = dialogView.findViewById(R.id.password);
        final EditText deviceNumberField = dialogView.findViewById(R.id.deviceNumber);
        final EditText groupsField = dialogView.findViewById(R.id.groups);

        new AlertDialog.Builder(this)
                .setTitle("MDM credentials")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String username = usernameField.getText().toString().trim();
                    String password = passwordField.getText().toString();
                    String deviceNumber = deviceNumberField.getText().toString().trim();
                    List<String> groups = parseGroups(groupsField.getText().toString());
                    if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)
                            || (TextUtils.isEmpty(deviceNumber) && groups == null)) {
                        Toast.makeText(this, "Username, password and a new device number or groups are required",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runChangeDevice(username, password,
                            TextUtils.isEmpty(deviceNumber) ? null : deviceNumber, groups);
                })
                .show();
    }

    /** Comma-separated group names; {@code null} (groups unchanged) when the field is blank. */
    private static List<String> parseGroups(String text) {
        if (text.trim().isEmpty()) {
            return null;
        }
        List<String> groups = new ArrayList<>();
        for (String name : text.split(",")) {
            if (!name.trim().isEmpty()) {
                groups.add(name.trim());
            }
        }
        return groups;
    }

    private void runChangeDevice(String username, String password, String deviceNumber, List<String> groups) {
        // A fresh service each run keeps this sample simple (credentials may change
        // between runs). shutdown() the previous one so its thread is released.
        if (mdmService != null) {
            mdmService.shutdown();
        }
        mdmService = new MdmService(new MdmConfig(headwindMDM.getServerUrl(), username, password));

        setBusy(true);
        log("Authenticating as \"" + username + "\" and updating " + headwindMDM.getDeviceId()
                + (deviceNumber != null ? ": number -> " + deviceNumber : "")
                + (groups != null ? ": groups -> " + groups : "") + " ...");

        mdmService.changeDevice(headwindMDM.getDeviceId(), deviceNumber, groups,
                new MdmService.Callback<Long>() {
                    @Override public void onSuccess(Long deviceId) {
                        setBusy(false);
                        log("OK - device id " + deviceId + " updated"
                                + (deviceNumber != null ? "; renamed to " + deviceNumber + " and configUpdated push sent" : "")
                                + (groups != null ? "; groups set to " + groups : "") + ".");
                    }

                    @Override public void onError(@NonNull MdmException e) {
                        setBusy(false);
                        StringBuilder sb = new StringBuilder("FAILED: ").append(e.getMessage());
                        if (e.getStatusCode() > 0) {
                            sb.append("\nHTTP ").append(e.getStatusCode());
                        }
                        if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                            sb.append("\n").append(e.getResponseBody());
                        }
                        log(sb.toString());
                    }
                });
    }

    // ------------------------------------------------------------------

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? VISIBLE : View.GONE);
        changeButton.setEnabled(!busy);
    }

    private void log(String message) {
        String stamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        String line = "[" + stamp + "] " + message + "\n";
        logView.append(line);
        // Also to logcat, to follow the startup gate together with the launcher log
        Log.i(TAG, message);
    }

    @Override
    public void onHeadwindMDMConnected() {
        // Called on the main thread (ServiceConnection.onServiceConnected()), with the
        // configuration and getMdmAgentError() already read from the launcher
        hmdmConnected=true;
        updateButtons();
        int error = headwindMDM.getMdmAgentError();
        launcherNotConfigured = error == MDMError.ERROR_NOT_CONFIGURED;
        updateStartLauncherButton();
        if (launcherNotConfigured) {
            // e.g. the launcher was just installed and isn't enrolled yet
            deviceInfo.setText("MDM launcher not configured yet: enroll it, then press "
                    + "\"Get device number\"");
            log("MDM agent connected, but not configured yet");
            return;
        }
        deviceInfo.setText(
                "Server: " + headwindMDM.getServerHost() + "\n"
                        + "Current device number: " + headwindMDM.getDeviceId()
                        + (error != 0 ? "\nMDM agent error " + error : ""));
    }

    @Override
    public void onHeadwindMDMDisconnected() {

    }

    @Override
    public void onHeadwindMDMConfigChanged() {

    }
}
