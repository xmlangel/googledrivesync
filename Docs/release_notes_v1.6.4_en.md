# Google Drive Sync v1.6.4 Release Notes

This release focuses on synchronization safety and improved folder-linking UX.

## Key Changes

### 1. Immediate stop when a sync folder is deleted during sync
- Sync now stops immediately if the target sync folder is removed while a sync is running.
- Users are informed with a clear stop reason message.

### 2. Stronger local root folder loss detection
- If the local sync root is deleted/missing during sync, the process is stopped immediately instead of continuing.

### 3. Safer default policy (recommended: download-only)
- Default behavior for newly added sync folders is aligned toward download-only safety.
- Additional protection has been added for first-time states without sync history to prevent unintended upload/delete behavior.

### 4. Better warning flow for duplicate/delete risks
- When linking to an existing Drive folder, users can now explicitly choose whether to clear local data first.
- If they proceed without clearing, a warning about potential duplicate upload/delete behavior is shown.

### 5. Folder browser enhancements
- Added in-place folder creation in the Drive folder selection screen.
- Improved decision UX for safer initial sync setup.

## Stability & Quality
- Hardened move/rename handling in sync flow
- Improved sync-related logging and repository guidance
