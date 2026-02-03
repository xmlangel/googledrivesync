# Release Notes v1.4.2

Google Drive Sync v1.4.2 has been released. This update focuses on user interface improvements, enhanced code quality and test coverage, and improved resilience to network errors.

## Key Improvements

### 1. User Interface (UI) and Convenience
- Modified the app termination behavior to move to the background instead of closing immediately.
- Added an explicit 'Exit' button in the top-right corner of the app.
- Introduced a confirmation dialog when the exit button is clicked to prevent accidental closure.

### 2. Code Quality and Testing
- Integrated JaCoCo for unit test coverage measurement.
- Achieved 100% test coverage for utility classes including `AppVersionUtil`, `FileUtils`, and `SyncLogger`.
- Added unit tests for the `GoogleAccount` data model and related components.

### 3. Synchronization Stability
- Enhanced resilience by ensuring that individual file errors (e.g., DNS resolution failures) are logged without aborting the entire synchronization session.
- Refined network connection error handling for improved reliability during sync operations.

## Technical Changes
- Performed project-wide refactoring and test optimization to enhance code quality.
- Added documentation for generating JaCoCo reports to improve developer experience.
