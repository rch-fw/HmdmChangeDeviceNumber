package com.rch.mdm.sample;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.usdk.apiservice.aidl.ModuleName;
import com.usdk.apiservice.aidl.UDeviceService;
import com.usdk.apiservice.aidl.data.BooleanValue;
import com.usdk.apiservice.aidl.data.StringValue;
import com.usdk.apiservice.aidl.device.UDeviceManager;
import com.usdk.apiservice.aidl.networkmanager.UNetWorkManager;

/**
 * WiFi configuration through the Ingenico USDK "WiFi auto" settings (UNetWorkManager).
 *
 * The settings are applied at the next boot: the system creates a saved WiFi network with
 * that SSID and password and connects to it. So the network is saved with {@link #save} and
 * the device is rebooted with {@link #reboot}.
 *
 * "WiFi auto" stays on and is applied again at every boot, recreating the network even after the
 * user forgets it in the settings; {@link #reset} turns it off.
 *
 * The USDK accepts the calls only from apps signed with an Ingenico-approved key
 * (the launcher's key): with the debug key they fail.
 */
public class UsdkWifi {

    private static final String USDK_SERVICE = "com.usdk.apiservice";

    public interface Callback {
        /** On the main thread. error is null on success. */
        void done(String error);
    }

    public interface StatusCallback {
        /** On the main thread. error is null on success; ssid is the WiFi auto SSID, may be empty. */
        void done(String error, boolean autoEnabled, String ssid);
    }

    private interface Task {
        /** Returns an error message, or null on success. */
        String run(UDeviceService service) throws Exception;
    }

    private static final Handler handler = new Handler(Looper.getMainLooper());

    public static boolean isAvailable(Context context) {
        Intent intent = new Intent(USDK_SERVICE);
        intent.setPackage(USDK_SERVICE);
        return !context.getPackageManager().queryIntentServices(intent, 0).isEmpty();
    }

    /** Reads the current WiFi auto settings. */
    public static void readStatus(Context context, final StatusCallback callback) {
        final BooleanValue enabled = new BooleanValue();
        final StringValue ssid = new StringValue();
        run(context, service -> {
            UNetWorkManager nm = networkManager(service);
            int ret = nm.isWifiAutoEnabled(enabled);
            if (ret != 0) return "isWifiAutoEnabled error " + ret;
            ret = nm.getWifiAutoSSID(ssid);
            if (ret != 0) return "getWifiAutoSSID error " + ret;
            return null;
        }, error -> callback.done(error, enabled.isTrue(), ssid.getData() == null ? "" : ssid.getData()));
    }

    /** Saves the WiFi network to connect to at the next boot (WPA/WPA2 personal). */
    public static void save(Context context, final String ssid, final String password, Callback callback) {
        run(context, service -> {
            UNetWorkManager nm = networkManager(service);
            int ret = nm.setWifiEnabled(true);
            if (ret != 0) return "setWifiEnabled error " + ret;
            ret = nm.setWifiAutoSSID(ssid);
            if (ret != 0) return "setWifiAutoSSID error " + ret;
            ret = nm.setWifiAutoPasswd(password);
            if (ret != 0) return "setWifiAutoPasswd error " + ret;
            ret = nm.setWifiAutoEnabled(true);
            if (ret != 0) return "setWifiAutoEnabled error " + ret;
            return null;
        }, callback);
    }

    /**
     * Turns WiFi auto off and blanks the stored SSID and password, as on a device fresh from the
     * factory. The network Android already saved is not removed (forget it in the settings).
     */
    public static void reset(Context context, Callback callback) {
        run(context, service -> {
            UNetWorkManager nm = networkManager(service);
            int ret = nm.setWifiAutoEnabled(false);
            if (ret != 0) return "setWifiAutoEnabled error " + ret;
            ret = nm.setWifiAutoPasswd("");
            if (ret != 0) return "setWifiAutoPasswd error " + ret;
            ret = nm.setWifiAutoSSID("");
            if (ret != 0) return "setWifiAutoSSID error " + ret;
            return null;
        }, callback);
    }

    /** Reboots the device. The callback is called only if the reboot fails. */
    public static void reboot(Context context, Callback callback) {
        run(context, service -> {
            UDeviceManager deviceManager = UDeviceManager.Stub.asInterface(service.getDeviceManager());
            deviceManager.reboot();
            return null;
        }, error -> {
            if (error != null) callback.done(error);
        });
    }

    private static UNetWorkManager networkManager(UDeviceService service) throws Exception {
        IBinder binder = service.getModule(ModuleName.NETWORK_MANAGER, null);
        if (binder == null) {
            throw new Exception("USDK network manager not available");
        }
        return UNetWorkManager.Stub.asInterface(binder);
    }

    /** Binds the USDK service, logs in (required before any call), runs the task and unbinds. */
    private static void run(Context context, final Task task, final Callback callback) {
        final Context appContext = context.getApplicationContext();
        Intent intent = new Intent(USDK_SERVICE);
        intent.setPackage(USDK_SERVICE);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                // Binder calls off the main thread
                new Thread(() -> {
                    String error;
                    try {
                        UDeviceService service = UDeviceService.Stub.asInterface(binder);
                        service.register(null, new Binder());
                        error = task.run(service);
                    } catch (Exception e) {
                        error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                    final String result = error;
                    handler.post(() -> {
                        try {
                            appContext.unbindService(this);
                        } catch (IllegalArgumentException e) {
                            // Already unbound
                        }
                        callback.done(result);
                    });
                }).start();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        boolean bound;
        try {
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
        if (!bound) {
            callback.done("Cannot bind the Ingenico USDK service");
        }
    }
}
