# Release Notes v1.0.7

v1.0.7 focuses on improving synchronization flexibility and enhancing logging for easier troubleshooting.

## Key Changes

### Sync Logic Optimization (Precision Improvement)

- **1-second Threshold:** Applied 1-second precision for timestamp comparisons to prevent redundant syncs caused by millisecond-level discrepancies between local filesystems (e.g., FAT32/exFAT) and Google Drive servers.
- **Redundant Write Prevention:** Reinforced logic to avoid unnecessary metadata updates for truly unchanged files.

### Enhanced Debugging

- **Detailed Sync Logs:** Improved logs to include the exact reasons for sync detection, such as specific local and drive timestamp values. This allows for more accurate tracking of synchronization behavior.

## Bug Fixes

- Fixed drive modified time initialization for temporary items in `SyncManager.kt`.
