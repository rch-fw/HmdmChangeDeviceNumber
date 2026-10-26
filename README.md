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

# Sample app

Shows how to create a demo app that uses 2 libraries:

- **mdmlib**: the library provided by this project
- **hmdm**: the library who provides API interface with mdm agent to an user android app

- hmdm library is described here : https://h-mdm.com/headwind-mdm-api/
- hmdm libray allow the interaction of an user defined app with mdm agent included
  retrieval of mdm related info like **device Id** and **mdm server url**.

The sample app also shows the **startup gate** (hmdm library 1.2.0): after boot it waits
until the MDM launcher has installed the app updates before enabling its buttons, because
installing an app kills it. See [Startup sync with the MDM launcher](docs/startup-sync.md).
