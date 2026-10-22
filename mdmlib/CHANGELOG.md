# mdmlib changelog

All notable changes to `mdmlib` are listed here, newest first.
The version is set in `mdmlib/build.gradle` and is available at runtime as
`com.rch.mdm.BuildConfig.VERSION_NAME`.

## 2.0.0 — 2026-10-21

Needs a Headwind MDM server that provides `POST /rest/private/devices/rename`
(`hmdm_server` branch `plugin-dev`, commit `8928b5e6`). Stock Headwind servers
are not supported anymore.

### Changed
- `HeadwindMdmClient.changeDeviceNumber` now makes a single call to
  `POST /rest/private/devices/rename` instead of fetch + `PUT /devices` + push.
  The server sends the `configUpdated` push itself.
- The MDM user only needs the `rename_device` permission (previously
  `edit_devices` + `push_api`, and "All devices available").
- HTTP error messages include the full request URL.
- `prebuilt/mdmlib-release.aar` rebuilt.

### Added
- Version number (`BuildConfig.VERSION_NAME`).
- `HeadwindMdmClient.renameDevice(token, currentNumber, newNumber)`, returning the device id.

### Removed (breaking)
- `HeadwindMdmClient.fetchDevice`
- `HeadwindMdmClient.updateDeviceNumber`
- `HeadwindMdmClient.notifyConfigUpdated`

### Fixed
- Errors the server reports with HTTP 200 (`{"status":"ERROR","message":"error.xxx"}`),
  such as `error.duplicate.device` or `error.permission.denied`, now raise
  `MdmException` instead of being treated as success.
- Removed a real password from the unit tests.

## 1.0.0 — 2026-09-02

Initial release: Java port of `change_dev_number.py`. This version had no version
number in the build; a prebuilt AAR of it was added on 2026-10-19.

### Added
- `HeadwindMdmClient`: JWT login (`authenticate`, password sent as uppercase MD5),
  then rename a device in three steps: fetch it by number, `PUT` it back with the
  new number, and send a `configUpdated` push.
- `MdmService`: async wrapper that runs calls on a background thread and delivers
  results on the main thread.
- `MdmConfig`: server URL (trailing slashes stripped), credentials and timeout.
- `MdmException`: carries the HTTP status code and response body.
