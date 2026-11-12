package com.rch.mdm.sample;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
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
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configures the WiFi network without the system settings, through the Ingenico USDK
 * (see UsdkWifi). The network is saved, then the device reboots and connects to it at boot;
 * after the reboot the MDM launcher's startup update loads the configuration and installs the
 * apps as usual.
 *
 * Not tied to the startup gate: without a network the gate stays closed until the launcher
 * gives up, and configuring WiFi is not interrupted by an app update.
 */
public class WifiActivity extends AppCompatActivity {

    private static final String TAG = "MooneySample";
    private static final int REQUEST_LOCATION = 1;

    /** An access point of the scan, one per SSID (the strongest). */
    private static class AccessPoint {
        final String ssid;
        final int level;
        final boolean secured;
        final boolean supported;

        AccessPoint(ScanResult result) {
            ssid = result.SSID;
            level = WifiManager.calculateSignalLevel(result.level, 5);
            String caps = result.capabilities == null ? "" : result.capabilities;
            secured = caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE");
            // "WiFi auto" takes a password: WPA/WPA2 personal only
            supported = caps.contains("PSK") && !caps.contains("EAP");
        }

        @Override
        public String toString() {
            String bars = "▂▄▆█".substring(0, Math.max(1, level));
            return ssid + "   " + bars + (secured ? "  🔒" : "")
                    + (supported ? "" : "  (not supported)");
        }
    }

    private WifiManager wifiManager;
    private final List<AccessPoint> accessPoints = new ArrayList<>();
    private ArrayAdapter<AccessPoint> listAdapter;
    private TextView scanStatus;
    private boolean receiverRegistered;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            showScanResults(updated ? null : "Scan not updated (Android limits how often apps scan), showing the last results");
        }
    };

    private TextView status;
    private EditText ssidField;
    private EditText passwordField;
    private Button saveButton;
    private Button resetButton;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi);
        setTitle("Configure WiFi");

        status = findViewById(R.id.wifiStatus);
        ssidField = findViewById(R.id.wifiSsid);
        passwordField = findViewById(R.id.wifiPassword);
        saveButton = findViewById(R.id.wifiSaveButton);
        progress = findViewById(R.id.wifiProgress);

        CheckBox showPassword = findViewById(R.id.wifiShowPassword);
        showPassword.setOnCheckedChangeListener((button, checked) -> {
            passwordField.setInputType(InputType.TYPE_CLASS_TEXT | (checked
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            passwordField.setSelection(passwordField.getText().length());
        });

        saveButton.setOnClickListener(v -> confirmSave());
        resetButton = findViewById(R.id.wifiResetButton);
        resetButton.setOnClickListener(v -> confirmReset());

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        scanStatus = findViewById(R.id.wifiScanStatus);
        ListView list = findViewById(R.id.wifiList);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, accessPoints);
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AccessPoint ap = accessPoints.get(position);
            ssidField.setText(ap.ssid);
            ssidField.setError(null);
            if (!ap.supported) {
                ssidField.setError("Only WPA/WPA2 personal networks are supported");
            }
            passwordField.requestFocus();
        });
        findViewById(R.id.wifiScanButton).setOnClickListener(v -> scan());

        if (!UsdkWifi.isAvailable(this)) {
            status.setText("Ingenico USDK not available on this device: WiFi can't be configured here.");
            saveButton.setEnabled(false);
            resetButton.setEnabled(false);
            return;
        }
        refreshStatus();
        scan();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver);
        }
        super.onDestroy();
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
            // Turned on by "Save and reboot"; without it there's nothing to scan
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

    private void refreshStatus() {
        final String connection = isWifiConnected() ? "WiFi connected" : "WiFi not connected";
        setBusy(true);
        UsdkWifi.readStatus(this, (error, autoEnabled, ssid) -> {
            setBusy(false);
            if (error != null) {
                status.setText(connection + "\nCan't read the WiFi settings: " + error
                        + "\n(the app must be signed with the Ingenico-approved key)");
                Log.w(TAG, "WiFi: can't read the USDK settings: " + error);
                return;
            }
            status.setText(connection + (autoEnabled && !ssid.isEmpty()
                    ? "\nNetwork set for the next boot: " + ssid : ""));
            if (ssidField.getText().length() == 0 && !ssid.isEmpty()) {
                ssidField.setText(ssid);
            }
        });
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private void confirmSave() {
        final String ssid = ssidField.getText().toString().trim();
        final String password = passwordField.getText().toString();
        if (TextUtils.isEmpty(ssid)) {
            ssidField.setError("Enter the network name");
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
                .setPositiveButton("Save and reboot", (dialog, which) -> save(ssid, password))
                .show();
    }

    private void save(String ssid, String password) {
        setBusy(true);
        // Never log the password
        Log.i(TAG, "WiFi: saving network " + ssid);
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
     * Resets "WiFi auto" to the factory state (off, no SSID, no password): otherwise the network saved
     * here comes back at every boot, even after forgetting it in the settings. To reproduce a device
     * fresh from the factory: reset here, then forget the network in the settings.
     */
    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset WiFi auto")
                .setMessage("Turn off WiFi auto and clear its network and password? The network already "
                        + "saved by Android stays: forget it in the settings to start from scratch.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (dialog, which) -> {
                    setBusy(true);
                    UsdkWifi.reset(this, error -> {
                        setBusy(false);
                        if (error != null) {
                            status.setText("FAILED to reset WiFi auto: " + error);
                            Log.w(TAG, "WiFi: FAILED to reset WiFi auto: " + error);
                            return;
                        }
                        Log.i(TAG, "WiFi: WiFi auto reset");
                        ssidField.setText("");
                        passwordField.setText("");
                        refreshStatus();
                    });
                })
                .show();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy);
        resetButton.setEnabled(!busy);
    }
}
