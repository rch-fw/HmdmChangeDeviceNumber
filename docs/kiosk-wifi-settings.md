# WiFi settings from a kiosk app on Sunmi terminals

How a kiosk app lets the user choose a WiFi network on a Sunmi terminal in kiosk mode: which intent to use, what the MDM configuration must allow, and what the MDM launcher does to the screen the user is on. It ends with the [requirements](#requirements) for the configuration and for the kiosk app, and the [launcher fix in 6.57–6.60](#launcher-fix-657660) for the repeated starts of the kiosk app.

Everything below was measured on a Sunmi terminal (Android 14, 1920×1200 landscape) with `it.mooney.srvmooney` 1.0.6 as kiosk app, on 2026-11-13 and 2026-11-16, unless marked *not tested*: the problem with launcher 6.56, the fix with launchers 6.57 to 6.60 (branch `dev/kiosk-no-restart` of the launcher repository, not merged yet).

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

Up to launcher 6.56, the launcher starts the kiosk app with `startActivity()` every time it shows its content (`MainActivity.showContent()` → `ProUtils.startCosuKioskMode()`), even when kiosk mode is already running and the kiosk app is already on screen. Launcher 6.57 stops doing that while the kiosk app is in front, and 6.59/6.60 remove the extra starts at boot (see [Launcher fix](#launcher-fix-657660)).

| Trigger | Starts the kiosk app, ≤ 6.56 | Starts the kiosk app, 6.57+ |
|---|---|---|
| Boot | 2 times (sometimes 3): when the launcher starts, and at the end of its configuration update. The second start can come **while the user is already using the app** (one boot: a touch, then the start 3 s later). | 6.57/6.58: 1 time, 2–3 times when the launcher is recreated at boot or `startLockTask()` fails, all within the first 2–4 seconds. **6.60: 1 time** in every boot (see [Starts at boot](#starts-at-boot-659660)). From 6.57 the start at the end of the configuration update is skipped in every boot. |
| Configuration push from the server (`configUpdated`) | Once, even with nothing changed and nothing to install | No |
| Launcher's own updates: MQTT ping check, periodic workers, an app's sync request (`forceConfigUpdate()`) | No | No |
| Launcher's `MainActivity` recreated by a configuration change | Yes | Yes, if the launcher is in front at that moment. From 6.59 screen size, layout and orientation changes no longer recreate it in kiosk mode; locale, SIM and font scale still do. |
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
| Launcher 6.60 or later | No restart of the kiosk app at pushes, allowlist refreshed at pushes, kiosk app changes applied without a reboot, a single start of the kiosk app at boot |

### Kiosk app

| What | Why |
|---|---|
| Open WiFi with `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`, not `ACTION_WIFI_SETTINGS` | The user stays in the app. No Settings homepage, and nothing is lost on a push or at boot. |
| Before starting it, check `DevicePolicyManager.isLockTaskPermitted("com.android.settings")` while in lock task mode, and show a message if it isn't permitted | A blocked start is silent: without the check the app waits forever |
| Detect the connection with a `ConnectivityManager` network callback, not by polling | No endless loop when the user closes the dialog without changing anything |
| Tolerate `onPause`, `onNewIntent` and `onResume` at any time, not only after a user action | Up to 6.56 the launcher triggers them at every push and at boot. 6.57/6.58 only at boot (first seconds) and when the launcher is recreated. 6.60 no longer does at boot, but older launchers are still in the field. |
| Handle the boot configuration change: declare `configChanges="orientation|screenSize|smallestScreenSize|screenLayout"` on the activities, or tolerate being recreated | About 2 s after boot the system sends a screen size and layout change. If the kiosk app is already in front, Android recreates it (the Sunmi Sample App was recreated in 2 boots out of 10 with launcher 6.60). The launcher can't prevent that. |
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

## Launcher fix (6.57–6.60)

The kiosk app can't protect another app's screen from the launcher's repeated starts: an external activity (a payment app, for example) has no dialog alternative. So the fix is in the launcher, in `MainActivity.startCosuKioskModeChecked()`, the method every kiosk start goes through (boot, push, install, recreation, delayed start). It only touches `MainActivity`, not the `ProUtils` flavors.

### What changed

**6.57.** While lock task mode is running:

1. The launcher refreshes the allowlist (`setLockTaskPackages`) and the lock task options from the current configuration. Changes to the allowed apps take effect at the push, without a reboot.
2. **If the launcher is in the background and the configured kiosk app is the one this process already started, the kiosk app isn't started again.** The log shows `Kiosk mode running, launcher in background: <package> not restarted`.

The launcher being in the background means that the kiosk app, or an app it opened, is in front. If the kiosk app's task is gone (crash, force-stop, update, the app closes itself), lock task mode falls back to the launcher's task. The launcher is then in the foreground, and its `onResume()` starts the kiosk app as before.

**6.58.** When the kiosk app changes in the configuration while lock task mode is running, the old kiosk app's task would stay in lock task mode if the old app is still in the configuration. It would come back in front when the new one is moved to the back, and the launcher wouldn't start the new one any more. So before starting the new kiosk app, the launcher removes the old package from the allowlist for a moment, and Android finishes its locked tasks (`clear-task-all`). The log shows `Kiosk app changed: closed the tasks of the previous kiosk app <package>`. Then the configuration's allowlist is set again.

**6.59.** About 2 s after the launcher starts at boot, the system sends a configuration change (screen size and layout, `536872192`) that doesn't come from the launcher. If it arrived while the launcher was in front, before the first start of the kiosk app, Android recreated the launcher's `MainActivity`, which had no `configChanges`: the new instance started the kiosk app again, `startLockTask()` from the activity being recreated failed (`Invalid task, not in foreground`), and the destroyed instance's kiosk retry, still posted on the handler, ran in parallel with the new instance's retries. 6.59:

1. declares `configChanges="orientation|screenSize|smallestScreenSize|screenLayout"` on `MainActivity`. In `onConfigurationChanged()` the launcher recreates itself, as Android did before, unless kiosk mode runs with an external kiosk app: then it shows no app grid and has nothing to redo. The desktop still recomputes its grid for the new width;
2. removes the pending kiosk check in `onDestroy()`, so only the live instance checks and retries.

**6.60.** `ProUtils.startCosuKioskMode()` calls `startLockTask()` and right after it `startActivity()` of the kiosk app. On Android 14 `startLockTask()` is a one-way call: the system could start the kiosk app and move its task in front first, then fail the lock task request (`Invalid task, not in foreground`), and the kiosk check retried 1 s later with a second start (3 boots out of 10 on 6.59). At the first start (lock task mode not running, launcher device owner and in front), 6.60 refreshes the allowlist and the options, calls `startLockTask()` from the launcher, checks every 50 ms until lock task mode is locked (at most 2 s), and only then starts the kiosk app. The log shows `Lock task mode started in N ms, starting <package>`. If it isn't locked within 2 s, or `startLockTask()` throws (before Android 12), the previous path runs, with its check and retries.

### Tests

On the Sunmi terminal, kiosk app `it.mooney.srvmooney` 1.0.6 or the Sunmi Sample App:

| # | Test | Result |
|---|---|---|
| 1 | Reboot loop, 10 rounds | 6.57: 10/10 PASS, 1 start in 3 rounds. 6.59: 10/10 PASS, 1 start in 7 rounds. **6.60: 10/10 PASS with the Sample App and 10/10 PASS with `srvmooney`, 1 start in all 20 rounds.** See [Starts at boot](#starts-at-boot-659660). |
| 2 | Push with the kiosk app in front (6.57, 6.60) | No start, no lifecycle callback in the kiosk app |
| 3 | Push with the full WiFi Settings page open | The page stays on screen |
| 4 | Kiosk app crash (`am crash`) / force-stop (6.57, 6.60) | The launcher starts it again after 0.15–0.33 s |
| 5 | Kiosk app changed by push, both directions (6.58, 6.60) | The new kiosk app starts without a reboot. The old one's task is closed: only the launcher and the new kiosk app are left in lock task mode. |
| 6 | `com.android.settings` added by push, no reboot | In the allowlist right after the push |
| 7 | Kiosk app updated by the launcher at boot (Sample App 1.0 → 1.2) | The install kills the app, which was waiting at the startup gate. The launcher starts the new version, which opens the gate and becomes operative. Android stops the package twice during the replace, so the first restart is lost and the second one works. |
| 8 | `kiosk_restart_delay_ms` = 10000 and a push | The delayed start fires 10 s later and is skipped |

Tests 3, 6, 7 and 8 ran on 6.57/6.58. 6.59 and 6.60 only change the first start at boot and the launcher's recreation, which those tests don't depend on.

### Starts at boot (6.59/6.60)

Reboot loops of 10 rounds on the Sunmi terminal; each round reboots and captures the log until the startup update ends:

| | 6.57 (`srvmooney`) | 6.59 (Sample App) | 6.60 (Sample App) | 6.60 (`srvmooney`) |
|---|---|---|---|---|
| PASS | 10/10 | 10/10 | 10/10 | 10/10 |
| Rounds with exactly 1 start | 3 | 7 | **10** | **10** |
| Start at the end of the configuration update | skipped | skipped | skipped | skipped |
| Launcher recreated at boot | 7 | 0 | 0 | 0 |
| `Invalid task` + kiosk retry | 3 | 3 | 0 | 0 |
| Wait for lock task mode | — | — | 446–992 ms (avg 764) | 497–870 ms (avg 694) |
| First start of the kiosk app after the launcher | ~2 s | +1.7–3.3 s | +1.9–3.3 s (avg 2.7) | +1.9–2.6 s |

With 6.60 the kiosk app appears about 0.6 s later than with 6.59 (the time lock task mode takes), while the launcher's start screen stays visible. The 2 s fallback never triggered.

The same boot configuration change still reaches the kiosk app when it is already in front: the Sample App, which doesn't declare `configChanges`, was recreated in 2 rounds out of 10 with 6.60. That's for the kiosk app to handle (see [Kiosk app](#kiosk-app)).

### Notes for testing

- **A launcher update always restarts the kiosk app.** Android stops the device owner app to replace it, and lock task mode ends with it.
- **A failed install blocks later downloads of the same URL.** In background updates the launcher doesn't download again an APK whose last install failed (`Skip download due to previous install failure`, `ConfigUpdater.canDownload()`). The failure is remembered by the file's URL. If an APK was registered under the wrong application on the server, upload it again with a different file name. A sync requested by an app (`forceConfigUpdate()`) ignores this rule.
- **A failed download is not remembered**: the launcher tries again at the next update.

## Open points

- What **Mostra tutte** and the network gears open in kiosk mode, and which restrictions are enough to make it safe.
- Confirm from a kiosk app build that uses `ACTION_INTERNET_CONNECTIVITY`: dialog shown with Settings allowed, blocked without it.
- `Settings.Panel.ACTION_WIFI` on a push with launcher ≤ 6.56: covered or not.
- Merging the branch `dev/kiosk-no-restart` (6.60) into the launcher's `master`.
