# WiFi settings from a kiosk app on Sunmi terminals

How a kiosk app lets the user choose a WiFi network on a Sunmi terminal in kiosk mode: which intent to use, what the MDM configuration must allow, and what the MDM launcher does to the screen the user is on. It ends with the [requirements](#requirements) for the configuration and for the kiosk app, and a [proposal](#proposal-launcher-change) for the launcher.

Everything below was measured on a Sunmi terminal (Android 14, 1920×1200 landscape) with launcher 6.56 and `it.mooney.srvmooney` 1.0.6 as kiosk app, on 2026-11-13, unless marked *not tested*.

## Context

On Sunmi terminals the MDM launcher (`com.hmdm.launcher`) is device owner and HOME app. With kiosk mode on, it starts the configuration's main app (the *kiosk app*) and puts the device in **lock task mode**: only the packages in the lock task allowlist can be on screen.

- **The allowlist** is every application in the configuration that isn't marked *remove*, plus the kiosk app and the launcher (`ProUtils.getAllowedApps()`). System apps count too: the default configuration has about 75 packages, including `com.android.chrome`, `com.android.launcher3`, `com.android.packageinstaller` and `com.android.vending`.
- **The allowlist is set only when lock task mode starts**, in practice at boot (`ProUtils.startCosuKioskMode()`). A configuration push doesn't refresh it while kiosk mode is running. A change to the allowed apps therefore needs a **reboot**.
- **A start outside the allowlist fails silently.** `startActivity()` doesn't throw. The system logs `Attempted Lock Task Mode violation` with `startResult 101`, and nothing appears.

## The problem

`srvmooney` opens the WiFi screen with `Settings.ACTION_WIFI_SETTINGS`. Three problems showed up:

1. **Without `com.android.settings` in the configuration the screen doesn't open.** The start is blocked, and the app keeps polling the WiFi state every 3 s, waiting for a change the user can't make.
2. **With Settings allowed, the user gets all of Settings.** On this wide screen Settings opens its two-pane homepage (`DeepLinkHomepageActivity`) with WiFi on the right. The Settings menu is visible right away, inside kiosk mode.
3. **A configuration push hides the WiFi screen.** At every push, and twice at every boot, the launcher starts the kiosk app again (see [Repeated starts of the kiosk app](#repeated-starts-of-the-kiosk-app)). Settings runs in its own task, so the kiosk app's task is brought in front of it. The WiFi screen disappears while the user is using it, with no warning.

## The intents compared

| Intent | What the user gets | Allowlist needs `com.android.settings` | A config push |
|---|---|---|---|
| `Settings.ACTION_WIFI_SETTINGS` | Full Settings in a separate task. On a wide screen: the two-pane homepage, with the menu visible. | Yes | **Covers it** with the kiosk app |
| `Settings.Panel.ACTION_WIFI` | `SettingsPanelActivity`: a WiFi sheet in a separate task, with an **IMPOSTAZIONI** button and a gear per network | Yes | Not tested. It's a separate task, so it would most likely be covered too. |
| **`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`** | `SettingsPanelActivity` closes immediately and hands off to a system dialog (`SunmiInternetDialog`). **The kiosk app stays the top activity**, with the dialog as a window over it. **Mostra tutte**, a gear per network, and **Fine**, which goes back to the app. | **Yes** (measured: blocked without it) | **Survives**: the kiosk app only gets pause/resume |

`ACTION_INTERNET_CONNECTIVITY` is the only intent that keeps the user inside the kiosk app. The panel intents need API 29 or later.

All three still go through the `com.android.settings` package. `SettingsPanelActivity` is declared `singleInstance`, so it always starts a new task, and in lock task mode a new task must belong to an allowed package. **Mostra tutte**, **IMPOSTAZIONI** and the network gears open Settings screens (*not tested*: what they show in kiosk mode).

## Measurements

### Settings not allowed: blocked (17:05)

| Time | Event |
|---|---|
| 17:05:28.896 | `srvmooney` starts `WIFI_SETTINGS` → `Attempted Lock Task Mode violation … Settings$WifiSettingsActivity`, `startResult 101` |
| from 17:05:31 | The page in `srvmooney` logs `getWiFiInfo_step4 EX: WIFI_GETSTATUS still connected` every 3 s |

### Settings allowed, full page covered by a push (17:23)

| Time | Event |
|---|---|
| 17:22:49.97 | `srvmooney` starts `WIFI_SETTINGS`: allowed (`startResult 0`). Settings opens as task #55, in front of `srvmooney` (task #54). |
| 17:22:50.05–50.78 | Settings redirects to `DeepLinkHomepageActivity` (the full homepage) with the WiFi page on top |
| 17:22:51.33 | `srvmooney` stopped, in the background |
| 17:23:23.34 | Launcher: `Got Push Message, type configUpdated` |
| 17:23:23.98 | Launcher: `Showing content from setActions()`, then `startActivity` of the kiosk app |
| 17:23:24.04 | `wm_task_to_front` of task #54 (`srvmooney`), `wm_new_intent` |
| 17:23:24.08–24.76 | Settings paused and stopped: **the WiFi page leaves the screen** |
| 17:23:24.11–24.19 | `srvmooney`: `onRestart`, `onNewIntent`, `onResume` |

The Settings task isn't destroyed: it stays behind, stopped. Whatever the user was typing is no longer visible.

### Internet dialog survives a push (17:36)

| Time | Event |
|---|---|
| 17:36:21.74 | `INTERNET_CONNECTIVITY` → `SettingsPanelActivity` as task #58, which closes right away |
| 17:36:21.78–21.92 | Task #54 (`srvmooney`) back in front and resumed. `SunmiInternetDialog` takes focus over it. |
| 17:36:54.61 | Launcher: `Got Push Message, type configUpdated` |
| 17:36:55.81–55.84 | `wm_new_intent`, `srvmooney` `onPause`/`onResume`. No task change. |
| 17:37:08 | Focus still on `SunmiInternetDialog`, and the dialog is on screen |

### Internet dialog without Settings in the configuration: blocked (17:44)

After `com.android.settings` was removed from the configuration and the terminal rebooted (allowlist 74 packages, without Settings):

```
Attempted Lock Task Mode violation r=ActivityRecord{… com.android.settings/.panel.SettingsPanelActivity
startResult ================ 101
START u0 {act=android.settings.panel.action.INTERNET_CONNECTIVITY … cmp=com.android.settings/.panel.SettingsPanelActivity} with LAUNCH_SINGLE_INSTANCE … result code=101
```

This was started from adb, not from the kiosk app. The lock task check depends on the activity being started, not on the caller, and `singleInstance` forces a new task, so the same result is expected from the app (*to be confirmed* with a kiosk app build that uses this intent).

## Repeated starts of the kiosk app

The launcher starts the kiosk app with `startActivity()` every time it shows its content (`MainActivity.showContent()` → `ProUtils.startCosuKioskMode()`), even when kiosk mode is already running and the kiosk app is already on screen.

| Trigger | Starts the kiosk app | Measured |
|---|---|---|
| Boot | 2 times (sometimes 3): when the launcher starts, and at the end of its configuration update. The second start can come **while the user is already using the app** (one boot: a touch, then the start 3 s later). | Yes |
| Configuration push from the server (`configUpdated`) | Once, even with nothing changed and nothing to install | Yes, 4 times |
| Launcher's own updates: MQTT ping check, periodic workers, an app's sync request (`forceConfigUpdate()`) | No | Yes (ping check) |
| Launcher's `MainActivity` recreated by a configuration change (orientation, locale, SIM, font scale) | Yes | At boot only |

With `kiosk_restart_delay_ms` set to a value other than 0, every start is delayed, and none is ever cancelled. Keep it at 0.

What a start does to the kiosk app:

| On screen when the start arrives | Effect |
|---|---|
| The kiosk app's root activity | `onPause`, `onNewIntent` (with `singleTask`), `onResume`. About 20–45 ms. |
| Another activity of the kiosk app above a `singleTask` root | The activities above the root are **destroyed** (`singleTask` implies `CLEAR_TOP`). Expected from Android's rules, *not tested*. |
| **Another app's task**: Settings, a payment app, any allowed app | **The kiosk app is brought in front of it** |
| A system dialog over the kiosk app (Internet dialog) | The dialog stays. The kiosk app only gets pause/resume. |

## Requirements

### MDM configuration

| What | Why |
|---|---|
| `com.android.settings` in the configuration's applications, not marked *remove*, icon hidden | Every WiFi intent goes through it. Without it the start is blocked silently. |
| **Reboot** the terminal after any change to the applications list | The allowlist is set only when kiosk mode starts |
| Android user restrictions in the configuration's `restrictions` field (comma-separated; the launcher applies them as device owner), for example `no_factory_reset`, `no_debugging_features`, `no_install_unknown_sources`, `no_config_tethering`, `no_add_user` | Allowing Settings allows all of it: **Mostra tutte** and the gears lead there. The restrictions limit what the user can do once there. The exact list is still to be decided. |
| Trim the system apps in the configuration | Each one that isn't marked *remove* is allowed in kiosk mode |
| `kiosk_restart_delay_ms` = 0 | Delayed starts pile up and are never cancelled |

### Kiosk app

| What | Why |
|---|---|
| Open WiFi with `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`, not `ACTION_WIFI_SETTINGS` | The user stays in the app. No Settings homepage, and nothing is lost on a push or at boot. |
| Before starting it, check `DevicePolicyManager.isLockTaskPermitted("com.android.settings")` while in lock task mode, and show a message if it isn't permitted | A blocked start is silent: without the check the app waits forever |
| Detect the connection with a `ConnectivityManager` network callback, not by polling | No endless loop when the user closes the dialog without changing anything |
| Tolerate `onPause`, `onNewIntent` and `onResume` at any time, not only after a user action | The launcher triggers them at every push and at boot |
| Don't use `singleTask` or `singleInstance` for the root activity if the app opens other activities of its own | A repeated start would destroy them |
| Use the startup gate (`awaitStartupUpdate()`, see [Startup sync](startup-sync.md)) | Without it the app is usable while the launcher is still updating apps, and an update of the app kills it. `srvmooney` was usable for about 12 s before the startup update ended. |

```java
private static final String SETTINGS_PACKAGE = "com.android.settings";

/** Opens the system Internet dialog over the app; returns false if kiosk mode doesn't allow it. */
boolean openWifiPicker(Activity activity) {
    ActivityManager am = activity.getSystemService(ActivityManager.class);
    DevicePolicyManager dpm = activity.getSystemService(DevicePolicyManager.class);
    boolean lockTask = am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    if (lockTask && !dpm.isLockTaskPermitted(SETTINGS_PACKAGE)) {
        // Blocked by kiosk mode: com.android.settings is missing from the MDM configuration
        return false;
    }
    activity.startActivity(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
    return true;
}
```

`isLockTaskPermitted()` can be called by any app.

## Proposal: launcher change

*Proposal, not implemented. To be agreed before any change.*

The kiosk app can't protect another app's screen from the launcher's repeated starts: an external activity (a payment app, for example) has no dialog alternative. The fix belongs in the launcher.

In `ProUtils.startCosuKioskMode()`, when lock task mode is already running:

1. Refresh the allowlist (`setLockTaskPackages`) and the lock task options. A configuration change to the allowed apps would then take effect at the push, without a reboot.
2. **Skip `startActivity()` if the kiosk app's task exists**, whatever is in front of it.
3. Keep `startActivity()` for the first start, and when the kiosk app's process or task is gone.

Edge cases to cover:

| Case | Expected behavior |
|---|---|
| Kiosk app crashed or killed (task gone) | Start it again (point 3) |
| Kiosk app changed in the configuration | Start the new one. Lock task mode must accept the new package before it starts. |
| Kiosk app updated by the launcher (install kills it) | Start it again after the install |
| Kiosk unlock from the launcher (admin) and back to kiosk | Start it again: lock task mode starts again |
| Kiosk mode turned off in the configuration | Unchanged (`unlockKiosk()`) |
| Network error dialog in the launcher (`networkErrorWifiClicked`) | Starts the kiosk app directly. To be reviewed. |

## Open points

- What **Mostra tutte** and the network gears open in kiosk mode, and which restrictions are enough to make it safe.
- Confirm from a kiosk app build that uses `ACTION_INTERNET_CONNECTIVITY`: dialog shown with Settings allowed, blocked without it.
- `Settings.Panel.ACTION_WIFI` on a push: covered or not.
- The launcher change above.
