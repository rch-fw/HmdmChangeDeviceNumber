# hmdm library changelog

Changes to the Headwind MDM integration library (`lib/` module of the MDM launcher repository;
`app/libs/hmdm-<version>.aar` in this project), newest first.
Each version needs a launcher reporting at least the matching plugin API version
(`PluginApiService.getVersion()`).
Reference of the methods added to `HeadwindMDM`: [hmdm library](hmdm-library.md).

## 1.2.3 (API 120)

### Added
- `HeadwindMDM.startLauncher(Context)`: starts the MDM launcher's main screen, as if the user
  had opened it (its launch intent, or its HOME activity if it has none). Unlike `connect()`,
  which only binds the launcher's service, this runs the launcher's full startup: enrollment,
  configuration, push service, startup update. It also takes the launcher out of the stopped
  state. Android 10+ blocks activity starts from the background: call it while the app is
  visible. Returns `false` if no launcher is installed or the start failed.
- `HeadwindMDM.getLauncherPackage(Context)`: the installed launcher package, in the order
  `connect()` tries them, or `null`.

No AIDL or plugin API change: the activity is started by the calling app, so it works with
any launcher version.

## 1.2.2 (API 120)

### Fixed
- Apps setting an API key (`HeadwindMDM.setApiKey()`) crashed with a `NullPointerException`
  in `MDMService.queryConfig(apiKey)` when connecting to a launcher without a configuration,
  for example just installed and not enrolled yet. `queryConfig(apiKey)` now returns `null`
  like `queryConfig()`, and `HeadwindMDM` reports `MDMError.ERROR_NOT_CONFIGURED`
  (`getMdmAgentError()`); `onHeadwindMDMConnected()` is still called, with no server or
  device data. The library doesn't query the configuration again by itself: reconnect
  (`disconnect()` + `connect()`) once the launcher is enrolled.

No AIDL or plugin API change.

## 1.2.1 (API 120)

### Added
- `MDMService.connect()` also looks for the launcher of Ingenico terminals,
  package `com.hmdm.launcher.ingenico` (tried after `com.hmdm.launcher`, before the legacy
  `ru.headwind.kiosk`).
- `<queries>` for the three launcher packages in the library manifest, so apps targeting
  Android 11+ (API 30) can bind to the launcher without declaring them in their own manifest.

No AIDL or plugin API change: works with the same launchers as 1.2.0.

## 1.2.0 (API 120)

### Added
- Startup sync: `HeadwindMDM.awaitStartupUpdate(StartupGateListener)` calls `onReady()`
  when the launcher startup update (the first configuration update after boot, the only one
  which installs and removes apps) is over. It handles reconnections and a safety-net timeout
  (`STARTUP_GATE_TIMEOUT_MS`, 3 min 30 s). With a launcher older than API 120 it opens at once.
- Lower-level `MDMService.awaitStartupUpdate(ConfigUpdateListener)` and the AIDL method
  `IMdmApi.awaitStartupUpdate(IMdmApiCallback)`.
- Launcher side: app installs and removals outside the startup update are deferred to the
  next boot, unless the configuration sets `appUpdateAtStartupOnly` to `false`
  (missing key = `true`; the server doesn't send it yet).

Integration guide for app developers: [Startup sync](startup-sync.md).

## 1.1.9 (API 119)

### Added
- `MDMService.forceConfigUpdate(ConfigUpdateListener)`: force a configuration update
  and receive live progress events (`IMdmApiCallback`).
