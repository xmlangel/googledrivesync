# Release Notes v1.1.0

Version v1.1.0 is a minor update that introduces Obsidian app integration and UI improvements.

## Key Changes

### Obsidian Integration

- **New Launch Icon:** Added an icon to the top-right of the dashboard to instantly launch the Obsidian app.
- **Improved App Detection:** Resolved package visibility restrictions on Android 11+ to accurately detect the installed Obsidian app.
- **Custom Icon Styling:** Implemented a tailored Obsidian icon with a transparent background for better UI harmony.

### UI and Usability Enhancements

- **Dynamic Version Display:** Refactored the dashboard title to fetch the version name dynamically from the system, ensuring consistent accuracy.
- **Optimized Icon Spacing:** Adjusted the layout of top bar action icons for a more compact and organized appearance.

### Build and Versioning

- **Version Upgrade:** Upgraded to minor version v1.1.0 and updated the build number.

## Technical Improvements

- Added `<queries>` element to `AndroidManifest.xml` to manage external app launch visibility.
- Utilized Compose's `LocalContext` and `PackageManager` for dynamic rendering of app metadata.
