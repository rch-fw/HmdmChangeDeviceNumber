# mdmlib changelog

All notable changes to `mdmlib` are listed here, newest first.
The library has no version number yet, so entries are identified by date and commit.

## 2026-10-21 — `ad20dd9`

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

### Added
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

## 2026-10-19 — `493af4c`

### Added
- Prebuilt `prebuilt/mdmlib-release.aar`.

## 2026-09-02 — `47a76d1`

Initial release: Java port of `change_dev_number.py`.

### Added
- `HeadwindMdmClient`: JWT login (`authenticate`, password sent as uppercase MD5),
  then rename a device in three steps: fetch it by number, `PUT` it back with the
  new number, and send a `configUpdated` push.
- `MdmService`: async wrapper that runs calls on a background thread and delivers
  results on the main thread.
- `MdmConfig`: server URL (trailing slashes stripped), credentials and timeout.
- `MdmException`: carries the HTTP status code and response body.
