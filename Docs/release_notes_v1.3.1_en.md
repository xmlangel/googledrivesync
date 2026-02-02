# Release Notes v1.3.1

Google Drive Sync v1.3.1 has been released. This update introduces advanced synchronization features that significantly improve sync performance in large-scale file environments and enhance data integrity.

## Key Improvements

### 1. Introduction of Differential Sync (Changes API)
- Integrated the Google Drive Changes API to track only server-side changes since the last sync.
- Completes sync scans in milliseconds even for large folders with thousands of files, minimizing system resource consumption.
- Efficiently manages network traffic through incremental update capabilities.

### 2. MD5-based Content Verification and Optimization
- Determines whether content has actually changed by comparing MD5 checksums in addition to file size and modification time.
- Maximizes transfer efficiency by preventing unnecessary uploads and downloads caused by time zone differences or minor timestamp drifts.
- Increased sync reliability through data integrity verification.

### 3. Enhanced Auto-upload Functionality
- Added an auto-upload option to immediately sync with Google Drive when a new file is created or an existing file is modified locally.
- Users can choose to enable or disable auto-upload in the settings screen.

## Fixes and Enhancements
- Improved logic to immediately reflect server-side file moves or deletions in the local file storage and database.
- Enhanced reliability for automatically recovering locally deleted files from the server.
- Optimized sync history management to record past operations more accurately.

---
© 2026 Google Drive Sync Team
