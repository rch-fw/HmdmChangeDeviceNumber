# mdmlib

Android library port of `change_dev_number.py`. Renames a device in a
[Headwind MDM](https://h-mdm.com/) server and/or sets its groups; when the number
changes, the server pushes a `configUpdated` notification to the device.

Current version: **2.1.0** (see [`mdmlib/CHANGELOG.md`](mdmlib/CHANGELOG.md)).
At runtime it is available as `com.rch.mdm.BuildConfig.VERSION_NAME`.

Requires a server that provides `POST /rest/private/devices/rename` (the patched
`hmdm_server`). The MDM user only needs a role with the `rename_device` permission.

## Build

This repo is a standalone Gradle project (root `settings.gradle` pins the
Android Gradle Plugin). Point `local.properties` at your SDK, then:

```sh
./gradlew :mdmlib:assembleRelease      # produces mdmlib/build/outputs/aar/mdmlib-release.aar
./gradlew :mdmlib:testDebugUnitTest    # run unit tests
```

## Use from an app

**Option A – as a module.** Copy the `mdmlib/` folder into your app project and add:

```gradle
// settings.gradle
include ':mdmlib'

// app/build.gradle
dependencies { implementation project(':mdmlib') }
```

**Option B – as an AAR.** Drop `mdmlib-release.aar` into your app's `libs/` and:

```gradle
dependencies { implementation files('libs/mdmlib-release.aar') }
```

Only dependency is `org.json`, which is part of the Android platform.
Requires the `INTERNET` permission (already declared in the library manifest).

## Usage

### Async (recommended)

```java
MdmService service = new MdmService(
        new MdmConfig("mdm_server_url", "username", "password"));

service.changeDeviceNumber("old_device_name", "new_device_name", new MdmService.Callback<Long>() {
    @Override public void onSuccess(Long deviceId) {
        Log.i("MDM", "Renamed device id " + deviceId);
    }
    @Override public void onError(MdmException e) {
        Log.e("MDM", "Failed (HTTP " + e.getStatusCode() + "): " + e.getResponseBody(), e);
    }
});

// service.shutdown();  // when done
```

### Synchronous (call off the main thread)

```java
HeadwindMdmClient client = new HeadwindMdmClient(
        new MdmConfig("mdm_server_url", "username", "password"));

long id = client.changeDeviceNumber("old_device_name", "new_device_name");
```

Or drive the individual steps:

```java
String token = client.authenticate();
long id = client.renameDevice(token, "old_device_name", "new_device_name");
```

### Setting groups

Pass group names (as shown in the web panel). The list replaces all current groups
of the device; `null` keeps them, an empty list removes the device from all groups.
`newNumber` may be `null` to change only the groups.

```java
// rename and set groups in one server transaction
client.changeDevice("old_device_name", "new_device_name", Arrays.asList("Shop A", "Kiosk"));

// only set groups
client.changeDevice("device_name", null, Arrays.asList("Shop A"));
```

To release app updates one area at a time using these groups, see the MDM operator
guide [Staged configuration rollout by area](docs/staged-rollout-by-area.md).

`MdmService.changeDevice(...)` is the async equivalent.

## Mapping to the Python script

| Python function            | Java equivalent                              |
|----------------------------|----------------------------------------------|
| `get_md5_uppercase`        | `HeadwindMdmClient.md5Uppercase`             |
| `authenticate`             | `HeadwindMdmClient.authenticate`             |
| `rename_device`            | `HeadwindMdmClient.updateDevice`             |
| `main`                     | `HeadwindMdmClient.changeDevice`             |

## Notes

- The server changes only the device number (it keeps the original number as
  `oldNumber` until the device syncs) and, if given, the groups; configuration and
  other fields are not touched.
- Server-side errors (`error.notfound.device`, `error.duplicate.device`,
  `error.notfound.group`, `error.permission.denied`) are raised as `MdmException`; the raw server reply is
  in `getResponseBody()`.
- Do not hard-code credentials in production; pass them from secure storage.
- If your server uses a self-signed certificate you must configure a
  network security config / trust manager in the host app.

# Ingenico Sample App

`ingenico-app/` (`com.mooney.mdm.ingenico`) does the whole initialization of an Ingenico terminal
by itself when it is opened: if the MDM agent is not running it configures WiFi (when there is no
network), starts the launcher, which enrolls, and waits for the startup update; if the agent is
running it waits for the startup update only. Then it is operative, with **Get device number**,
**Change device number**, **Sync configuration** and **Configure WiFi**. Targets SDK 28 (WiFi with
`addNetwork`); needs launcher 6.54 or later. See [Ingenico Sample App](docs/ingenico-sample-app.md).

# Sample app

Shows how to create a demo app that uses 2 libraries:

- **mdmlib**: the library provided by this project
- **hmdm**: the library who provides API interface with mdm agent to an user android app

- hmdm library is described here : https://h-mdm.com/headwind-mdm-api/
- hmdm libray allow the interaction of an user defined app with mdm agent included
  retrieval of mdm related info like **device Id** and **mdm server url**.

The sample app also shows the **startup gate** (hmdm library 1.2.0 or later; the app ships 1.2.2; 1.2.1 and later also connect to the
Ingenico launcher package `com.hmdm.launcher.ingenico`): after boot it waits
until the MDM launcher has installed the app updates before enabling its buttons, because
installing an app kills it. See [Startup sync with the MDM launcher](docs/startup-sync.md).

### Sync configuration button

The **Sync configuration** button asks the MDM launcher to load the configuration from the server
now (`HeadwindMDM.forceConfigUpdate()`), for example after the network has been configured.

- It is shown only once the startup gate is open: during the startup update it would start a second
  configuration update in parallel (see [Startup sync](docs/startup-sync.md), rule 5).
- During the day, with **Install app updates only at device startup** checked (the default), the
  update applies settings, policies, files and remote commands, and leaves app installs and removals
  for the next boot: use **Reboot device** to install them.
- While the launcher updates the configuration, the app shows "Syncing configuration, please wait"
  and hides the buttons which need the MDM agent. It goes back to normal when the launcher reports
  the end of the update (the `com.hmdm.push.configUpdated` broadcast, `onHeadwindMDMConfigChanged()`):
  the log shows "Configuration sync completed".
- The launcher reports only successful updates: on a network error it retries 6 times every 10 s
  and gives up silently. So the app stops waiting after 90 s and logs "no answer from the launcher".
- After **Configure WiFi** with the Ingenico USDK no sync is needed: the reboot runs the startup
  update, which also installs the apps. After an Android suggestion connected, the WiFi screen offers
  this sync. On a new device, with the launcher not enrolled yet, the sync fails: use **Start MDM
  launcher** instead (with launcher 6.54 the startup update then installs the apps in this boot).
  See [WiFi setup](docs/wifi-setup.md#sync-request-availability) for the sync APIs.

### Reboot button

The **Reboot device** button asks the MDM launcher to reboot the device, so the app updates
waiting for the next boot are installed. It sends a `reboot` push message through the hmdm
library (`HeadwindMDM.sendPush("reboot", null)`), which requires:

- the launcher's library API key (`LIBRARY_API_KEY` in the launcher build). It is not stored
  in this repository: add it to `local.properties` (not committed) before building:

  ```properties
  hmdm.apiKey=<LIBRARY_API_KEY of the launcher build>
  ```

  The build exposes it as `BuildConfig.HMDM_API_KEY`. Without it the app still builds, and the
  button only logs an error.
- the launcher in device owner mode: otherwise it accepts the message but doesn't reboot.

`sendPush` returning `true` means only that the launcher accepted the message; the reboot
runs in the background. Unlike a reboot from the web panel, it isn't recorded on the server.

### Start MDM launcher button

The **Start MDM launcher** button starts the MDM launcher (`com.hmdm.launcher`, or
`com.hmdm.launcher.ingenico` if that one is not installed) and
takes it out of the stopped state, for example after `adb shell am force-stop` or *Force stop*
in the system settings. A stopped app doesn't receive broadcasts and its services don't run;
Android clears the stopped state only when one of its components is started explicitly.

The button starts the launcher's main activity (`getLaunchIntentForPackage`), or its HOME
activity if it has no LAUNCHER entry. This needs the packages in the manifest's `<queries>`
(Android 11+):

```xml
<queries>
    <package android:name="com.hmdm.launcher" />
    <package android:name="com.hmdm.launcher.ingenico" />
</queries>
```

Notes:

- The button is shown only while the launcher is not running: when it is in the stopped state
  (checked when the app starts, before connecting, because binding to the launcher's service clears
  that state) or not configured yet. It is hidden once the sample app has started the launcher.
  It doesn't wait for the startup gate, which can't open while the launcher is stopped (it only
  times out).
- The sample app stays in front: the launcher's activity shows at most for a moment, and its
  enrollment and configuration update go on in the background. See
  [Starting the MDM launcher and staying in front](docs/start-launcher.md) for how it works and
  its limits (tested on Android 10; on Android 14+ the launcher may stay in front).
  This is optional: uncheck **Come back here after starting the launcher**, shown below the button,
  to leave the launcher in front. The choice is saved and kept when the app restarts (checked by
  default).
- If the launcher was not configured yet, the sample app shows "MDM launcher not configured yet":
  once the launcher is enrolled, press **Get device number** to reconnect to the MDM agent and read
  the server and device number.
- To check (Ingenico launcher): `adb shell dumpsys package com.hmdm.launcher.ingenico | grep stopped` shows
  `stopped=true` before and `stopped=false` after pressing the button.

### Configure WiFi button

The **Configure WiFi** button opens a screen to set the WiFi network without the system settings,
for example on a device fresh from the factory with no network. Choose the network from the list of
access points nearby (strongest first; **Scan** searches again) or type its name, enter the password,
and pick one of three methods (the choice is saved; the first time, Android addNetwork):

- **Android addNetwork**, **Connect** (default): the app adds the network
  (`WifiManager.addNetwork()`) and Android connects in a few seconds: no user action, no reboot, any
  signing key. Allowed on Android 10 only because the app **targets SDK 28**; whether production
  terminals accept such an app is still to be confirmed. The network belongs to the app: removed if
  the app is uninstalled. On a new device, then tap **Start MDM launcher**: with launcher 6.54 it
  enrolls and installs the apps in this boot.

- **Ingenico USDK**, **Save and reboot**: the app saves the network in the Ingenico "WiFi auto"
  settings (`UNetWorkManager.setWifiAutoSSID` / `setWifiAutoPasswd` / `setWifiAutoEnabled`) and
  reboots through the USDK (`UDeviceManager.reboot()`). At boot the system creates a saved WiFi
  network and connects; the MDM launcher's startup update then loads the configuration and installs
  the apps as usual. No user action; needs the Ingenico-approved key; the settings apply only at boot.
- **Android suggestion**, **Connect**: the app suggests the network
  (`WifiManager.addNetworkSuggestions()`); Android connects a few seconds after the user allows it in
  the notification shade, without a reboot. Any signing key. Then the screen offers **Sync
  configuration** (the launcher loads its configuration now) and **Reboot** (the startup update
  installs the apps). On Android 10 the approval is only in a silent, collapsed notification, which
  users don't find: **not usable** in practice.

Where the USDK is not available or refuses the calls, it can't be selected. See
[WiFi setup on Ingenico terminals](docs/wifi-setup.md) for a comparison of the three methods.

**Reset WiFi setup** also removes the networks the app added.

A network saved through WiFi auto stays even if the app is uninstalled, and "WiFi auto" stays on:
Ingenico's system service applies it again at every boot, recreating the network even after it is
forgotten in the settings. A suggested network is used by all apps like any WiFi connection, but the
suggestion belongs to the app: it is removed with the app. **Reset WiFi setup** turns WiFi
auto off, clears its network and password (`UsdkWifi.reset()`), and removes the app's suggestions. To
reproduce a device fresh from the factory: press **Reset WiFi setup**, then forget the network in the
settings.

- The network list needs the location permission and the location turned on (Android 10 scan
  results). The Ingenico firmware grants the permission at install, so no dialog is shown. Android
  limits scans to about 4 every 2 minutes: a refused scan keeps the last results. Networks a method
  can't use are marked "not supported": enterprise (EAP) networks, and open networks with the
  Ingenico USDK ("WiFi auto" takes a WPA/WPA2 password).
- The button is always shown, not tied to the startup gate: without a network the gate stays
  closed until the launcher gives up loading the configuration.
- The USDK accepts these calls only from apps signed with an Ingenico-approved key. The build signs
  the app with the launcher's key when `RELEASE_M10_STORE_FILE`, `RELEASE_M10_KEY_ALIAS`,
  `RELEASE_M10_STORE_PASSWORD` and `RELEASE_M10_KEY_PASSWORD` are set in `~/.gradle/gradle.properties`;
  otherwise it uses the debug key, and the screen shows that it can't read the WiFi settings.
- An app signed with a different key can't update an installed one: uninstall the old version first,
  and upload the newly signed APK to the MDM server.
- The Ingenico USDK library is proprietary and is **not in the repository** (`.gitignore`). Before
  building the sample app, copy `usdk_api_aidl_v13.12.0.20240202.jar` from `app/libs/` of the
  launcher repo (mooney-mdm-agent), or from the Ingenico USDK, to `app/libs/`. Without it the build
  stops with a message saying so. The `mdmlib` library doesn't need it.
- Code: `WifiActivity`, `UsdkWifi`. See [WiFi setup on Ingenico terminals](docs/wifi-setup.md) for
  how the methods work, when a reboot or a sync request is needed, and the tests on the EX6000.
