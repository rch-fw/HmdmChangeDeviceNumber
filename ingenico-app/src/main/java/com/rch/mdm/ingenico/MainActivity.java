package com.rch.mdm.ingenico;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Brings an Ingenico terminal from the factory state to operative by itself, see
 * docs/ingenico-sample-app.md.
 *
 * At start it checks the MDM agent (the Headwind MDM launcher):
 *
 * - Agent not running: not enrolled yet (new device), or in the stopped state (never run, or
 *   force-stopped, so it missed the boot). The app initializes the device: if there is no network
 *   it configures WiFi (addNetwork, the app targets SDK 28), then starts the launcher's main screen,
 *   which enrolls it and runs its startup update. The app stays behind it: when the startup update
 *   is over, the launcher brings it back to the front (Run at boot in the configuration).
 * - Agent running: nothing to start.
 *
 * In both cases it then synchronizes with the agent through the startup gate
 * (awaitStartupUpdate): it waits until the launcher has installed the app updates of this boot,
 * because installing an app kills it. Then it is operative.
 *
 * Needs MDM launcher 6.54 or later: before its enrollment the launcher keeps its startup update
 * open, so the WiFi setup can take as long as needed.
 */
public class MainActivity extends AppCompatActivity implements HeadwindMDM.EventHandler {

    static final String TAG = "IngenicoSample";

    private static final long CONNECT_TIMEOUT_MS = 15 * 1000;
    // addNetwork connects in a few seconds: after this, hint at a wrong password or no signal
    private static final long WIFI_REMINDER_MS = 20 * 1000;
    // The launcher reports only successful configuration updates: stop waiting after this
    private static final long SYNC_TIMEOUT_MS = 90 * 1000;

    private static final int REQUEST_LOCATION = 1;

    private enum State { CONNECTING, WIFI, STARTING_AGENT, SYNCHRONIZING, READY }

    private State state = State.CONNECTING;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HeadwindMDM headwindMDM;
    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;

    /** The launcher was stopped when the app started (read before connecting: binding clears it). */
    private boolean launcherStopped;
    /** Set while the launcher's activity is being started: synchronize in onStop(). */
    private boolean launcherStarting;
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

    // WiFi panel
    private View wifiPanel;
    private EditText ssidField;
    private EditText passwordField;
    private Button wifiConnectButton;
    private Button wifiCancelButton;
    private TextView scanStatus;
    private final List<AccessPoint> accessPoints = new ArrayList<>();
    private ArrayAdapter<AccessPoint> listAdapter;
    private boolean scanReceiverRegistered;
    /** SSID added, waiting for the connection; null if none. */
    private String pendingSsid;
    private ConnectivityManager.NetworkCallback networkCallback;

    /** An access point of the scan, one per SSID (the strongest). */
    private static class AccessPoint {
        final String ssid;
        final int level;
        final boolean open;
        final boolean supported;

        AccessPoint(ScanResult result) {
            ssid = result.SSID;
            level = WifiManager.calculateSignalLevel(result.level, 5);
            String caps = result.capabilities == null ? "" : result.capabilities;
            open = !caps.contains("WPA") && !caps.contains("WEP") && !caps.contains("SAE") && !caps.contains("EAP");
            supported = open || (caps.contains("PSK") && !caps.contains("EAP"));
        }

        @Override
        public String toString() {
            String bars = "▂▄▆█".substring(0, Math.max(1, level));
            return ssid + "   " + bars + (open ? "" : "  🔒") + (supported ? "" : "  (not supported)");
        }
    }

    private final Runnable connectTimeout = () -> {
        if (state == State.CONNECTING) {
            log("No answer from the MDM agent within " + CONNECT_TIMEOUT_MS / 1000 + " s");
            ready("MDM agent not available");
        }
    };

    private final Runnable wifiReminder = () -> {
        if (pendingSsid != null) {
            stepDetail.setText("Not connected yet to " + pendingSsid
                    + ". Check the password and that the network is in range.");
        }
    };

    private final Runnable syncTimeout = () -> {
        if (syncing) {
            log("Configuration sync: no answer from the agent within " + SYNC_TIMEOUT_MS / 1000 + " s");
            setSyncing(false);
        }
    };

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            showScanResults(updated ? null : "Scan limited by Android, showing the last results");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

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
        findViewById(R.id.wifiButton).setOnClickListener(v -> showWifi());
        setUpWifiPanel();

        String launcher = HeadwindMDM.getLauncherPackage(this);
        if (launcher == null) {
            log("MDM launcher not installed");
            ready("MDM agent not installed");
            return;
        }
        // Before connect(): binding to the launcher's service takes it out of the stopped state
        launcherStopped = isStopped(launcher);

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
    protected void onResume() {
        super.onResume();
        if (launcherStarting) {
            // Back here without onStop(): the launcher's activity didn't start
            launcherStarting = false;
            log("The MDM agent's screen didn't start");
            synchronize();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (launcherStarting) {
            // onStop() comes after the launcher activity's onResume(), where it has started its
            // enrollment and configuration update. The app stays behind the launcher and waits at
            // the startup gate: when the startup update is over, the launcher starts the apps marked
            // Run at boot, and this one (singleTask) comes back to the front with its state.
            launcherStarting = false;
            log("MDM agent started, waiting behind it");
            synchronize();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopWaitingForWifi();
        if (scanReceiverRegistered) {
            unregisterReceiver(scanReceiver);
        }
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
        boolean notEnrolled = headwindMDM.getMdmAgentError() == MDMError.ERROR_NOT_CONFIGURED;
        if (notEnrolled || launcherStopped) {
            log("MDM agent not running (" + (notEnrolled ? "not enrolled" : "stopped") + "): initializing the device");
            if (WifiSetup.hasInternet(connectivityManager)) {
                log("Network already connected");
                startAgent();
            } else {
                log("No network: configure WiFi");
                showWifi();
            }
        } else {
            log("MDM agent running");
            synchronize();
        }
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
     * Starts the launcher's main screen (hmdm library 1.2.3, HeadwindMDM.startLauncher()), which
     * enrolls it and runs its startup update.
     */
    private void startAgent() {
        setStep(State.STARTING_AGENT, "Starting the MDM agent", "It enrolls and loads its configuration");
        launcherStarting = true;
        if (HeadwindMDM.startLauncher(this)) {
            log("Starting the MDM agent's main screen");
        } else {
            launcherStarting = false;
            log("FAILED to start the MDM agent (details in logcat, tag HeadwindMDM)");
            synchronize();
        }
    }

    /**
     * Startup gate: waits until the launcher has installed the app updates of this boot (installing
     * an app kills it). The gate always opens: at the end of the startup update, or at a timeout.
     */
    private void synchronize() {
        if (state == State.SYNCHRONIZING || state == State.READY) {
            return;
        }
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
        if (headwindMDM != null) {
            // Read the device data again: if the app started the launcher, the data read at the
            // first connection was from before the enrollment. onHeadwindMDMConnected() shows it.
            headwindMDM.disconnect(this);
            if (headwindMDM.connect(this, this)) {
                return;
            }
        }
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

    private boolean isStopped(String pkg) {
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---- WiFi ----

    private void setUpWifiPanel() {
        wifiPanel = findViewById(R.id.wifiPanel);
        ssidField = findViewById(R.id.wifiSsid);
        passwordField = findViewById(R.id.wifiPassword);
        wifiConnectButton = findViewById(R.id.wifiConnectButton);
        wifiCancelButton = findViewById(R.id.wifiCancelButton);
        scanStatus = findViewById(R.id.wifiScanStatus);

        CheckBox showPassword = findViewById(R.id.wifiShowPassword);
        showPassword.setOnCheckedChangeListener((button, checked) -> {
            passwordField.setInputType(InputType.TYPE_CLASS_TEXT | (checked
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            passwordField.setSelection(passwordField.getText().length());
        });

        ListView list = findViewById(R.id.wifiList);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, accessPoints);
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AccessPoint ap = accessPoints.get(position);
            ssidField.setText(ap.ssid);
            ssidField.setError(ap.supported ? null : "Only WPA/WPA2 personal and open networks are supported");
            if (ap.open) {
                passwordField.setText("");
            } else {
                passwordField.requestFocus();
            }
        });
        findViewById(R.id.wifiScanButton).setOnClickListener(v -> scan());
        wifiConnectButton.setOnClickListener(v -> connectWifi());
        wifiCancelButton.setOnClickListener(v -> hideWifi());
    }

    /** During the initialization, or from the operative screen (then it can be cancelled). */
    private void showWifi() {
        boolean fromReady = state == State.READY;
        if (!fromReady) {
            setStep(State.WIFI, "Configure WiFi", "Choose the network and enter its password");
            stepProgress.setVisibility(View.GONE);
        }
        wifiCancelButton.setVisibility(fromReady ? View.VISIBLE : View.GONE);
        readyPanel.setVisibility(View.GONE);
        logScroll.setVisibility(View.GONE);
        wifiPanel.setVisibility(View.VISIBLE);
        scan();
    }

    private void hideWifi() {
        stopWaitingForWifi();
        pendingSsid = null;
        wifiPanel.setVisibility(View.GONE);
        logScroll.setVisibility(View.VISIBLE);
        if (state == State.READY) {
            readyPanel.setVisibility(View.VISIBLE);
            stepDetail.setText(null);
        }
    }

    private void connectWifi() {
        String ssid = ssidField.getText().toString().trim();
        String password = passwordField.getText().toString();
        if (TextUtils.isEmpty(ssid)) {
            ssidField.setError("Enter the network name");
            return;
        }
        if (!password.isEmpty() && password.length() < 8) {
            passwordField.setError("WPA/WPA2 password: at least 8 characters (empty for an open network)");
            return;
        }
        int netId = WifiSetup.addNetwork(wifiManager, ssid, password);
        if (netId == -1) {
            // Never log the password
            log("FAILED to add the network " + ssid + " (refused by Android)");
            stepDetail.setText("Network " + ssid + " refused by Android. If it was saved in the settings, "
                    + "forget it there first.");
            return;
        }
        log("WiFi: network " + ssid + " added, connecting");
        stepDetail.setText("Connecting to " + ssid + "...");
        stepProgress.setVisibility(View.VISIBLE);
        stepProgress.setIndeterminate(true);
        pendingSsid = ssid;
        waitForWifi();
    }

    private void waitForWifi() {
        stopWaitingForWifi();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(() -> onWifiConnected());
            }
        };
        connectivityManager.registerNetworkCallback(request, networkCallback);
        handler.postDelayed(wifiReminder, WIFI_REMINDER_MS);
    }

    private void stopWaitingForWifi() {
        handler.removeCallbacks(wifiReminder);
        if (networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
    }

    private void onWifiConnected() {
        if (pendingSsid == null) {
            return;
        }
        log("WiFi: connected to " + pendingSsid + " with internet");
        hideWifi();
        if (state == State.WIFI) {
            startAgent();
        }
    }

    /** Scan results need the location permission (granted at install by the Ingenico firmware). */
    private void scan() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            scanStatus.setText("Allow the location permission to list the networks");
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQUEST_LOCATION);
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!locationManager.isLocationEnabled()) {
            scanStatus.setText("Location is off: type the network name");
            return;
        }
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }
        if (!scanReceiverRegistered) {
            registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            scanReceiverRegistered = true;
        }
        showScanResults(null);
        @SuppressWarnings("deprecation")
        boolean started = wifiManager.startScan();
        scanStatus.setText(started ? "Scanning..." : "Available networks (scan limited by Android)");
    }

    private void showScanResults(String note) {
        Map<String, AccessPoint> bySsid = new HashMap<>();
        for (ScanResult result : wifiManager.getScanResults()) {
            if (TextUtils.isEmpty(result.SSID)) {
                continue;   // hidden network: type its name
            }
            AccessPoint ap = new AccessPoint(result);
            AccessPoint known = bySsid.get(ap.ssid);
            if (known == null || ap.level > known.level) {
                bySsid.put(ap.ssid, ap);
            }
        }
        accessPoints.clear();
        accessPoints.addAll(bySsid.values());
        Collections.sort(accessPoints, (a, b) -> a.level != b.level ? b.level - a.level : a.ssid.compareToIgnoreCase(b.ssid));
        listAdapter.notifyDataSetChanged();
        scanStatus.setText(note != null ? note : accessPoints.isEmpty()
                ? "No networks found: tap Scan, or type the name"
                : "Available networks (" + accessPoints.size() + ")");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scan();
            } else {
                scanStatus.setText("No location permission: type the network name");
            }
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
