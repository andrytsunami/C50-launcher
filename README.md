# Projector Launcher

A lightweight Android TV home screen for the SHAKACHU C50 projector.

## Target device
- Android 9 / API 28
- Minimum SDK: 28
- Application ID: `ru.andry.projectorlauncher`

## Current features
- Landscape home screen
- Large clock and date
- Automatically discovers launchable apps
- D-pad / OK remote navigation
- Shortcut to Android Settings
- No background service and no third-party runtime dependencies

## Build
GitHub Actions builds a debug APK on pushes to `main` and on manual workflow dispatch.

After a successful run, open **Actions → Build Projector Launcher → Artifacts** and download `projector-home-debug`.

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Safe installation
Install and test the APK without disabling the factory launcher. Select the new launcher as HOME only after confirming that it starts and remote navigation works. Keep the stock launcher enabled until the new one is fully tested.

<!-- rebuild after removing invalid duplicate wallpaper resource -->
