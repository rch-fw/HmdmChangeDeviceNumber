package com.rch.mdm.sunmi;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.hmdm.HeadwindMDM;
import com.hmdm.MDMError;
import com.rch.mdm.MdmConfig;
import com.rch.mdm.MdmException;
import com.rch.mdm.MdmService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Sunmi counterpart of the Ingenico Sample App, see docs/sunmi-sample-app.md.
 *
 * On Sunmi terminals the MDM agent (the Headwind MDM launcher, com.hmdm.launcher) is device owner
 * and the HOME app: Android starts it at every boot, and the provisioning procedure has already
 * enrolled it and set up the network. So, unlike the Ingenico app, this app never starts the
 * launcher, never recovers it from the stopped state and never configures WiFi. It only
 * synchronizes with the agent through the startup gate (awaitStartupUpdate): it waits until the
 * launcher has installed the app updates of this boot, because installing an app kills it.
 * Then it is operative.
 */
public class MainActivity extends AppCompatActivity implements HeadwindMDM.EventHandler {

    static final String TAG = "SunmiSample";

    private static final String LAUNCHER_PACKAGE = "com.hmdm.launcher";

    private static final long CONNECT_TIMEOUT_MS = 15 * 1000;
    // The launcher reports only successful configuration updates: stop waiting after this
    private static final long SYNC_TIMEOUT_MS = 90 * 1000;

    private enum State { CONNECTING, SYNCHRONIZING, READY }

    private State state = State.CONNECTING;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HeadwindMDM headwindMDM;

    private boolean syncing;
    /** Device number or groups changed on the server: read the device data again at the next configuration update. */
    private boolean changePending;
    private MdmService mdmService;

    private TextView stepTitle;
    private TextView stepDetail;
    private ProgressBar stepProgress;
    private View readyPanel;
    private TextView deviceInfo;
    private TextView logView;
    private ScrollView logScroll;

    private final Runnable connectTimeout = () -> {
        if (state == State.CONNECTING) {
            log("No answer from the MDM agent within " + CONNECT_TIMEOUT_MS / 1000 + " s");
            ready("MDM agent not available");
        }
    };

    private final Runnable syncTimeout = () -> {
        if (syncing) {
            log("Configuration sync: no answer from the agent within " + SYNC_TIMEOUT_MS / 1000 + " s");
            setSyncing(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        stepTitle = findViewById(R.id.stepTitle);
        stepDetail = findViewById(R.id.stepDetail);
        stepProgress = findViewById(R.id.stepProgress);
        readyPanel = findViewById(R.id.readyPanel);
        deviceInfo = findViewById(R.id.deviceInfo);
        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        findViewById(R.id.getButton).setOnClickListener(v -> getDeviceNumber());
        findViewById(R.id.changeButton).setOnClickListener(v -> askCredentialsThenChange());
        findViewById(R.id.syncButton).setOnClickListener(v -> syncConfig());

        if (!isInstalled(LAUNCHER_PACKAGE)) {
            log("MDM launcher not installed");
            ready("MDM agent not installed");
            return;
        }

        setStep(State.CONNECTING, "Connecting to the MDM agent", null);
        headwindMDM = HeadwindMDM.getInstance();
        if (!headwindMDM.connect(this, this)) {
            log("Can't bind the MDM agent");
            ready("MDM agent not available");
            return;
        }
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (headwindMDM != null) {
            headwindMDM.disconnect(this);
        }
        if (mdmService != null) {
            mdmService.shutdown();
        }
        super.onDestroy();
    }

    // ---- MDM agent ----

    @Override
    public void onHeadwindMDMConnected() {
        // On the main thread, with the configuration and getMdmAgentError() already read
        handler.removeCallbacks(connectTimeout);
        if (state == State.READY) {
            showDeviceInfo();
            log("Device number: " + headwindMDM.getDeviceId());
            return;
        }
        if (state != State.CONNECTING) {
            return;     // reconnection (the launcher restarted, e.g. updating itself)
        }
        if (headwindMDM.getMdmAgentError() == MDMError.ERROR_NOT_CONFIGURED) {
            // The provisioning procedure enrolls the launcher: nothing this app can do. Don't wait
            // at the gate, which stays open until the enrollment (launcher 6.54 or later).
            log("MDM agent not enrolled: check the provisioning procedure");
            ready("MDM agent not enrolled");
            return;
        }
        log("MDM agent running");
        synchronize();
    }

    @Override
    public void onHeadwindMDMDisconnected() {
        log("MDM agent disconnected");
    }

    @Override
    public void onHeadwindMDMConfigChanged() {
        // Broadcast sent by the launcher at the end of each successful configuration update
        if (syncing) {
            log("Configuration sync completed");
            setSyncing(false);
            showDeviceInfo();
        }
        if (changePending && state == State.READY) {
            // The launcher loaded the configuration with the new device number: read it again
            changePending = false;
            log("Configuration updated after the change, reading the device number again");
            getDeviceNumber();
        }
    }

    /**
     * Startup gate: waits until the launcher has installed the app updates of this boot (installing
     * an app kills it). The gate always opens: at the end of the startup update, or at a timeout.
     */
    private void synchronize() {
        setStep(State.SYNCHRONIZING, "Synchronizing with the MDM agent", "Waiting for the app updates...");
        headwindMDM.awaitStartupUpdate(new HeadwindMDM.StartupGateListener() {
            @Override public void onWaiting() {
                log("Startup gate: the agent is updating apps, waiting");
            }

            @Override public void onAppRemoving(String pkg, String name) {
                log("Startup gate: removing " + pkg);
                stepDetail.setText("Removing " + name);
            }

            @Override public void onAppDownloading(String pkg, String name) {
                log("Startup gate: downloading " + pkg);
                stepDetail.setText("Downloading " + name);
                stepProgress.setIndeterminate(true);
            }

            @Override public void onAppInstalling(String pkg, String name) {
                log("Startup gate: installing " + pkg);
                stepDetail.setText("Installing " + name);
                stepProgress.setIndeterminate(true);
            }

            @Override public void onDownloadProgress(int progress, long total, long current) {
                stepProgress.setIndeterminate(false);
                stepProgress.setProgress(progress);
            }

            @Override public void onReady(int reason) {
                switch (reason) {
                    case HeadwindMDM.STARTUP_READY:
                        log("Startup gate: open, startup update over");
                        break;
                    case HeadwindMDM.STARTUP_TIMEOUT:
                        log("Startup gate: open, no answer from the agent (timeout)");
                        break;
                    default:
                        log("Startup gate: open, MDM agent not available");
                        break;
                }
                ready(null);
            }
        });
    }

    /** Operative: the app's normal work starts here. */
    private void ready(String problem) {
        setStep(State.READY, "Ready", problem);
        stepProgress.setVisibility(View.GONE);
        readyPanel.setVisibility(View.VISIBLE);
        int agentButtons = headwindMDM != null && headwindMDM.isConnected() ? View.VISIBLE : View.GONE;
        findViewById(R.id.getButton).setVisibility(agentButtons);
        findViewById(R.id.changeButton).setVisibility(agentButtons);
        findViewById(R.id.syncButton).setVisibility(agentButtons);
        log("Operative");
        showDeviceInfo();
    }

    private void showDeviceInfo() {
        if (headwindMDM == null || !headwindMDM.isConnected()) {
            deviceInfo.setText("MDM agent not connected");
        } else if (headwindMDM.getMdmAgentError() == MDMError.ERROR_NOT_CONFIGURED) {
            deviceInfo.setText("MDM agent not enrolled");
        } else {
            int error = headwindMDM.getMdmAgentError();
            deviceInfo.setText("Server: " + headwindMDM.getServerHost()
                    + "\nDevice number: " + headwindMDM.getDeviceId()
                    + (error != 0 ? "\nMDM agent error " + error : ""));
        }
    }

    /**
     * Get device number: reconnects to the MDM agent, which reads its configuration again
     * (server, device number); onHeadwindMDMConnected() shows it.
     */
    private void getDeviceNumber() {
        log("Reading the device number from the MDM agent");
        headwindMDM.disconnect(this);
        headwindMDM.connect(this, this);
    }

    // ---- Change device number ----

    /** Change device number: asks the MDM server credentials, the new number and/or groups. */
    private void askCredentialsThenChange() {
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
                    changeDevice(username, password, TextUtils.isEmpty(deviceNumber) ? null : deviceNumber, groups);
                })
                .show();
    }

    /** Comma-separated group names; null (groups unchanged) when the field is blank. */
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

    /**
     * Renames the device and/or sets its groups on the MDM server (mdmlib, REST API with the
     * user's credentials). On a rename the server sends the device a configUpdated push: the
     * launcher reloads its configuration with the new number.
     */
    private void changeDevice(String username, String password, String deviceNumber, List<String> groups) {
        // A fresh service each time (the credentials may change); release the previous one
        if (mdmService != null) {
            mdmService.shutdown();
        }
        mdmService = new MdmService(new MdmConfig(headwindMDM.getServerUrl(), username, password));

        setChanging(true);
        // Never log the password
        log("Authenticating as \"" + username + "\" and updating " + headwindMDM.getDeviceId()
                + (deviceNumber != null ? ": number -> " + deviceNumber : "")
                + (groups != null ? ": groups -> " + groups : "") + " ...");

        mdmService.changeDevice(headwindMDM.getDeviceId(), deviceNumber, groups,
                new MdmService.Callback<Long>() {
                    @Override public void onSuccess(Long deviceId) {
                        setChanging(false);
                        log("OK - device id " + deviceId + " updated"
                                + (deviceNumber != null ? "; renamed to " + deviceNumber + " and configUpdated push sent" : "")
                                + (groups != null ? "; groups set to " + groups : "") + ".");
                        if (deviceNumber != null) {
                            changePending = true;
                        }
                    }

                    @Override public void onError(@NonNull MdmException e) {
                        setChanging(false);
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

    private void setChanging(boolean on) {
        findViewById(R.id.changeButton).setEnabled(!on);
        stepDetail.setText(on ? "Updating the device on the MDM server..." : null);
        stepProgress.setVisibility(on ? View.VISIBLE : View.GONE);
        stepProgress.setIndeterminate(true);
    }

    /**
     * Asks the launcher to load its configuration now: settings, policies and, with launcher 6.55
     * or later, app installs and removals (older launchers leave them for the next boot). Only once
     * operative: during the startup update it would start a second update in parallel.
     */
    private void syncConfig() {
        headwindMDM.forceConfigUpdate();
        int error = headwindMDM.getMdmAgentError();
        if (error != 0) {
            log("FAILED to request the configuration sync: MDM agent error " + error);
            return;
        }
        log("Configuration sync requested");
        setSyncing(true);
    }

    private void setSyncing(boolean on) {
        syncing = on;
        findViewById(R.id.syncButton).setEnabled(!on);
        stepDetail.setText(on ? "Syncing configuration..." : null);
        stepProgress.setVisibility(on ? View.VISIBLE : View.GONE);
        stepProgress.setIndeterminate(true);
        handler.removeCallbacks(syncTimeout);
        if (on) {
            handler.postDelayed(syncTimeout, SYNC_TIMEOUT_MS);
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---- UI ----

    private void setStep(State newState, String title, String detail) {
        state = newState;
        stepTitle.setText(title);
        stepDetail.setText(detail);
        stepProgress.setVisibility(View.VISIBLE);
        stepProgress.setIndeterminate(true);
    }

    private void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logView.append("[" + time + "] " + message + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        Log.i(TAG, message);
    }
}
