#!/bin/bash

# Increment Build Version Script
# Usage:
#   ./number_up.sh ma (Major)
#   ./number_up.sh mj (Minor)
#   ./number_up.sh build (Patch)
#   ./number_up.sh (Interactive)

BUILD_GRADLE="app/build.gradle"

if [ ! -f "$BUILD_GRADLE" ]; then
    echo "Error: $BUILD_GRADLE not found!"
    exit 1
fi

# Detect OS (macOS vs others)
OS_TYPE=$(uname)

# 1. Extract current versions
CURRENT_CODE=$(grep "versionCode" "$BUILD_GRADLE" | head -1 | awk '{print $2}')
CURRENT_NAME=$(grep "versionName" "$BUILD_GRADLE" | head -1 | awk -F'"' '{print $2}')

if [ -z "$CURRENT_CODE" ] || [ -z "$CURRENT_NAME" ]; then
    echo "Error: Could not parse versions from $BUILD_GRADLE"
    exit 1
fi

echo "Current versionCode: $CURRENT_CODE"
echo "Current versionName: $CURRENT_NAME"

# 2. Increment versionCode (Every run)
NEW_CODE=$((CURRENT_CODE + 1))

# 3. Handle versionName increment
MODE=$1

if [ -z "$MODE" ]; then
    echo ""
    echo "Select version increment type:"
    echo "1) Major (ma) - Change X in X.Y.Z"
    echo "2) Minor (mj) - Change Y in X.Y.Z"
    echo "3) Build (build) - Change Z in X.Y.Z"
    echo "4) Skip versionName update"
    read -p "Choice [1-4]: " CHOICE
    case $CHOICE in
        1) MODE="ma" ;;
        2) MODE="mj" ;;
        3) MODE="build" ;;
        4) MODE="skip" ;;
        *) echo "Invalid choice. Skipping versionName update."; MODE="skip" ;;
    esac
fi

# Parse versionName (e.g., 1.1.3)
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_NAME"

case $MODE in
    ma)
        MAJOR=$((MAJOR + 1))
        MINOR=0
        PATCH=0
        ;;
    mj)
        MINOR=$((MINOR + 1))
        PATCH=0
        ;;
    build)
        PATCH=$((PATCH + 1))
        ;;
    skip)
        # Keep current versionName
        ;;
    *)
        echo "Unknown mode: $MODE. Skipping versionName update."
        ;;
esac

NEW_NAME="$MAJOR.$MINOR.$PATCH"

echo "New versionCode: $NEW_CODE"
if [ "$MODE" != "skip" ]; then
    echo "New versionName: $NEW_NAME"
else
    NEW_NAME=$CURRENT_NAME
    echo "New versionName: $NEW_NAME (no change)"
fi

# 4. Update build.gradle
if [ "$OS_TYPE" == "Darwin" ]; then
    # macOS sed
    sed -i '' "s/versionCode $CURRENT_CODE/versionCode $NEW_CODE/" "$BUILD_GRADLE"
    sed -i '' "s/versionName \"$CURRENT_NAME\"/versionName \"$NEW_NAME\"/" "$BUILD_GRADLE"
else
    # Linux sed
    sed -i "s/versionCode $CURRENT_CODE/versionCode $NEW_CODE/" "$BUILD_GRADLE"
    sed -i "s/versionName \"$CURRENT_NAME\"/versionName \"$NEW_NAME\"/" "$BUILD_GRADLE"
fi

echo ""
echo "Successfully updated $BUILD_GRADLE!"
