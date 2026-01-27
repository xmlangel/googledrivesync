#!/bin/bash

# APK Deployment Script
# Lists connected devices and installs the built APK to the selected device.

# Find the APK file
APK_PATH=$(find ./app/build -name "app-debug.apk" | head -n 1)

if [ -z "$APK_PATH" ]; then
    echo "❌ Error: Could not find app-debug.apk. Please build the project first."
    echo "Try: ./gradlew assembleDebug"
    exit 1
fi

# Extract Version Info from build.gradle
VERSION_NAME=$(grep "versionName" app/build.gradle | awk '{print $2}' | tr -d '"')
VERSION_CODE=$(grep "versionCode" app/build.gradle | awk '{print $2}')
PACKAGE_NAME=$(grep "applicationId" app/build.gradle | head -n 1 | awk '{print $2}' | tr -d '"')

# Check if APK is older than build.gradle
if [ "$APK_PATH" -ot "app/build.gradle" ]; then
    echo "⚠️ Warning: The APK file is older than app/build.gradle."
    echo "   It might not contain the latest changes or version updates ($VERSION_NAME)."
    echo "   Recommended: Run ./gradlew assembleDebug first."
    read -p "Do you want to proceed with the old APK anyway? (y/N): " PROCEED
    if [[ ! "$PROCEED" =~ ^[Yy]$ ]]; then
        echo "❌ Aborting. Please build the project and try again."
        exit 1
    fi
fi

echo "📦 Found APK: $APK_PATH"
echo "🔢 Project Version: $VERSION_NAME ($VERSION_CODE)"
echo "🆔 Package: $PACKAGE_NAME"

# Get list of connected devices
DEVICES=($(adb devices | grep -v "List" | grep "device$" | awk '{print $1}'))

if [ ${#DEVICES[@]} -eq 0 ]; then
    echo "❌ Error: No devices connected. Please connect a device via USB or start an emulator."
    exit 1
fi

SELECTED_DEVICE=""

if [ ${#DEVICES[@]} -eq 1 ]; then
    SELECTED_DEVICE=${DEVICES[0]}
    echo "📱 Only one device found: $SELECTED_DEVICE"
else
    echo "📱 Multiple devices found. Please select one:"
    for i in "${!DEVICES[@]}"; do
        echo "[$i] ${DEVICES[$i]}"
    done

    read -p "Select device index (0-$((${#DEVICES[@]} - 1))): " INDEX

    if [[ ! "$INDEX" =~ ^[0-9]+$ ]] || [ "$INDEX" -lt 0 ] || [ "$INDEX" -ge ${#DEVICES[@]} ]; then
        echo "❌ Invalid selection. Exiting."
        exit 1
    fi

    SELECTED_DEVICE=${DEVICES[$INDEX]}
fi

echo "🚀 Installing APK to $SELECTED_DEVICE (with -t for test-only APK)..."
adb -s "$SELECTED_DEVICE" install -r -t "$APK_PATH"

if [ $? -eq 0 ]; then
    echo "✅ adb install commanded successfully."
    
    # Verification step
    echo "🔍 Verifying installation on device..."
    INSTALLED_VERSION=$(adb -s "$SELECTED_DEVICE" shell dumpsys package "$PACKAGE_NAME" | grep "versionName" | head -n 1 | awk -F= '{print $2}' | tr -d '[:space:]')
    
    if [ "$INSTALLED_VERSION" == "$VERSION_NAME" ]; then
        echo "✨ Verification SUCCESS: $PACKAGE_NAME ($INSTALLED_VERSION) is confirmed on device."
    else
        echo "⚠️ Verification UNCERTAIN: Could not confirm version match. Manual check recommended."
        echo "   Expected: $VERSION_NAME, Found: $INSTALLED_VERSION"
    fi
else
    echo "❌ Installation failed."
    exit 1
fi
