# Google Drive Sync v1.6.7 Release Notes

This version focuses on adding local folder creation and improving storage access compatibility for Android 10.

## Key Changes

### 1. Local Folder Creation
- Added a feature to create new folders directly in local storage within the app.
- This allows for more flexible configuration of sync targets.

### 2. Android 10 (API 29) Compatibility
- Added support for legacy external storage access on Android 10 to resolve Scoped Storage compatibility issues.

### 3. Sync Exclusion Stabilization
- Refined the sync exclusion filtering logic and strengthened related test cases.
- Added Git metadata paths (`.git/`, `.gitignore`, `.gitattributes`, `.gitmodules`, `.gitkeep`) to default exclusions.
- These Git paths are excluded not only from local scans, but also from server-side (Drive) change processing.

### 4. Version Bump
- Bumped app version to `1.6.7` (`versionCode 32`).
