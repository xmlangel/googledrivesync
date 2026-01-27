# Release Notes v1.2.0

Version 1.2.0 is a minor update that introduces MD5 checksum support for improved sync accuracy and adds detection for file moves and renames.

## Key Changes

### Enhanced Sync Engine

- **MD5 Checksum Based Sync:** Ensures perfect file integrity by comparing MD5 checksums in addition to file sizes.
- **Move and Rename Detection:** Detects file moves or renames on both Google Drive and local storage, performing sync without redundant uploads or downloads.
- **Size-Based Optimization:** Improves sync analysis speed for large files and minimizes unnecessary data transfers.

### User Convenience and Safety

- **Upload Confirmation Dialog:** Adds a confirmation dialog before uploading files to prevent unintended data changes.
- **Default Sync Direction:** Changed the default sync direction to 'Download Only' to safeguard against data loss.
- **Detailed Sync Status:** Provides detailed messages during sync to show exactly what action (upload, download, move, etc.) is being performed.

### Tools and Automation

- **Deployment Automation Script:** Added `deploy_apk.sh` to quickly install and verify built APKs on connected devices.
- **Version Management Script:** Added `number_up.sh` for easy management of Major, Minor, and Build versions.

## Technical Improvements

- **Database Schema Update:** Updated `SyncItemEntity` to include a column for storing MD5 checksums.
- **Log Standardization:** Prefixed error logs with `[ERROR]` for easier troubleshooting.
