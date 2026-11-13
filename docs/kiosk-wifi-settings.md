# WiFi settings from a kiosk app on Sunmi terminals

How a kiosk app lets the user choose a WiFi network on a Sunmi terminal in kiosk mode: which intent to use, what the MDM configuration must allow, and what the MDM launcher does to the screen the user is on. It ends with the [requirements](#requirements) for the configuration and for the kiosk app, and the [launcher fix in 6.57/6.58](#launcher-fix-657658) for the repeated starts of the kiosk app.

Everything below was measured on a Sunmi terminal (Android 14, 1920×1200 landscape) with `it.mooney.srvmooney` 1.0.6 as kiosk app, on 2026-11-13, unless marked *not tested*: the problem with launcher 6.56, the fix with launchers 6.57 and 6.58 (branch `dev/kiosk-no-restart` of the launcher repository, not merged yet).

## Context

On Sunmi terminals the MDM launcher (`com.hmdm.launcher`) is device owner and HOME app. With kiosk mode on, it starts the configuration's main app (the *kiosk app*) and puts the device in **lock task mode**: only the packages in the lock task allowlist can be on screen.

- **The allowlist** is every application in the configuration that isn't marked *remove*, plus the kiosk app and the launcher (`ProUtils.getAllowedApps()`). System apps count too: the default configuration has about 75 packages, including `com.android.chrome`, `com.android.launcher3`, `com.android.packageinstaller` and `com.android.vending`.
- **Up to launcher 6.56, the allowlist is set only when lock task mode starts**, in practice at boot (`ProUtils.startCosuKioskMode()`). A configuration push doesn't refresh it while kiosk mode is running, so a change to the allowed apps needs a **reboot**. **From 6.57 the launcher refreshes it at every configuration push**: no reboot needed.
- **A start outside the allowlist fails silently.** `startActivity()` doesn't throw. The system logs `Attempted Lock Task Mode violation` with `startResult 101`, and nothing appears.

## The problem

`srvmooney` opens the WiFi screen with `Settings.ACTION_WIFI_SETTINGS`. Three problems showed up:

1. **Without `com.android.settings` in the configuration the screen doesn't open.** The start is blocked, and the app keeps polling the WiFi state every 3 s, waiting for a change the user can't make.
2. **With Settings allowed, the user gets all of Settings.** On this wide screen Settings opens its two-pane homepage (`DeepLinkHomepageActivity`) with WiFi on the right. The Settings menu is visible right away, inside kiosk mode.
3. **Up to launcher 6.56, a configuration push hides the WiFi screen.** At every push, and twice at every boot, the launcher starts the kiosk app again (see [Repeated starts of the kiosk app](#repeated-starts-of-the-kiosk-app)). Settings runs in its own task, so the kiosk app's task is brought in front of it. The WiFi screen disappears while the user is using it, with no warning. **Fixed in 6.57**: the page stays on screen.

## The intents compared

| Intent | What the user gets | Allowlist needs `com.android.settings` | A config push, launcher ≤ 6.56 | A config push, launcher 6.57+ |
|---|---|---|---|---|
| `Settings.ACTION_WIFI_SETTINGS` | Full Settings in a separate task. On a wide screen: the two-pane homepage, with the menu visible. | Yes | **Covers it** with the kiosk app | Stays on screen (measured) |
| `Settings.Panel.ACTION_WIFI` | `SettingsPanelActivity`: a WiFi sheet in a separate task, with an **IMPOSTAZIONI** button and a gear per network | Yes | Not tested. It's a separate task, so it would most likely be covered too. | Stays (not tested: same case as the full page) |
| **`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`** | `SettingsPanelActivity` closes immediately and hands off to a system dialog (`SunmiInternetDialog`). **The kiosk app stays the top activity**, with the dialog as a window over it. **Mostra tutte**, a gear per network, and **Fine**, which goes back to the app. | **Yes** (measured: blocked without it) | **Survives**: the kiosk app only gets pause/resume | Survives, and the kiosk app gets nothing |

`ACTION_INTERNET_CONNECTIVITY` is the only intent that keeps the user inside the kiosk app, and the only one that also works with launchers up to 6.56. The panel intents need API 29 or later. With launcher 6.57+ the full page is no longer covered, but it still exposes all of Settings.

All three still go through the `com.android.settings` package. `SettingsPanelActivity` is declared `singleInstance`, so it always starts a new task, and in lock task mode a new task must belong to an allowed package. **Mostra tutte**, **IMPOSTAZIONI** and the network gears open Settings screens (*not tested*: what they show in kiosk mode).

## Measurements

### Settings not allowed: blocked (17:05)

| Time | Event |
|---|---|
| 17:05:28.896 | `srvmooney` starts `WIFI_SETTINGS` → `Attempted Lock Task Mode violation … Settings$WifiSettingsActivity`, `startResult 101` |
| from 17:05:31 | The page in `srvmooney` logs `getWiFiInfo_step4 EX: WIFI_GETSTATUS still connected` every 3 s |

### Settings allowed, full page covered by a push (launcher 6.56, 17:23)

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

### Settings allowed, full page stays after a push (launcher 6.57, 18:46)

| Time | Event |
|---|---|
| 18:46:56.80 | Launcher: `Got Push Message, type configUpdated` |
| 18:46:58.05 | Launcher: `Showing content from setActions()` |
| 18:46:58.16 | Launcher: `Kiosk mode running, launcher in background: it.mooney.srvmooney not restarted` |
| after | Top activity still `Settings$WifiSettingsActivity`. No `wm_task_to_front`, no `wm_new_intent`, Settings not paused. |

### Internet dialog survives a push (launcher 6.56, 17:36)

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

Up to launcher 6.56, the launcher starts the kiosk app with `startActivity()` every time it shows its content (`MainActivity.showContent()` → `ProUtils.startCosuKioskMode()`), even when kiosk mode is already running and the kiosk app is already on screen. Launcher 6.57 stops doing that while the kiosk app is in front (see [Launcher fix](#launcher-fix-657658)).

| Trigger | Starts the kiosk app, ≤ 6.56 | Starts the kiosk app, 6.57+ |
|---|---|---|
| Boot | 2 times (sometimes 3): when the launcher starts, and at the end of its configuration update. The second start can come **while the user is already using the app** (one boot: a touch, then the start 3 s later). | 1 time. 2–3 times when the launcher is recreated at boot (see [Residual starts at boot](#residual-starts-at-boot)), all within the first 2–4 seconds. The start at the end of the configuration update is skipped in every boot. |
| Configuration push from the server (`configUpdated`) | Once, even with nothing changed and nothing to install | No |
| Launcher's own updates: MQTT ping check, periodic workers, an app's sync request (`forceConfigUpdate()`) | No | No |
| Launcher's `MainActivity` recreated by a configuration change (orientation, locale, SIM, font scale) | Yes | Yes, if the launcher is in front at that moment |
| Kiosk app task gone: crash, force-stop, update, the app closes itself | Yes (needed) | Yes (needed) |
| Launcher updated (Android stops it to replace it, and lock task mode ends with it) | Yes: the kiosk app restarts from scratch | Same: unavoidable |

With `kiosk_restart_delay_ms` set to a value other than 0, every start is delayed. Up to 6.56 none is ever cancelled. From 6.57 each one is checked when it fires and skipped if the kiosk app is in front. Keep it at 0 anyway: it also delays the starts that are needed, for example after a crash.

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
| Launcher ≤ 6.56: **reboot** the terminal after any change to the applications list | The allowlist is set only when kiosk mode starts. From 6.57 a push is enough. |
| Android user restrictions in the configuration's `restrictions` field (comma-separated; the launcher applies them as device owner), for example `no_factory_reset`, `no_debugging_features`, `no_install_unknown_sources`, `no_config_tethering`, `no_add_user` | Allowing Settings allows all of it: **Mostra tutte** and the gears lead there. The restrictions limit what the user can do once there. The exact list is still to be decided. |
| Trim the system apps in the configuration | Each one that isn't marked *remove* is allowed in kiosk mode |
| `kiosk_restart_delay_ms` = 0 | Up to 6.56 delayed starts pile up and are never cancelled. From 6.57 they are harmless, but the delay also slows the starts that are needed. |
| Launcher 6.58 or later | No restart of the kiosk app at pushes, allowlist refreshed at pushes, kiosk app changes applied without a reboot |

### Kiosk app

| What | Why |
|---|---|
| Open WiFi with `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`, not `ACTION_WIFI_SETTINGS` | The user stays in the app. No Settings homepage, and nothing is lost on a push or at boot. |
| Before starting it, check `DevicePolicyManager.isLockTaskPermitted("com.android.settings")` while in lock task mode, and show a message if it isn't permitted | A blocked start is silent: without the check the app waits forever |
| Detect the connection with a `ConnectivityManager` network callback, not by polling | No endless loop when the user closes the dialog without changing anything |
| Tolerate `onPause`, `onNewIntent` and `onResume` at any time, not only after a user action | Up to 6.56 the launcher triggers them at every push and at boot. From 6.57 only at boot (first seconds) and when the launcher is recreated, but older launchers are still in the field. |
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

## Launcher fix (6.57/6.58)

The kiosk app can't protect another app's screen from the launcher's repeated starts: an external activity (a payment app, for example) has no dialog alternative. So the fix is in the launcher, in `MainActivity.startCosuKioskModeChecked()`, the method every kiosk start goes through (boot, push, install, recreation, delayed start). It only touches `MainActivity`, not the `ProUtils` flavors.

### What changed

**6.57.** While lock task mode is running:

1. The launcher refreshes the allowlist (`setLockTaskPackages`) and the lock task options from the current configuration. Changes to the allowed apps take effect at the push, without a reboot.
2. **If the launcher is in the background and the configured kiosk app is the one this process already started, the kiosk app isn't started again.** The log shows `Kiosk mode running, launcher in background: <package> not restarted`.

The launcher being in the background means that the kiosk app, or an app it opened, is in front. If the kiosk app's task is gone (crash, force-stop, update, the app closes itself), lock task mode falls back to the launcher's task. The launcher is then in the foreground, and its `onResume()` starts the kiosk app as before.

**6.58.** When the kiosk app changes in the configuration while lock task mode is running, the old kiosk app's task would stay in lock task mode if the old app is still in the configuration. It would come back in front when the new one is moved to the back, and the launcher wouldn't start the new one any more. So before starting the new kiosk app, the launcher removes the old package from the allowlist for a moment, and Android finishes its locked tasks (`clear-task-all`). The log shows `Kiosk app changed: closed the tasks of the previous kiosk app <package>`. Then the configuration's allowlist is set again.

### Tests

On the Sunmi terminal, kiosk app `it.mooney.srvmooney` 1.0.6 or the Sunmi Sample App:

| # | Test | Result |
|---|---|---|
| 1 | Reboot loop, 10 rounds (6.57) | 10/10 PASS. The start at the end of the configuration update is skipped in every round. 1 start in 3 rounds, 2–3 starts in the 7 rounds with the launcher recreated at boot. |
| 2 | Push with the kiosk app in front | No start, no lifecycle callback in the kiosk app |
| 3 | Push with the full WiFi Settings page open | The page stays on screen |
| 4 | Kiosk app crash (`am crash`) / force-stop | The launcher starts it again after 0.33 s / 0.2 s |
| 5 | Kiosk app changed by push, both directions (6.58) | The new kiosk app starts without a reboot. The old one's task is closed: only the launcher and the new kiosk app are left in lock task mode. |
| 6 | `com.android.settings` added by push, no reboot | In the allowlist right after the push |
| 7 | Kiosk app updated by the launcher at boot (Sample App 1.0 → 1.2) | The install kills the app, which was waiting at the startup gate. The launcher starts the new version, which opens the gate and becomes operative. Android stops the package twice during the replace, so the first restart is lost and the second one works. |
| 8 | `kiosk_restart_delay_ms` = 10000 and a push | The delayed start fires 10 s later and is skipped |

### Residual starts at boot

About 2 s after the launcher starts, the system sends a configuration change (screen size and layout) that doesn't come from the launcher. If it arrives before the first start of the kiosk app, while the launcher is still in front, Android recreates the launcher's `MainActivity`:

- The recreated activity starts the kiosk app again, 1–2 s after the first start, while the kiosk app is still loading.
- `startLockTask()` called during the recreation can fail (`IllegalArgumentException: Invalid task, not in foreground`). The kiosk retry then brings the launcher to front and starts the kiosk app again (`Kiosk mode not started (lock task not started), bringing launcher to front, attempt n/5`). With a kiosk app that comes to front quickly, the retry can fail again a few times, because on Android 14 `startLockTask()` is applied after the kiosk app's task has already moved in front. The attempt counter belongs to the activity instance, so after a recreation two instances can retry in parallel.

The end state is always correct (kiosk app in front, lock task LOCKED). The fix would be for the launcher's `MainActivity` to handle `screenSize|screenLayout` itself (`configChanges`), so it isn't recreated. That's still to be proposed and tested.

### Notes for testing

- **A launcher update always restarts the kiosk app.** Android stops the device owner app to replace it, and lock task mode ends with it.
- **A failed install blocks later downloads of the same URL.** In background updates the launcher doesn't download again an APK whose last install failed (`Skip download due to previous install failure`, `ConfigUpdater.canDownload()`). The failure is remembered by the file's URL. If an APK was registered under the wrong application on the server, upload it again with a different file name. A sync requested by an app (`forceConfigUpdate()`) ignores this rule.
- **A failed download is not remembered**: the launcher tries again at the next update.

## Open points

- What **Mostra tutte** and the network gears open in kiosk mode, and which restrictions are enough to make it safe.
- Confirm from a kiosk app build that uses `ACTION_INTERNET_CONNECTIVITY`: dialog shown with Settings allowed, blocked without it.
- `Settings.Panel.ACTION_WIFI` on a push with launcher ≤ 6.56: covered or not.
- The launcher not being recreated at boot (`configChanges`), see [Residual starts at boot](#residual-starts-at-boot).
- Merging the branch `dev/kiosk-no-restart` (6.58) into the launcher's `master`.
