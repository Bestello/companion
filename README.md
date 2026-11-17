# FMC Companion

Phone companion for the CMF Watch Pro 2 and the [FMC](https://fmc.tools) watch face store. MIT.

- `ios/`: Swift/SwiftUI app (`tools.fmc.companion`).
- `android/`: Kotlin/Compose app (`tools.fmc.companion`), same scope as iOS.

Both apps check their watch protocol against the same test vectors (`ios/WireKit/Tests/WireKitTests/vectors.json`,
from the Python host of cmf-watch-sdk). A change to a vector is a change for both apps.

## iOS

```bash
cd ios
swift test --package-path WireKit        # transport: frames, K session, install, App Loader INFO
xcodebuild -project FMCCompanion.xcodeproj -scheme FMCCompanion \
  -destination 'platform=iOS Simulator,name=iPhone 17e' build
```

`project.yml` generates the project (`xcodegen generate`); the generated `FMCCompanion.xcodeproj` is committed.

Demo mode (a toggle in the app, or launch with `-demo YES`) shows a sample watch, for App Review and the simulator.
The watch connection uses the link Nothing X already holds and the channel key K
([cmf-watch-sdk docs/abi-1.4.md](https://github.com/cmf-tech-community/cmf-watch-sdk/blob/main/docs/abi-1.4.md),
"Writing a host"): no pairing popup, Nothing X keeps working. The app only reads the watch for now.

## Android

```bash
cd android
./gradlew testDebugUnitTest   # transport: frames, K session, install, App Loader INFO, watchface upload, links
./gradlew assembleDebug
```

JDK 17 and the Android SDK (`ANDROID_HOME`). The transport is a port of WireKit in
`app/src/main/java/tools/fmc/companion/wire`; its tests read `vectors.json` and `watchface-vectors.json` from
`ios/WireKit/Tests/WireKitTests` in place, so both apps always run the same vectors.

Android 12+. Demo mode is the switch on the Watch tab; debug builds also take
`adb shell am start -n tools.fmc.companion/.MainActivity --ez demo true --ei tab 1 --ez check true`.
The watch connection is a second GATT client on the link Nothing X holds; on Android this is not yet tried on a real
watch. Watch faces need Nothing X closed first, as on iOS.
