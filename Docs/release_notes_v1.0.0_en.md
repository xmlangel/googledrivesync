# Release Notes v1.0.0 (Initial Release)

We are thrilled to announce the first official release of **Google Drive Sync**! This version includes all the core features for seamless data synchronization between your Android device and Google Drive.

## 🚀 Key Features

### 1. Bidirectional Synchronization

- Automatically detects and syncs changes between local and Google Drive folders.
- Keeps your data up-to-date by comparing file modification timestamps.

### 2. Multi-Account Support

- Connect and manage multiple Google accounts within the app.
- Assign different accounts to different synchronization pairs (Folder Pairs).

### 3. Flexible Conflict Resolution

- Choose from three intuitive options when both local and remote files have changed:
  - **Use Local**: Overwrites Drive with the local file.
  - **Use Drive**: Overwrites the local file with the Drive version.
  - **Keep Both**: Preserves both versions by renaming the local file.

### 4. Automatic Background Sync

- Performs periodic background synchronization using WorkManager even when the app is closed.
- **Configurable Constraints**: Save data and battery by setting sync to run only on Wi-Fi or while charging.

### 5. Detailed Sync Logs & History

- Track every sync operation's success, failure, and skipped items through detailed logs.

## 🛠 Architecture & Internal Improvements

- **Jetpack Compose**: Modern UI framework for a sleek and responsive user experience.
- **Room Persistence**: Secure and efficient storage for local configurations and sync states.
- **CI/CD Integration**: Automated build system via GitHub Actions for improved development workflow.

## 📦 Installation

- Download and install the `app-debug.apk` built by GitHub Actions, available as an artifact or on the release page.

---
© 2026 Google Drive Sync Team
