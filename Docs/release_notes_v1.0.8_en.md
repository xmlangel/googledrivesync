# Release Notes v1.0.8

v1.0.8 introduces 'File Linking' logic to completely eliminate redundant syncs in existing file scenarios.

## Key Changes

### Introduction of File Linking Logic

- **Data-free Matching:** If a file exists on both the local device and Google Drive with matching sizes and near-identical timestamps (within 2s), they are now linked in the database without any data transfer.
- **Optimized Initial Sync:** Even with 800+ files, they will be matched quickly on the first run and skipped in subsequent runs.

### Enhanced Sync Consistency and Stability

- **Size-based Change Detection:** Improved accuracy by adding file size checks to the synchronization criteria.
- **Database Consistency Fix:** Resolved an issue where the exact size of Drive files was not being correctly recorded in the database.

## Technical Enhancements

- Increased the timestamp comparison threshold from 1s to 2s for better compatibility across various filesystems.
