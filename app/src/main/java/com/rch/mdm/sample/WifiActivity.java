package com.rch.mdm.sample;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configures the WiFi network without the system settings, in one of three modes
 * (see docs/wifi-setup.md):
 *
 * - Android addNetwork (WifiManager.addNetwork()): Android connects right away, no prompt, no
 *   reboot, any signing key. Allowed on Android 10 only because the app targets SDK 28. The network
 *   belongs to the app: removed if the app is uninstalled. On a new device the MDM launcher, not
 *   enrolled yet, then enrolls and installs the apps in this boot when it is started (launcher 6.54).
 * - Ingenico USDK (UsdkWifi): the network is saved in the "WiFi auto" settings, then the device
 *   reboots and connects at boot; the MDM launcher's startup update then loads the configuration
 *   and installs the apps. Needs the Ingenico-approved signing key.
 * - Android suggestion (WifiManager.addNetworkSuggestions()): Android connects without a reboot,
 *   once the user allows the suggestion in a silent notification, which users hardly find:
 *   not usable in practice on Android 10.
 *
 * Not tied to the startup gate: without a network the gate stays closed until the launcher
 * gives up, and configuring WiFi is not interrupted by an app update.
 */
public class WifiActivity extends AppCompatActivity {

    /** Result for MainActivity: the user asked for a configuration sync. */
    public static final int RESULT_SYNC = RESULT_FIRST_USER;

    private static final String TAG = "MooneySample";
    private static final int REQUEST_LOCATION = 1;

    private static final String PREFS = "sample";
    private static final String PREF_MODE = "wifiMode";
    private static final String MODE_ADD_NETWORK = "addNetwork";
    private static final String MODE_USDK = "usdk";
    private static final String MODE_SUGGESTION = "suggestion";

    // After this time without a connection, remind the user to allow the suggestion (keeps waiting)
    private static final long SUGGESTION_REMINDER_MS = 60 * 1000;
    // addNetwork connects in a few seconds: after this, hint at a wrong password or no signal
    private static final long ADD_NETWORK_REMINDER_MS = 20 * 1000;

    /** An access point of the scan, one per SSID (the strongest). */
    private class AccessPoint {
        final String ssid;
        final int level;
        final boolean psk;
        final boolean open;

        AccessPoint(ScanResult result) {
            ssid = result.SSID;
            level = WifiManager.calculateSignalLevel(result.level, 5);
            String caps = result.capabilities == null ? "" : result.capabilities;
            psk = caps.contains("PSK") && !caps.contains("EAP");
            open = !caps.contains("WPA") && !caps.contains("WEP") && !caps.contains("SAE") && !caps.contains("EAP");
        }

        /** "WiFi auto" takes a WPA/WPA2 password; addNetwork and suggestions also take open networks. */
        boolean supported() {
            return psk || (open && !isUsdkMode());
        }

        @Override
        public String toString() {
            String bars = "▂▄▆█".substring(0, Math.max(1, level));
            return ssid + "   " + bars + (open ? "" : "  🔒") + (supported() ? "" : "  (not supported)");
        }
    }

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<AccessPoint> accessPoints = new ArrayList<>();
    private ArrayAdapter<AccessPoint> listAdapter;
    private TextView scanStatus;
    private boolean receiverRegistered;

    private TextView status;
    private RadioButton modeAddNetwork;
    private RadioButton modeUsdk;
    private RadioButton modeSuggestion;
    private EditText ssidField;
    private EditText passwordField;
    private Button saveButton;
    private Button resetButton;
    private View connectedPanel;
    private ProgressBar progress;

    /** Ingenico USDK usable: installed and accepting this app's calls (signing key). */
    private boolean usdkUsable;
    /** Set while the mode is switched by the app, not by the user: not saved. */
    private boolean forcedMode;

    /** SSID of the network added or suggested, waiting for the connection; null if none. */
    private String pendingSsid;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            showScanResults(updated ? null : "Scan not updated (Android limits how often apps scan), showing the last results");
        }
    };

    private final Runnable connectionReminder = () -> {
        if (pendingSsid != null) {
            status.setText("Not connected yet to " + pendingSsid + ".\n" + (isSuggestionMode()
                    ? approvalHint() : "Check the password and that the network is in range."));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi);
        setTitle("Configure WiFi");

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        status = findViewById(R.id.wifiStatus);
        ssidField = findViewById(R.id.wifiSsid);
        passwordField = findViewById(R.id.wifiPassword);
        saveButton = findViewById(R.id.wifiSaveButton);
        resetButton = findViewById(R.id.wifiResetButton);
        connectedPanel = findViewById(R.id.wifiConnectedPanel);
        progress = findViewById(R.id.wifiProgress);
        modeAddNetwork = findViewById(R.id.wifiModeAddNetwork);
        modeUsdk = findViewById(R.id.wifiModeUsdk);
        modeSuggestion = findViewById(R.id.wifiModeSuggestion);

        CheckBox showPassword = findViewById(R.id.wifiShowPassword);
        showPassword.setOnCheckedChangeListener((button, checked) -> {
            passwordField.setInputType(InputType.TYPE_CLASS_TEXT | (checked
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            passwordField.setSelection(passwordField.getText().length());
        });

        saveButton.setOnClickListener(v -> confirmSave());
        resetButton.setOnClickListener(v -> confirmReset());
        findViewById(R.id.wifiSyncButton).setOnClickListener(v -> {
            // MainActivity requests the sync and shows its progress
            setResult(RESULT_SYNC);
            finish();
        });
        findViewById(R.id.wifiRebootButton).setOnClickListener(v -> confirmReboot());

        ListView list = findViewById(R.id.wifiList);
        scanStatus = findViewById(R.id.wifiScanStatus);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, accessPoints);
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AccessPoint ap = accessPoints.get(position);
            ssidField.setText(ap.ssid);
            ssidField.setError(null);
            if (!ap.supported()) {
                ssidField.setError(isUsdkMode()
                        ? "Only WPA/WPA2 personal networks are supported"
                        : "Only WPA/WPA2 personal and open networks are supported");
            }
            if (ap.open) {
                passwordField.setText("");
            } else {
                passwordField.requestFocus();
            }
        });
        findViewById(R.id.wifiScanButton).setOnClickListener(v -> scan());

        String mode = prefs.getString(PREF_MODE, MODE_ADD_NETWORK);
        (MODE_USDK.equals(mode) ? modeUsdk : MODE_SUGGESTION.equals(mode) ? modeSuggestion : modeAddNetwork)
                .setChecked(true);
        ((RadioGroup) findViewById(R.id.wifiMode)).setOnCheckedChangeListener((group, checkedId) -> {
            if (!forcedMode) {
                prefs.edit().putString(PREF_MODE, isUsdkMode() ? MODE_USDK
                        : isSuggestionMode() ? MODE_SUGGESTION : MODE_ADD_NETWORK).apply();
            }
            updateMode();
        });

        usdkUsable = UsdkWifi.isAvailable(this);
        if (!usdkUsable) {
            disableUsdk("Ingenico USDK not available on this device");
        }
        updateMode();
        refreshStatus();
        scan();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver);
        }
        stopWaitingForConnection();
        super.onDestroy();
    }

    private boolean isSuggestionMode() {
        return modeSuggestion.isChecked();
    }

    private boolean isUsdkMode() {
        return modeUsdk.isChecked();
    }

    private void updateMode() {
        saveButton.setText(isUsdkMode() ? "Save and reboot" : "Connect");
        listAdapter.notifyDataSetChanged();
    }

    /** The USDK can't be used: the Android modes only. */
    private void disableUsdk(String reason) {
        usdkUsable = false;
        modeUsdk.setEnabled(false);
        modeUsdk.setText("Ingenico USDK: not available (" + reason + ")");
        if (isUsdkMode()) {
            forcedMode = true;
            modeAddNetwork.setChecked(true);
            forcedMode = false;
        }
    }

    // ---- Status ----

    private void refreshStatus() {
        final String connection = connectionText();
        if (!usdkUsable) {
            status.setText(connection);
            return;
        }
        setBusy(true);
        UsdkWifi.readStatus(this, (error, autoEnabled, ssid) -> {
            setBusy(false);
            if (error != null) {
                Log.w(TAG, "WiFi: can't read the USDK settings: " + error);
                disableUsdk("calls refused, the app must be signed with the Ingenico-approved key");
                status.setText(connection + "\nIngenico USDK refused the calls (" + error + ")");
                return;
            }
            status.setText(connection + (autoEnabled && !ssid.isEmpty()
                    ? "\nIngenico WiFi auto set for the next boot: " + ssid : ""));
            if (ssidField.getText().length() == 0 && !ssid.isEmpty()) {
                ssidField.setText(ssid);
            }
        });
    }

    private String connectionText() {
        String ssid = connectedSsid();
        if (ssid != null) {
            return "WiFi connected to " + ssid;
        }
        return isWifiConnected() ? "WiFi connected" : "WiFi not connected";
    }

    private boolean isWifiConnected() {
        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : connectivityManager.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    /** SSID the device is connected to, without quotes; null if none or unknown (location). */
    @SuppressWarnings("deprecation")
    private String connectedSsid() {
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1 || info.getSSID() == null
                || WifiManager.UNKNOWN_SSID.equals(info.getSSID())) {
            return null;
        }
        String ssid = info.getSSID();
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    // ---- Network list ----

    /** Scan results need the location permission and, on Android 10, the location turned on. */
    private void scan() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            scanStatus.setText("Allow the location permission to list the networks");
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQUEST_LOCATION);
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!locationManager.isLocationEnabled()) {
            scanStatus.setText("Location is off: turn it on to list the networks, or type the name");
            return;
        }
        if (!wifiManager.isWifiEnabled()) {
            scanStatus.setText("WiFi is off: type the network name");
            return;
        }
        if (!receiverRegistered) {
            registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            receiverRegistered = true;
        }
        // Show the last results at once, the new ones when the scan is over
        showScanResults(null);
        @SuppressWarnings("deprecation")
        boolean started = wifiManager.startScan();
        scanStatus.setText(started ? "Scanning..." : "Available networks (scan limited by Android, try again later)");
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

    // ---- Save / connect ----

    private void confirmSave() {
        final String ssid = ssidField.getText().toString().trim();
        final String password = passwordField.getText().toString();
        if (TextUtils.isEmpty(ssid)) {
            ssidField.setError("Enter the network name");
            return;
        }
        if (!isUsdkMode()) {
            // Empty password: open network
            if (!password.isEmpty() && password.length() < 8) {
                passwordField.setError("WPA/WPA2 password: at least 8 characters (empty for an open network)");
                return;
            }
            if (isSuggestionMode()) {
                suggest(ssid, password);
            } else {
                addNetwork(ssid, password);
            }
            return;
        }
        if (password.length() < 8) {
            passwordField.setError("WPA/WPA2 password: at least 8 characters");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Configure WiFi")
                .setMessage("Save the network \"" + ssid + "\" and reboot the device? "
                        + "It connects to the network at boot.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save and reboot", (dialog, which) -> saveWithUsdk(ssid, password))
                .show();
    }

    private void saveWithUsdk(String ssid, String password) {
        setBusy(true);
        // Never log the password
        Log.i(TAG, "WiFi: saving network " + ssid + " (Ingenico USDK)");
        UsdkWifi.save(this, ssid, password, error -> {
            if (error != null) {
                setBusy(false);
                status.setText("FAILED to save the network: " + error);
                Log.w(TAG, "WiFi: FAILED to save the network: " + error);
                return;
            }
            status.setText("Network saved, rebooting...");
            Log.i(TAG, "WiFi: network saved, rebooting");
            UsdkWifi.reboot(this, rebootError -> {
                setBusy(false);
                status.setText("Network saved, but the reboot FAILED: " + rebootError
                        + "\nReboot the device by hand to connect.");
                Log.w(TAG, "WiFi: reboot FAILED: " + rebootError);
            });
        });
    }

    /**
     * Android addNetwork: a saved network, connected right away. Android 10 allows it only to apps
     * targeting SDK 28 or lower. Ingenico's WifiManager refuses a configuration without the key
     * management ("not support type: 0"), so it is always set.
     */
    @SuppressWarnings("deprecation")
    private void addNetwork(String ssid, String password) {
        // Replace this app's earlier network with the same name (Android refuses a duplicate)
        removeOwnNetworks(ssid);
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + ssid + "\"";
        if (password.isEmpty()) {
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            conf.preSharedKey = "\"" + password + "\"";
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }
        int netId = wifiManager.addNetwork(conf);
        if (netId == -1) {
            // Never log the password
            status.setText("FAILED to add the network " + ssid + " (refused by Android)."
                    + "\nIf it was saved by the settings or another app, forget it there first.");
            Log.w(TAG, "WiFi: FAILED to add network " + ssid + " (addNetwork refused)");
            return;
        }
        boolean enabled = wifiManager.enableNetwork(netId, true);
        Log.i(TAG, "WiFi: network " + ssid + " added (Android addNetwork, id " + netId
                + ", enabled " + enabled + "), waiting for the connection");
        connectedPanel.setVisibility(View.GONE);
        pendingSsid = ssid;
        status.setText("Network " + ssid + " added, connecting...");
        waitForConnection();
    }

    /**
     * Removes the networks this app added (all, or those named ssid). Android lets an app remove only
     * its own networks: the others stay. Returns how many were removed.
     */
    @SuppressWarnings("deprecation")
    private int removeOwnNetworks(String ssid) {
        List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
        if (configs == null) {
            return 0;
        }
        int removed = 0;
        for (WifiConfiguration config : configs) {
            if (ssid != null && !("\"" + ssid + "\"").equals(config.SSID)) {
                continue;
            }
            if (wifiManager.removeNetwork(config.networkId)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Android suggestion: Android connects once the user allows the app's suggestions (notification),
     * when the network is in range. The approval is asked only once per app.
     */
    private void suggest(String ssid, String password) {
        // Android 10 refuses a suggestion with the SSID of an existing one: remove the app's suggestions first
        wifiManager.removeNetworkSuggestions(new ArrayList<>());
        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setIsAppInteractionRequired(true);
        if (!password.isEmpty()) {
            builder.setWpa2Passphrase(password);
        }
        int result = wifiManager.addNetworkSuggestions(Collections.singletonList(builder.build()));
        if (result != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            status.setText("FAILED to suggest the network: error " + result);
            Log.w(TAG, "WiFi: FAILED to suggest network " + ssid + ": error " + result);
            return;
        }
        Log.i(TAG, "WiFi: network " + ssid + " suggested (Android suggestion), waiting for the connection");
        connectedPanel.setVisibility(View.GONE);
        if (ssid.equals(connectedSsid())) {
            onConnected(ssid);
            return;
        }
        pendingSsid = ssid;
        status.setText("Network " + ssid + " suggested, waiting for the connection...\n" + approvalHint());
        waitForConnection();
    }

    private String approvalHint() {
        return "If asked, allow it: open the notification shade, Silent notifications, "
                + "\"Allow suggested Wi‑Fi networks?\", expand it and tap ALLOW.";
    }

    private void waitForConnection() {
        stopWaitingForConnection();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(() -> {
                    String ssid = connectedSsid();
                    if (pendingSsid != null && (ssid == null || ssid.equals(pendingSsid))) {
                        onConnected(pendingSsid);
                    }
                });
            }
        };
        connectivityManager.registerNetworkCallback(request, networkCallback);
        handler.postDelayed(connectionReminder, isSuggestionMode() ? SUGGESTION_REMINDER_MS : ADD_NETWORK_REMINDER_MS);
    }

    private void stopWaitingForConnection() {
        handler.removeCallbacks(connectionReminder);
        if (networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
    }

    private void onConnected(String ssid) {
        pendingSsid = null;
        stopWaitingForConnection();
        Log.i(TAG, "WiFi: connected to " + ssid + " with internet ("
                + (isSuggestionMode() ? "Android suggestion" : "Android addNetwork") + ")");
        status.setText("Connected to " + ssid + " with internet.\n"
                + "New device: go back and tap Start MDM launcher, which installs the apps now.\n"
                + "Launcher already enrolled: Sync configuration loads its configuration (settings, "
                + "policies); Reboot also installs the apps.");
        connectedPanel.setVisibility(View.VISIBLE);
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reboot")
                .setMessage("Reboot the device now? At boot the MDM launcher loads its configuration and installs the apps.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reboot", (dialog, which) -> {
                    if (!usdkUsable) {
                        status.setText("This app can't reboot the device (Ingenico USDK not usable): reboot it by hand.");
                        return;
                    }
                    setBusy(true);
                    UsdkWifi.reboot(this, error -> {
                        setBusy(false);
                        status.setText("Reboot FAILED: " + error + "\nReboot the device by hand.");
                        Log.w(TAG, "WiFi: reboot FAILED: " + error);
                    });
                })
                .show();
    }

    // ---- Reset ----

    /**
     * Back to the factory state: removes the networks this app added, its Android suggestions, and resets Ingenico
     * "WiFi auto" (off, no SSID, no password). Otherwise the network comes back at every boot, even
     * after forgetting it in the settings. To reproduce a device fresh from the factory: reset here,
     * then forget the network in the settings.
     */
    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset WiFi setup")
                .setMessage("Remove the networks this app added or suggested, and turn off Ingenico WiFi auto "
                        + "(network and password cleared)? A network saved by the settings or by WiFi auto stays: "
                        + "forget it in the settings to start from scratch.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (dialog, which) -> reset())
                .show();
    }

    private void reset() {
        pendingSsid = null;
        stopWaitingForConnection();
        connectedPanel.setVisibility(View.GONE);
        Log.i(TAG, "WiFi: " + removeOwnNetworks(null) + " network(s) added by this app removed");
        int removed = wifiManager.removeNetworkSuggestions(new ArrayList<>());
        Log.i(TAG, "WiFi: suggestions removed (status " + removed + ")");
        ssidField.setText("");
        passwordField.setText("");
        if (!usdkUsable) {
            refreshStatus();
            return;
        }
        setBusy(true);
        UsdkWifi.reset(this, error -> {
            setBusy(false);
            if (error != null) {
                status.setText("FAILED to reset WiFi auto: " + error);
                Log.w(TAG, "WiFi: FAILED to reset WiFi auto: " + error);
                return;
            }
            Log.i(TAG, "WiFi: WiFi auto reset");
            refreshStatus();
        });
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy);
        resetButton.setEnabled(!busy);
    }
}
