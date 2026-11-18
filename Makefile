# make ios-sim | ios-device | android | test
# Signing team and device go in local.mk (git-ignored):  TEAM = XXXXXXXXXX   DEVICE = iPhone
-include local.mk

SIM    ?= iPhone 17e
DEVICE ?= iPhone
APP_ID  = tools.fmc.companion
XCB     = xcodebuild -project ios/FMCCompanion.xcodeproj -scheme FMCCompanion -configuration Debug -quiet

.PHONY: ios-sim ios-device android android-release test

ios-sim:
	$(XCB) -destination 'platform=iOS Simulator,name=$(SIM)' -derivedDataPath build/ios-sim build
	xcrun simctl boot '$(SIM)' 2>/dev/null || true
	open -a Simulator 2>/dev/null || open "$$(xcode-select -p)/../Applications/DeviceHub.app"
	xcrun simctl install '$(SIM)' "build/ios-sim/Build/Products/Debug-iphonesimulator/FMC Companion.app"
	xcrun simctl launch '$(SIM)' $(APP_ID)

ios-device:
	@test -n "$(TEAM)" || { echo "set TEAM in local.mk (Apple Developer team ID)"; exit 1; }
	$(XCB) -destination 'platform=iOS,name=$(DEVICE)' -derivedDataPath build/ios-device \
		-allowProvisioningUpdates DEVELOPMENT_TEAM=$(TEAM) CODE_SIGN_STYLE=Automatic build
	xcrun devicectl device install app --device '$(DEVICE)' "build/ios-device/Build/Products/Debug-iphoneos/FMC Companion.app"
	xcrun devicectl device process launch --device '$(DEVICE)' $(APP_ID)

# needs JDK 17, ANDROID_HOME and a device or emulator in adb
android:
	cd android && ./gradlew installDebug
	adb shell am start -n $(APP_ID)/.MainActivity

# signed with android/keystore.properties; bump versionCode in android/app/build.gradle.kts first
CODE = $$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' android/app/build.gradle.kts)
NAME = $$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' android/app/build.gradle.kts)
android-release:
	@test -f android/keystore.properties || { echo "android/keystore.properties is missing (see build.gradle.kts)"; exit 1; }
	cd android && ./gradlew assembleRelease
	mkdir -p build && cp android/app/build/outputs/apk/release/app-release.apk build/fmc-companion-$(CODE).apk
	gh release create android-$(NAME) build/fmc-companion-$(CODE).apk --title "Android $(NAME)" --notes ""

test:
	swift test --package-path ios/WireKit
	cd android && ./gradlew testDebugUnitTest
