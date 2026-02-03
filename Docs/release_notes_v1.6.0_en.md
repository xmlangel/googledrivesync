# Release Notes v1.6.0

Google Drive Sync v1.6.0 has been released. This version focuses on improving the usability of the sync engine and strengthening exception handling, with significant improvements in stability during large-scale file listing and response to network instability.

## Key Improvements

### 1. Improved Sync Resumption & Progress Reporting
- Implemented a feature to periodically save the `Page Token` during the file listing phase, allowing synchronization to resume from where it left off instead of starting over.
- Enhanced user experience by displaying real-time progress while fetching the Google Drive file list.

### 2. Network Resilience & Retry Mechanism
- Introduced a retry mechanism with `Exponential Backoff` for network timeouts or temporary server errors, increasing the probability of successful synchronization even in unstable network environments.

### 3. Optimized Google Workspace File Handling
- Improved handling of non-downloadable files like Google Docs and Sheets by applying dedicated export logic or pre-filtering unsupported types to prevent unnecessary errors.

### 4. Enhanced Conflict Resolution & Logging
- Improved conflict resolution by logging detailed MD5 hash mismatches for easier root cause analysis.
- Enhanced debugging efficiency by recording detailed error messages and stack traces during synchronization exceptions.

### 5. File Filtering & Type Safety
- Added a feature to exclude local-only files (containing `_local` in the filename) from synchronization.
- Strengthened type safety checks to prevent incorrect sync attempts involving mismatches between local files and Drive folders.

## Technical Achievements
- Improved test readability and management efficiency by introducing custom test metadata annotations and building an automated JUnit XML report enhancement process.
- Strengthened fine-grained exception handling for various Android-specific scenarios, such as `GoogleAuthIOException`.
