# Release Notes v1.5.0

Google Drive Sync v1.5.0 has been released. This version consolidates the achievements of various minor updates (v1.4.1 ~ v1.4.4) released since v1.4.0, focusing on maximizing the stability and reliability of the synchronization engine.

## Key Improvements (Consolidated since v1.4.0)

### 1. Dirty Tracking & Real-time Detection
- Introduced the `Dirty Tracking` architecture that detects changes in the local file system in real-time.
- By targeting only changed files without a full scan, it significantly reduces battery consumption and API calls.

### 2. Enhanced Sync Resilience
- Improved the logic so that an error in a single file does not stop the entire synchronization process, allowing it to proceed to the next file.
- Provides detailed error logs for individual files to make troubleshooting easier.

### 3. Stabilized Coroutine & Cancellation Logic
- Refined coroutine cancellation logic to prevent abnormal termination when closing the app or canceling tasks during synchronization.
- Ensures safe resource release by correctly propagating `CancellationException`.

### 4. Advanced Conflict Resolution Strategy
- **Keep Both**: In case of a conflict, the local version is kept as a copy and the server version is downloaded, maintaining 0% data loss.
- **Manual Resolution**: If no policy is set, files are protected until the user makes a decision.
- Completed comprehensive unit testing for all conflict scenarios in the sync engine.

### 5. Availability & Performance Optimization
- **Network Diagnosis**: More clearly diagnoses and logs network issues such as DNS errors or server response delays.
- **MD5-based Skip**: Increased efficiency by skipping transfers when file contents are virtually identical, ignoring timestamp differences.

## Technical Achievements
- Significantly expanded test coverage for the core sync engine (`SyncManager`, `SyncWorker`).
- Verified operational stability in Android background-constrained environments (`WorkManager`).
