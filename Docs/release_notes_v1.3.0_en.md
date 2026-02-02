# Release Notes v1.3.0

Version 1.3.0 is a minor update that significantly improves handling of fatal network errors during synchronization, enhancing overall application resilience and professionalism.

## Key Changes

### Enhanced Error Handling and Resilience

- **Immediate Halt on Network Errors:** When fatal network issues like DNS failures or timeouts occur, the sync process now stops immediately instead of continuing in an inconsistent state, ensuring data safety.
- **Optimized Exception Propagation:** Refactored the internal logic to transparently propagate exceptions from Google Drive API calls, allowing for precise identification and handling of error causes.
- **Detailed Error Messages:** Provides specific, user-friendly error messages for various scenarios such as network disconnection or DNS issues, helping users understand and resolve problems easily.

### Sync Engine Stabilization

- **Improved Recursive Scanning:** Refined the logic for recursive folder scanning to ensure more accurate collection of pending uploads, resulting in consistent sync results.
- **State Management Optimization:** Strengthened internal data flows for tracking sync progress to maintain data integrity even during unexpected interruptions.

### Quality Assurance & Environment

- **Strengthened Unit Testing:** Added and updated 15 core unit tests, including network failure scenarios, to strictly verify the stability of the synchronization logic.
- **Optimized Test Configuration:** Fine-tuned the test setup to ensure Robolectric tests run reliably in environments targeting the latest Android SDKs.

## Technical Improvements

- **Error Classification Logic:** Introduced the `isFatalNetworkError` utility to clearly distinguish between transient retriable errors and fatal errors requiring a process halt.
- **Full Test Suite Passing:** Verified that all 45 tests in the integrated suite pass successfully, ensuring compatibility with existing features.
