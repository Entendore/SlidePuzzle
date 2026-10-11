# Slide Puzzle (100% local Android app)

Take a photo or pick one from the gallery, then solve it as a sliding puzzle
(3x3 up to 20x20). No network permission, no cloud backup, no third-party
services — all data stays on the device.

## Build & run
    ./gradlew :app:assembleDebug
    adb install app/build/outputs/apk/debug/app-debug.apk

## Tests
    ./gradlew test

Requirements: JDK 17, Android SDK 34. First build downloads the toolchain;
the app itself never touches the network at runtime.
