# hmdm library: additions to HeadwindMDM

Reference of the methods added to `com.hmdm.HeadwindMDM` on top of the upstream Headwind MDM
library (1.1.x, described at https://h-mdm.com/headwind-mdm-api/). Version history:
[hmdm library changelog](hmdm-library-changelog.md).

The library ships in this project as `app/libs/hmdm-1.2.3.aar`; its source is in the `lib/`
module of the MDM launcher repository.

| Since | Method | Purpose |
|---|---|---|
| 1.2.0 | [`awaitStartupUpdate()`](#awaitstartupupdate) | Wait until the launcher has installed the app updates of this boot |
| 1.2.3 | [`startLauncher()`](#startlauncher) | Start the launcher's main screen, i.e. its full startup |
| 1.2.3 | [`getLauncherPackage()`](#getlauncherpackage) | The installed launcher package |

Library versions and launchers:

- 1.2.1 or later connects to the three launcher packages, in this order: `com.hmdm.launcher`,
  `com.hmdm.launcher.ingenico` (Ingenico flavor), `ru.headwind.kiosk` (legacy). Its manifest
  declares them in `<queries>`, so apps targeting Android 11+ need nothing in their own manifest.
- 1.2.2 or later doesn't crash when the launcher has no configuration yet (just installed, not
  enrolled): `getMdmAgentError()` returns `MDMError.ERROR_NOT_CONFIGURED`.

---

## awaitStartupUpdate

```java
public void awaitStartupUpdate(StartupGateListener listener)
public void awaitStartupUpdate(StartupGateListener listener, long timeoutMs)
```

**Startup gate.** Calls `listener.onReady()` once the launcher's *startup update* is over.

The startup update is the first configuration update after boot. It is the only one that
installs and removes apps (unless the configuration sets `appUpdateAtStartupOnly` to `false`),
because installing an app kills it. Until `onReady()` is called, the app must not start any
operation which must not be interrupted, such as a payment.

- Call it after `connect()`, typically in the launch activity's `onCreate()` or in
  `onHeadwindMDMConnected()`.
- `onReady()` is called **exactly once** per call. The gate always opens: at the end of the
  startup update, at the timeout, or at once if there is no launcher.
- The gate survives reconnections: the launcher restarts when it updates itself, and the gate
  asks again when the connection comes back.
- The timeout is a safety net in the app. The default, `STARTUP_GATE_TIMEOUT_MS` (3 min 30 s), is
  a bit longer than the launcher's own (3 min), so normally the launcher decides. A new call
  replaces the previous listener and restarts the timeout.
- If the startup update is already over, `onReady(STARTUP_READY)` is called at once, also with a
  launcher older than plugin API 120 (no startup update support).

### Launcher side

- The launcher's 3 min timeout starts at the first request of the boot: an app calling
  `awaitStartupUpdate()`, or the launcher starting to update apps. When it expires, the waiting
  apps are released and the app updates not done yet are left for the next boot.
- **Before the enrollment** (launcher 6.54 or later), a waiting app doesn't start the launcher's
  timeout: the startup update stays open until the launcher, once enrolled, updates the apps.
  The library's own timeout still runs from the call: call `awaitStartupUpdate()` once the launcher
  has been started (see [`startLauncher()`](#startlauncher)), not before a long user step such as a
  WiFi setup.
- `awaitStartupUpdate()` doesn't start an update. If the launcher isn't running (not enrolled, or
  in the stopped state), start it with [`startLauncher()`](#startlauncher).

### StartupGateListener

All callbacks run on the main thread. Only `onReady()` must be implemented.

| Callback | When |
|---|---|
| `onWaiting()` | The startup update is in progress: show a wait screen |
| `onAppRemoving(pkg, name)` | The launcher is removing an app |
| `onAppDownloading(pkg, name)` | The launcher is downloading an app |
| `onAppInstalling(pkg, name)` | The launcher is installing an app |
| `onDownloadProgress(progress, total, current)` | Download progress, `progress` in percent |
| `onReady(reason)` | The app may start working. Called once |

### onReady reasons

| Constant | Value | Meaning |
|---|---|---|
| `STARTUP_READY` | 0 | The startup update is over (or the launcher is too old to support it) |
| `STARTUP_TIMEOUT` | 1 | The launcher didn't report the end of the startup update within `timeoutMs` |
| `STARTUP_NO_MDM` | 2 | No launcher installed, or `connect()` not called or failed |

### Example

```java
HeadwindMDM mdm = HeadwindMDM.getInstance();
mdm.connect(this, this);
mdm.awaitStartupUpdate(new HeadwindMDM.StartupGateListener() {
    @Override public void onWaiting() {
        showWaitScreen("Updating apps...");
    }

    @Override public void onAppInstalling(String pkg, String name) {
        showWaitScreen("Installing " + name);
    }

    @Override public void onReady(int reason) {
        // STARTUP_READY, STARTUP_TIMEOUT or STARTUP_NO_MDM: the app works in all cases
        startWorking();
    }
});
```

---

## startLauncher

```java
public static boolean startLauncher(Context context)
```

Starts the MDM launcher's main screen, as if the user had opened it from the home screen.

Unlike `connect()`, which only binds the launcher's service, this runs the launcher's **full
startup**: enrollment (`init.json`), configuration, push service and startup update. It also takes
the launcher out of the **stopped state** (just installed and never opened, or force-stopped), in
which Android doesn't deliver it any broadcast, not even `BOOT_COMPLETED`.

- The activity is started by the calling app, so it works with **any launcher version** (no AIDL
  or plugin API change).
- Intent: the launcher's launch intent (`LAUNCHER` category, so the launcher stays in front also in
  background mode), or its `HOME` activity if it has no launch intent, with
  `FLAG_ACTIVITY_NEW_TASK`. The launcher package is the one of
  [`getLauncherPackage()`](#getlauncherpackage).
- **Call it while the app is visible**: Android 10+ blocks activity starts from the background.
- Returns `true` if the activity was started, `false` if no launcher is installed or the start
  failed. The reason is logged with the tag `HeadwindMDM`.
- Static: it doesn't need `connect()`.

When to use it: the launcher isn't running, which happens when it isn't enrolled
(`getMdmAgentError() == MDMError.ERROR_NOT_CONFIGURED`) or was in the stopped state. That's the
case on Ingenico terminals, where the launcher is neither device owner nor the HOME app. Not
needed on Sunmi, where the launcher is device owner and HOME app: Android starts it at every boot.

### Example: starting the launcher when it is not running

Read the stopped state **before** `connect()`: binding the launcher's service clears it.

```java
String launcher = HeadwindMDM.getLauncherPackage(this);
boolean stopped = false;
if (launcher != null) {
    try {
        stopped = (getPackageManager().getApplicationInfo(launcher, 0).flags
                & ApplicationInfo.FLAG_STOPPED) != 0;
    } catch (PackageManager.NameNotFoundException e) {
        // Uninstalled in the meantime
    }
}
mdm.connect(this, this);

// In onHeadwindMDMConnected():
boolean notEnrolled = mdm.getMdmAgentError() == MDMError.ERROR_NOT_CONFIGURED;
if (notEnrolled || stopped) {
    // The launcher's screen covers the app: wait for the startup update behind it.
    // Mark the app "Run at boot" in the configuration, so the launcher brings it back.
    if (!HeadwindMDM.startLauncher(this)) {
        Log.w(TAG, "Can't start the MDM launcher");
    }
}
mdm.awaitStartupUpdate(listener);
```

The complete flow (WiFi setup, waiting in `onStop()`, reconnecting after the gate) is in the
[Ingenico Sample App](ingenico-sample-app.md) (`ingenico-app/`). The
[Sunmi Sample App](sunmi-sample-app.md) (`sunmi-app/`) shows the startup gate alone.

---

## getLauncherPackage

```java
public static String getLauncherPackage(Context context)
```

Returns the installed MDM launcher package, in the order `connect()` tries them
(`com.hmdm.launcher`, `com.hmdm.launcher.ingenico`, `ru.headwind.kiosk`), or `null` if none is
installed. Static: it doesn't need `connect()`.

Use it to check the launcher's package state before connecting, for example its stopped state
(see the example in [`startLauncher()`](#startlauncher)), or to tell the user that the MDM agent is missing.

---

## Related launcher behavior

- `forceConfigUpdate()` (upstream method): with launcher 6.55 or later it also installs and
  removes apps at once, as the startup update does, even when the startup update of this boot is
  over and `appUpdateAtStartupOnly` is set. The app asks for it, so it accepts that installing an
  app kills it. Don't call it before the launcher is enrolled (no device ID yet) or during the
  startup update (it would start a second update in parallel).
- `onHeadwindMDMConfigChanged()` is called at the end of every successful configuration update.
  Failed updates are not reported: use a timeout when waiting for it.
