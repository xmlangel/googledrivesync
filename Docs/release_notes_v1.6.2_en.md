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

### 4. Differential Sync (Changes API) & Local Optimization
- **Changes API Integration**: Integrated Google Drive's `Changes API` to efficiently detect server-side changes. Improved the sync engine to reflect renames and moves instantly on local storage without re-downloading files, minimizing bandwidth usage.
- **Local Rename/Move Optimization**: Added detection for local renames and moves using MD5 matching. These changes are now reflected on the server using metadata updates instead of full re-uploads.

### 5. Improved Stability on App Exit
- Enhanced the app to cancel active background sync tasks and properly stop real-time folder monitoring when the user completely exits the app, preventing resource leaks.
## Bug Fixes

### 1. Fixed EISDIR (Is a directory) Error
- Resolved an `EISDIR` error that occurred when the sync engine attempted to upload a directory as a file. The engine now correctly identifies and creates new local directories on Google Drive.

### 6. Enhanced Conflict Resolution & Logging
- Improved conflict resolution by logging detailed MD5 hash mismatches for easier root cause analysis.
- Enhanced debugging efficiency by recording detailed error messages and stack traces during synchronization exceptions.

### 7. File Filtering & Type Safety
- Added a feature to exclude local-only files (containing `_local` in the filename) from synchronization.
- Strengthened type safety checks to prevent incorrect sync attempts involving mismatches between local files and Drive folders.

## Technical Achievements
- Improved test readability and management efficiency by introducing custom test metadata annotations and building an automated JUnit XML report enhancement process.
- Strengthened fine-grained exception handling for various Android-specific scenarios, such as `GoogleAuthIOException`.
