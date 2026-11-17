# Boot and enrollment flow of the MDM launcher

How the Headwind MDM launcher (fork `rch-group/mooney-mdm-agent`, `master`, 6.60) enrolls a terminal, becomes the home app, and starts at every boot, on Sunmi and on Ingenico terminals.

## Who does what

| Component | Role |
|---|---|
| `MainActivity` | Does the work: reads `init.json`, chooses the device ID, makes the launcher the home app, starts the services and the configuration update, starts the kiosk app. |
| `BootReceiver` | Safety net at boot: does the work itself only when `MainActivity` hasn't run in this boot. |
| `AdminReceiver.startEnrollmentIfNeeded()` | Opens `MainActivity` when the firmware has made the launcher device owner but nothing else opens it (Sunmi first enrollment). Fork only, not in upstream. |
| `SetupCompleteWorker` | Waits for the setup wizard to finish before enrollment starts. Fork only. |
| `PushNotificationWorker` | Hourly fallback: forces a configuration update when the last one is more than 1 hour old. |

## Enrollment settings (`init.json`)

`MainActivity.onCreate()` reads the enrollment settings with `AdminReceiver.updateSettingsFromFile()` when the device has no device ID yet. Two places, the first one found wins:

1. `init.json` in the launcher's external files directory (`Android/data/<package>/files/`), copied there by the Sunmi firmware;
2. `init.json` in the APK assets (the `ingenico` flavor embeds one).

The keys are `com.hmdm.BASE_URL`, `com.hmdm.SERVER_PROJECT`, `com.hmdm.CONFIG`, `com.hmdm.GROUP` and `com.hmdm.DEVICE_ID_USE` (`serial`: the device ID is the serial number; on Ingenico it is read through USDK, see `UsdkSerialNumber`).

Only `MainActivity` reads `init.json`: until it runs, the launcher has no server URL and isn't enrolled.

## How the launcher becomes the home app

Declaring `HOME` in the manifest only makes the launcher a candidate: on Sunmi the stock `com.android.launcher3` is a home app too, and with two candidates Android shows the chooser or keeps the system default.

The launcher becomes the **default** home app by setting a device owner policy, `DevicePolicyManager.addPersistentPreferredActivity()` for the `HOME` filter (`Utils.setDefaultLauncher()`). The user can't change it, and it persists across reboots. It is set:

- at the first start, by `MainActivity.onResume()` → `setDefaultLauncherEarly()`, when the launcher is device owner and has no configuration yet (`SET_DEFAULT_LAUNCHER_EARLY`, true in `master`), so the user can't leave with Home during the enrollment;
- at every configuration update, by `ConfigUpdater.setDefaultLauncher()`: with **Run default launcher** (`runDefaultLauncher`) off it sets the launcher as home, with it on it clears the policy (`clearPackagePersistentPreferredActivities()`).

Kiosk mode depends on the launcher being home: Android opens it at boot and whenever the kiosk app closes or crashes, and the launcher then starts the kiosk app again. With `runDefaultLauncher` on, the kiosk app isn't started (see [kiosk mode and the home app](#kiosk-mode-and-the-home-app)).

## Sunmi: first enrollment

The Sunmi firmware (CRP) makes the launcher device owner and copies `init.json` into its external files directory. Nothing opens `MainActivity`, and the launcher isn't the home app yet, so the fork starts it:

1. **The firmware sets the device owner**: `AdminReceiver.onEnabled()` → `startEnrollmentIfNeeded()`.
2. `startEnrollmentIfNeeded()` returns if the server URL is already set or the launcher isn't device owner. If the setup wizard is still running (`user_setup_complete` = 0) it schedules `SetupCompleteWorker` and returns: opening `MainActivity` now would interfere with the wizard.
3. **The wizard completes**: the job scheduler starts `SetupCompleteWorker` (content URI trigger on `user_setup_complete`, also if the process was frozen or killed), which calls `startEnrollmentIfNeeded()` again. `App.onCreate()` checks the same at every process start, in case WorkManager dropped the trigger.
4. `startEnrollmentIfNeeded()` starts `MainActivity` (log: `Device owner set by the firmware, starting MainActivity to enroll the device`).
5. `MainActivity.onCreate()` reads `init.json` (server URL, configuration, group) and runs `Initializer.init()`.
6. `MainActivity.onResume()` → `setDefaultLauncherEarly()`: the launcher **becomes the home app**.
7. `startLauncher()` chooses the device ID (serial number) and starts the first configuration update, with the progress screen; then installs the apps and, in kiosk mode, starts the kiosk app.

If the terminal reboots before step 4, `BootReceiver` calls `startEnrollmentIfNeeded()` again at the next boot (no server URL yet).

### The setup wizard

The setup wizard is the series of screens Android shows at the first boot after a factory reset, before the home screen: language, WiFi, date and time, terms, Google account. It is also called OOBE (out-of-box experience).

While it runs, the wizard is registered as a home app with a high priority, so it is what Android shows at boot. When the user completes it, it sets `Settings.Secure.user_setup_complete` (and `Settings.Global.device_provisioned`) to 1 and disables its own components, and the normal home apps take over.

On the Sunmi terminal tested (LE15P5CS00214) there are two wizard packages, `com.google.android.setupwizard` and `com.tinno.setupwizard` (Tinno is the hardware maker of this model). After the setup both have their components disabled, and the home app candidates are `com.android.launcher3`, the Headwind launcher and `com.android.settings/.FallbackHome`.

With Sunmi CRP the firmware makes the launcher device owner at the first boot, while the wizard is still on screen. Opening `MainActivity` at that moment would cover or interrupt the wizard, and `setDefaultLauncherEarly()` would make the launcher the home app in the middle of the setup. So `startEnrollmentIfNeeded()` checks `user_setup_complete`:

- 0: it schedules `SetupCompleteWorker` with a content URI trigger on the setting, and does nothing else (log: `Setup wizard is running, enrollment will start when it completes`);
- 1: it starts `MainActivity` (log: `Device owner set by the firmware, starting MainActivity to enroll the device`).

When the user completes the wizard, the setting changes, the job scheduler runs the worker (log: `SetupCompleteWorker: user_setup_complete changed`), and the worker calls `startEnrollmentIfNeeded()`, which now starts `MainActivity`. If the setup is still not complete, the worker is scheduled again. Commit `a05db82`; the enrollment loop test script (`7d38530`, factory reset → wizard → enrollment → kiosk app) tests the whole sequence.

Check on a device:

```
adb shell settings get secure user_setup_complete
adb shell settings get global device_provisioned
adb shell pm list packages | grep -i setupwizard
```

## Sunmi: every boot after enrollment

1. Android opens the home app, `MainActivity`, usually before `BOOT_COMPLETED` is delivered.
2. `MainActivity.onCreate()` saves the start time (`setAppStartTime()`), runs `Initializer.init()` and starts the services; `startLauncher()` shows the saved configuration and starts the configuration update in the background (the startup update, see [Startup sync](startup-sync.md)); in kiosk mode `showContent()` → `startKiosk()` starts the kiosk app.
3. `BOOT_COMPLETED` arrives: `BootReceiver` sees the server URL is set and the start time is later than the boot time, logs `Headwind MDM is already started, ignoring BootReceiver` and does nothing.

### Sunmi boot sequence

Captured on the Sunmi terminal LE15P5CS00214 (launcher 6.60-master, kiosk mode, Sunmi Sample App as the kiosk app, no app marked *Run at boot*, no app to install in that boot):

| Time | Event | Log |
|---|---|---|
| 15:30:07.3 | Boot screen: Android shows `com.android.settings/.FallbackHome` while the user is still locked | `wm_set_resumed_activity … FallbackHome` |
| 15:30:10.8–11.3 | User unlocked | `am_user_state_changed`, `uc_finish_user_unlocked` |
| 15:30:13.724 | **The launcher's process starts as the home app** | `am_proc_start … com.hmdm.launcher,top-activity,{…MainActivity}` |
| 15:30:15.012 | `Initializer.init()` | `MDM Launcher 6.60-master started` |
| 15:30:15.111 | **Startup update** begins; orientation locked during the update | `Updating configuration in startLauncher()`, `Lock orientation: …` |
| 15:30:16.034 | Lock task mode started first, then the kiosk app (6.60) | `Lock task mode started in 810 ms, starting com.mooney.mdm.sunmi` |
| 15:30:16.291 | First configuration request fails: WiFi isn't up yet | `Failed to update config: network error` |
| 15:30:16.426 | The kiosk app's process starts, in front in lock task mode | `am_proc_start … com.mooney.mdm.sunmi,next-top-activity` |
| 15:30:16.891 | The kiosk app connects and **waits at the startup gate** | `Startup gate: the agent is updating apps, waiting` |
| 15:30:24.036 | Network up | `Network is available, resuming flow` |
| 15:30:26.301 | Configuration request repeated | `updateConfig(): set configInitializing=true` |
| 15:30:28.366 | Configuration loaded; apps checked (nothing to install) | `Configuration updated` |
| 15:30:29.291 | `BOOT_COMPLETED` reaches the launcher, 15.5 s after `MainActivity` started | `appStartTime=…, bootTime=…`, `Headwind MDM is already started, ignoring BootReceiver` |
| 15:30:30.613 | `showContent()` after the update: the kiosk app isn't started again (6.57) | `Kiosk mode running, launcher in background: com.mooney.mdm.sunmi not restarted` |
| 15:30:30.709 | **Startup update over**: the gate opens | `Startup update completed` |
| 15:30:30.751 | The kiosk app starts its work | `Startup gate: open, startup update over`, `Operative` |

One start of the kiosk app, no `Invalid task`, no recreation of `MainActivity`. The kiosk app waited 14 s at the gate, mostly for the WiFi.

### Sunmi and Ingenico compared

| | Sunmi (home app, kiosk mode) | Ingenico (not home) |
|---|---|---|
| Who starts the launcher at boot | Android, as the home app, about 3 s after the user unlock | A broadcast (SIM change, `BOOT_COMPLETED`) |
| Who runs the startup update | `MainActivity.startLauncher()` | `BootReceiver` → `Initializer.startServicesAndLoadConfig()` |
| `BootReceiver` | Ignores the broadcast (`already started`) | Does the work |
| Who starts the app | Kiosk mode (`startKiosk()`), right after lock task mode | *Run at boot*, 5 s after the startup update |
| When the app starts | **Before** the startup update ends | **After** the startup update |
| Startup gate | The app **waits** until the update is over | Already open when the app connects |

So on Sunmi the kiosk app must wait at the gate (`awaitStartupUpdate()`) before it lets the user work: the launcher may still install apps, the kiosk app included, and installing an app kills it. See [Startup sync](startup-sync.md).

## Ingenico

On Ingenico terminals `com.ingenico.dxmobile` is device owner, not the launcher. So the launcher can't set itself as home, and the `ingenico` flavor removes `HOME` from `MainActivity` (`src/ingenico/AndroidManifest.xml`) to avoid the chooser. Kiosk mode of the configuration is ignored.

**First enrollment.** `startEnrollmentIfNeeded()` does nothing (the launcher isn't device owner). A launcher just installed and never run is in the Android **stopped state** and doesn't even receive `BOOT_COMPLETED`. Enrollment starts only when something opens `MainActivity`: the user, or an app through the hmdm library (`HeadwindMDM.startLauncher()`, see [Starting the MDM launcher](start-launcher.md)). `MainActivity` then reads `init.json` from the APK assets and enrolls as on Sunmi, without becoming home.

**Every boot after enrollment.** Nothing opens `MainActivity`, so `BootReceiver` does the work (see [`BootReceiver`](#bootreceiver)), and the launcher starts the apps marked **Run at boot** when its startup update is over.

### Ingenico boot sequence

Captured on the EX6000 (launcher 6.60-ingenico, Ingenico Sample App 1.3 marked *Run at boot*, no app to install in that boot):

| Time | Event | Log |
|---|---|---|
| 15:19:02.240 | The launcher's process starts for a SIM broadcast, before `BOOT_COMPLETED`; `App.onCreate()` reads the serial number through USDK | `am_proc_start … SimChangedReceiver`, `Reading the serial number through USDK` |
| 15:19:10.934 | `BOOT_COMPLETED`: `MainActivity` hasn't run, so `BootReceiver` initializes | `Headwind MDM wasn't started since boot, start initializing services` |
| 15:19:11.034 | Push service (MQTT) started | `Starting Push service from BootReceiver` |
| 15:19:11.051 | **Startup update** begins: configuration request | `updateConfig(): set configInitializing=true` |
| 15:19:12.520 | Configuration loaded; apps checked (nothing to install) | `Configuration updated` |
| 15:19:12.937 | **Startup update over**: the startup gate opens | `Startup update completed` |
| 15:19:18.034 | **Run at boot**, after the 5 s pause | `Run at boot: started com.mooney.mdm.ingenico` |
| 15:19:18.682 | The sample app connects and finds the gate open | `Startup gate: open, startup update over`, `Operative` |

The sample app was started only by the launcher's *Run at boot* intent (`am_create_activity` / `am_proc_start … activity`): the Ingenico home app and DxMobile didn't open it.

**The order is always the same.** `BootAutorun.start()` is called only at the end of `StartupUpdate.complete()`, once per boot (the state is saved with `BOOT_COUNT`, so a configuration push or a launcher restart later in the boot doesn't start the apps again). The startup update ends:

| End | Log | Run at boot |
|---|---|---|
| Configuration loaded, app installs and removals done | `Startup update completed` | Right after |
| 3 min timeout from the first request of the boot, no app update running | `Startup update timed out` | Right after; the remaining app updates wait for the next boot |
| Timeout during an app download or install | `Startup update timed out, remaining app updates deferred to the next boot`, or after a 20 s grace `…the current app update did not end` | When that app update ends, or after the grace |
| Configuration request failed (server or network error after the retries), launcher enrolled | `Startup update failed: …` | Right after, with the configuration of the previous boot |
| Not enrolled yet (no configuration ever loaded) | `Startup update kept open: configuration not loaded before the enrollment` | Not yet: with the first update after the enrollment |

So on Ingenico the sequence is: startup update (configuration, downloads, installs) → gate open → 5 s → *Run at boot* starts the apps. An app started by *Run at boot* finds the gate open; an app waits on the gate during the sync only if something else opened it before the startup update ended (the user, for example). See [Startup sync](startup-sync.md) and [Ingenico Sample App](ingenico-sample-app.md).

In the same boot the terminal started with its clock about 2 minutes ahead (15:21), and the time daemon set it back at 15:19:02 (`TIME_SET`): the case described in [The start time check](#the-start-time-check). It was harmless here, because the saved start time was about 22 hours old.

## `BootReceiver`

Handles `BOOT_COMPLETED` and `QUICKBOOT_POWERON` (`receiver/BootReceiver.java`):

1. **No server URL** (not enrolled): calls `AdminReceiver.startEnrollmentIfNeeded()` and returns. This check comes before the next one, so it runs whether `MainActivity` has started or not.
2. **`MainActivity` already started in this boot** (saved start time later than the boot time): returns. This is the usual case when the launcher is home.
3. **Otherwise** (launcher not home, or `MainActivity` held back, e.g. by a setup screen): `Initializer.init()` (waits for the network, schedules the workers), then `Initializer.startServicesAndLoadConfig()`:
   - starts the push service (MQTT as a foreground service, or long polling), `StatusControlService` and the foreground app checks;
   - sends the pending logs;
   - `ConfigUpdater.forceConfigUpdate()` with no UI: no progress screen, no network error dialog; the device info is sent when the update completes.

   If kiosk mode is required it also sends a `HOME` intent, to bring `MainActivity` up.

If `startEnrollmentIfNeeded()` reaches a `MainActivity` that is already running (`singleInstance`), no second instance is created: Android pauses and resumes it, and `onResume()` runs `startLauncher()` again (the server dialog is shown again, the serial number wait is repeated, a configuration update already running is not started twice).

### The start time check

`getAppStartTime()` is 0 if `MainActivity` never ran (fresh install, data cleared). It is saved in the preferences, so after a reboot it holds the previous boot's time, earlier than the new boot time. Both values come from the wall clock (`System.currentTimeMillis()`): if the clock goes back between two boots (dead RTC battery, manual change), the receiver may wrongly ignore a boot. The fallbacks are the launcher opening as home and `PushNotificationWorker`. A boolean flag wouldn't work: saved, it survives the reboot and nothing can reliably clear it (a crash or power loss skips `ShutdownReceiver`; `BootReceiver` runs after `MainActivity`). Comparing `Settings.Global.BOOT_COUNT`, as `StartupUpdate` already does, would remove the clock issue.

## Configuration sync triggers

| Trigger | When |
|---|---|
| `MainActivity.startLauncher()` → `updateConfig()` | Every start of `MainActivity` (boot as home app, enrollment) |
| `BootReceiver` → `forceConfigUpdate()` | Boot, when `MainActivity` hasn't run |
| Push `configUpdated` (MQTT) | Configuration push from the server |
| `PushNotificationWorker` | Every 15 minutes it checks; forces an update if the last one is more than 1 hour old (log: `Forcing configuration update`) |
| Sync requested by an app | `HeadwindMDM` API, see [Startup sync](startup-sync.md#sync-requested-by-an-app) |

`ConfigUpdater.updateConfig()` ignores a request while another update is running (`configInitializing`). At boot a failing configuration request is retried 6 times every 10 s.

## Kiosk mode and the home app

| `runDefaultLauncher` | Home app | Kiosk app at boot and after a crash |
|---|---|---|
| off (default) | Headwind launcher | Started by `MainActivity` |
| on | Stock launcher | Not started: `MainActivity` hands over to the default launcher before the kiosk check, and `BootReceiver` brings up the home app, which is not the Headwind launcher |

Keep **Run default launcher** off for kiosk terminals.

## Checking on a device

```
adb logcat -v time | grep -E "BOOT_RECEIVER|already started, ignoring|wasn't started since boot|starting MainActivity to enroll|Setup wizard is running|user_setup_complete changed|Set Headwind MDM as default launcher|Forcing configuration update|Startup update|Run at boot"
adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN
adb shell dpm list-owners
```

## Code

- `app/src/main/java/com/hmdm/launcher/receiver/BootReceiver.java`
- `app/src/main/java/com/hmdm/launcher/AdminReceiver.java`: `onEnabled()`, `startEnrollmentIfNeeded()`, `updateSettingsFromFile()`
- `app/src/main/java/com/hmdm/launcher/worker/SetupCompleteWorker.java`, `App.java`
- `app/src/main/java/com/hmdm/launcher/helper/Initializer.java`: `init()`, `startServicesAndLoadConfig()`
- `app/src/main/java/com/hmdm/launcher/ui/MainActivity.java`: `onCreate()`, `onResume()`, `setDefaultLauncherEarly()`, `startLauncher()`
- `app/src/main/java/com/hmdm/launcher/helper/ConfigUpdater.java`: `setDefaultLauncher()`
- `app/src/main/java/com/hmdm/launcher/util/Utils.java`: `setDefaultLauncher()`, `clearDefaultLauncher()`
- `app/src/main/java/com/hmdm/launcher/worker/PushNotificationWorker.java`
- Fork commits: `29a1c55` (enrollment when the firmware sets the device owner), `a05db82` (after the setup wizard), `2de50e6` (Ingenico flavor, `init.json` in the assets), `566d721` (Ingenico: no home app)
