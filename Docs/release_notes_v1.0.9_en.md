# Release Notes v1.0.9

v1.0.9 is a definitive optimization release that resolves the remaining cases of redundant synchronization.

## Key Changes

### Size-based Sync Optimization (Final Fix)

- **New Item Size Matching:** Even without database records, files that exist on both sides with matching sizes are now linked instantly without sync.
- **Metadata-only Updates (Swallowing):** When modification times change but file sizes remain identical (common in apps like Obsidian), the app now updates only the database timestamps instead of re-transferring data.
- **Zero Redundant Traffic:** No uploads or downloads will occur unless the actual file content (size) has changed.

### UI Improvements

- Updated the TopAppBar version display to `v1.0.9`.

## Technical Enhancements

- Overcame the limitations of timestamp-based change detection with robust size-based matching based on real-world log analysis.
