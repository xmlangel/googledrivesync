# Release Notes v1.0.5

Google Drive Sync v1.0.5 has been released. This update focuses on resolving notification noise issues and enhancing the stability of synchronization tasks.

## Key Improvements

### 1. Notification System Optimization and Stability

- Resolved the "Muting recently noisy" system error that occurred during sync initiation and progress.
- Optimized notification update frequency to prevent notifications from being blocked by the Android system.
- Improved notification ID management logic for background tasks and manual sync requests to provide more accurate status information.

### 2. Enhanced Sync Task Policy (WorkManager)

- Implemented Unique Work logic to prevent multiple synchronization tasks from running simultaneously.
- Fixed logic where periodic sync tasks were unnecessarily re-registered upon app startup, reducing system resource consumption and improving stability.

## Bug Fixes

- Fixed a bug where sync completion notifications were updated multiple times in short intervals.
- Introduced automated verification tests for notification frequency to ensure future stability and prevent regression.

---
© 2026 Google Drive Sync Team
