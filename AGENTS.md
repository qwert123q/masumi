# Device data safety

- Never run `connectedDebugAndroidTest`, uninstall `rs.masumi.app.dev`, clear its data, or use another install path that removes the existing target app on a user's device.
- Build and install debug updates with `tools/install-debug-preserving-data.sh`. It snapshots translation-provider settings locally and uses an in-place `adb install -r -t`.
- Device instrumentation belongs on a disposable emulator or a dedicated test package, never on the user's data-bearing app package.
- Do not print, commit, or expose provider API keys or the ignored `.device-backups/` directory.
