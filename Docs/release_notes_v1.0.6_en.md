# Release Notes v1.0.6

Google Drive Sync v1.0.6 is now released. This update focuses on improving synchronization efficiency and ensuring the stability of the build pipeline.

## Key Changes

### Sync Engine Improvements (Fixed Redundant Sync)

- **Optimized Change Detection:** Introduced individual timestamp comparison per file to solve the issue where files without actual changes were being re-synced redundantly.
- **Improved Metadata Accuracy:** Accurately records server-side modification times returned by Google Drive API to prevent malfunctions caused by clock skew between devices.

### CI/CD (GitHub Action) Stability

- **Pipeline Robustness:** Fixed an issue where the entire pipeline would halt if the test result upload step failed, ensuring a more reliable release process.
- **Optimized Release Notes Generation:** Improved the tag-based release note merging logic to ensure release descriptions are correctly included.

## Technical Enhancements

- Added unit tests in `SyncManager.kt` to verify the redundant sync prevention.
- Updated version extraction logic in GitHub Action workflows for better reliability.
