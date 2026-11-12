package com.rch.mdm.ingenico;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import java.util.List;

/**
 * WiFi setup with WifiManager.addNetwork(), see docs/wifi-setup.md.
 *
 * Android 10 allows addNetwork() only to apps targeting SDK 28 or lower (this app does), and
 * Ingenico's WifiManager refuses a configuration without the key management ("not support
 * type: 0"), so it is always set. The network is an ordinary saved network, kept across reboots,
 * but it belongs to this app: Android removes it if the app is uninstalled.
 */
@SuppressWarnings("deprecation")
final class WifiSetup {

    private WifiSetup() {
    }

    /** True if the device has a network with validated internet access (WiFi or other). */
    static boolean hasInternet(ConnectivityManager cm) {
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * Adds the network (WPA/WPA2 personal, or open if the password is empty) and connects to it.
     * An earlier network of this app with the same name is replaced.
     * @return the network ID, or -1 if Android refused it (e.g. the same network was saved by the
     *         settings or by another app: only that one can change it)
     */
    static int addNetwork(WifiManager wifi, String ssid, String password) {
        removeOwnNetworks(wifi, ssid);
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = quoted(ssid);
        if (password.isEmpty()) {
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            conf.preSharedKey = quoted(password);
            conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }
        if (!wifi.isWifiEnabled()) {
            wifi.setWifiEnabled(true);
        }
        int netId = wifi.addNetwork(conf);
        if (netId != -1) {
            wifi.enableNetwork(netId, true);
        }
        return netId;
    }

    /**
     * Removes this app's networks named ssid. Android lets an app remove only its own networks:
     * the others are left as they are.
     */
    private static void removeOwnNetworks(WifiManager wifi, String ssid) {
        List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
        if (configs == null) {
            return;
        }
        for (WifiConfiguration config : configs) {
            if (quoted(ssid).equals(config.SSID)) {
                wifi.removeNetwork(config.networkId);
            }
        }
    }

    private static String quoted(String s) {
        return "\"" + s + "\"";
    }
}
