# Release Notes v1.6.2

Google Drive Sync v1.6.2 has been released.

## Key Improvements

### 1. Improved Sync Resumption & Progress Reporting
- Implemented a feature to periodically save the `Page Token` during the file listing phase, allowing synchronization to resume from where it left off instead of starting over.
- Enhanced user experience by displaying real-time progress while fetching the Google Drive file list.

### 2. Network Resilience & Retry Mechanism
- Introduced a retry mechanism with `Exponential Backoff` for network timeouts or temporary server errors, increasing the probability of successful synchronization even in unstable network environments.

### 3. Optimized Google Workspace File Handling
- Improved handling of non-downloadable files like Google Docs and Sheets by applying dedicated export logic or pre-filtering unsupported types to prevent unnecessary errors.
# Google Drive Sync v1.6.3 Release Notes

This version focuses on fixing synchronization errors and improving user convenience.

## Key Changes

### 1. Bug Fix: EISDIR Error Resolution
Continuing from previous improvements, we've completely resolved the `EISDIR (Is a directory)` error that occurred when **existing tracked folders** had their modification times changed. Metadata updates for existing folders are now handled reliably.

### 2. Feature: Real-time Sync Log Updates
We've improved the logging screen to show updates immediately. You no longer need to exit and re-enter the screen to see the latest logs; you can now **monitor sync progress in real-time**.

---

### Other Improvements
- Improved testability and readability of the sync engine
- Enhanced initialization logs in `SyncManager`

## Bug Fixes

### 6. Enhanced Conflict Resolution & Logging
- Improved conflict resolution by logging detailed MD5 hash mismatches for easier root cause analysis.
- Enhanced debugging efficiency by recording detailed error messages and stack traces during synchronization exceptions.

### 7. File Filtering & Type Safety
- Added a feature to exclude local-only files (containing `_local` in the filename) from synchronization.
- Strengthened type safety checks to prevent incorrect sync attempts involving mismatches between local files and Drive folders.

## Technical Achievements
- Improved test readability and management efficiency by introducing custom test metadata annotations and building an automated JUnit XML report enhancement process.
- Strengthened fine-grained exception handling for various Android-specific scenarios, such as `GoogleAuthIOException`.
