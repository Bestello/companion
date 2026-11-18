# make ios-sim | ios-device | ios-testflight | android | test
# Signing team and device go in local.mk (git-ignored):  TEAM = XXXXXXXXXX   DEVICE = iPhone
# ios-testflight also needs an App Store Connect API key there:  ASC_KEY_ID = …  ASC_ISSUER = …  ASC_KEY_PATH = …/AuthKey_….p8
-include local.mk

SIM    ?= iPhone 17e
DEVICE ?= iPhone
APP_ID  = tools.fmc.companion
XCB     = xcodebuild -project ios/FMCCompanion.xcodeproj -scheme FMCCompanion -configuration Debug -quiet

.PHONY: ios-sim ios-device ios-testflight android android-release test

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

# Release archive, uploaded to App Store Connect; it shows in TestFlight once Apple has processed it.
# ponytail: the build number is the time (yymmdd.HHMM), so it always grows and nothing gets bumped by hand
ARCHIVE  = build/ios-archive
BUILD_NO = $(shell date +%y%m%d.%H%M)
ASC_AUTH = -allowProvisioningUpdates -authenticationKeyPath "$(ASC_KEY_PATH)" -authenticationKeyID $(ASC_KEY_ID) -authenticationKeyIssuerID $(ASC_ISSUER)
ios-testflight:
	@test -n "$(TEAM)" || { echo "set TEAM in local.mk"; exit 1; }
	@test -n "$(ASC_KEY_ID)" -a -n "$(ASC_ISSUER)" -a -f "$(ASC_KEY_PATH)" || { echo "set ASC_KEY_ID, ASC_ISSUER and ASC_KEY_PATH in local.mk (App Store Connect > Users and Access > Integrations)"; exit 1; }
	rm -rf $(ARCHIVE)
	xcodebuild -project ios/FMCCompanion.xcodeproj -scheme FMCCompanion -configuration Release -quiet \
		-destination 'generic/platform=iOS' -archivePath $(ARCHIVE)/FMC.xcarchive $(ASC_AUTH) \
		DEVELOPMENT_TEAM=$(TEAM) CODE_SIGN_STYLE=Automatic CURRENT_PROJECT_VERSION=$(BUILD_NO) archive
	printf '%s' '<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>method</key><string>app-store-connect</string><key>destination</key><string>upload</string><key>teamID</key><string>$(TEAM)</string><key>manageAppVersionAndBuildNumber</key><false/></dict></plist>' > $(ARCHIVE)/export.plist
	xcodebuild -exportArchive -archivePath $(ARCHIVE)/FMC.xcarchive -exportOptionsPlist $(ARCHIVE)/export.plist \
		-exportPath $(ARCHIVE)/out $(ASC_AUTH)
	@echo "uploaded build $(BUILD_NO); TestFlight lists it after processing (usually 5-15 min)"
