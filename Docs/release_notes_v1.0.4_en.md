# Release Notes v1.0.4

Google Drive Sync v1.0.4 has been released! This update focuses on resolving synchronization errors related to specific file types and sizes, further improving stability.

## Key Improvements

### 1. Enhanced Sync Stability and Bug Fixes

- **Improved 0-byte File Handling (Fixes 416 Error)**: Resolved the "416 Requested range not satisfiable" error that occurred when syncing 0-byte files. Empty files are now synchronized correctly without any errors.
- **Google Workspace File Support (Fixes 403 Error)**: Added a feature to automatically export Google Docs, Sheets, and Slides—which have download restrictions—into Office formats (Docx, Xlsx, Pptx) or PDF. This resolves the "403 Forbidden" error and enables synchronization for all file types.

### 2. Testing and Quality Assurance

- **Added Drive API Helper Tests**: Introduced unit tests for the core module responsible for Google Drive API integration, ensuring the continued accuracy of synchronization logic during future updates.

---
© 2026 Google Drive Sync Team
