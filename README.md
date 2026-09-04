# mdmlib

Android library port of `change_dev_number.py`. Renames a device in a
[Headwind MDM](https://h-mdm.com/) server and pushes a `configUpdated` notification.

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
JSONObject device = client.fetchDevice(token, "old_device_name");
client.updateDeviceNumber(token, device, "old_device_name", "new_device_name");
client.notifyConfigUpdated(token, "new_device_name");
```

## Mapping to the Python script

| Python function            | Java equivalent                              |
|----------------------------|----------------------------------------------|
| `get_md5_uppercase`        | `HeadwindMdmClient.md5Uppercase`             |
| `authenticate`             | `HeadwindMdmClient.authenticate`             |
| `fetch_device`             | `HeadwindMdmClient.fetchDevice`              |
| `update_device_number`     | `HeadwindMdmClient.updateDeviceNumber`       |
| `notify_config_updated`    | `HeadwindMdmClient.notifyConfigUpdated`      |
| `main`                     | `HeadwindMdmClient.changeDeviceNumber`       |

## Notes

- The device object is kept as a `JSONObject` and sent back to the server intact,
  so no fields are lost on the round-trip (same behaviour as the script).
- `info` is re-serialised to a JSON string before the PUT when the server returns
  it as an object/array.
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
