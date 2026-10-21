# mdmlib

Android library port of `change_dev_number.py`. Renames a device in a
[Headwind MDM](https://h-mdm.com/) server; the server then pushes a `configUpdated`
notification to the device.

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

## Mapping to the Python script

| Python function            | Java equivalent                              |
|----------------------------|----------------------------------------------|
| `get_md5_uppercase`        | `HeadwindMdmClient.md5Uppercase`             |
| `authenticate`             | `HeadwindMdmClient.authenticate`             |
| `rename_device`            | `HeadwindMdmClient.renameDevice`             |
| `main`                     | `HeadwindMdmClient.changeDeviceNumber`       |

## Notes

- The server changes only the device number (it keeps the original number as
  `oldNumber` until the device syncs); groups, configuration and other fields are
  not touched.
- Server-side errors (`error.notfound.device`, `error.duplicate.device`,
  `error.permission.denied`) are raised as `MdmException`; the raw server reply is
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
