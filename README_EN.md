# Google Drive Sync (English)

[한국어 버전 (README.md)](README.md)

An Android application for bidirectional synchronization between local folders and Google Drive. Features include multiple sync pairs, conflict resolution, and background sync using WorkManager.

![Google Drive Sync](drivesync.png)

## Features

- **Bidirectional Sync**: Keeps your local and Google Drive folders in perfect harmony.
- **Multiple Sync Pairs**: Configure multiple folders to sync with different Google Drive locations.
- **Conflict Resolution**: Choose how to handle conflicts (Use Local, Use Drive, or Keep Both).
- **Background Sync**: Automated synchronization using Android WorkManager.
- **Material3 UI**: Modern, clean interface built with Jetpack Compose.

## How to Build

To build this project, you need to set up a Google Cloud Project and obtain the necessary credentials.

> [!CAUTION]
> **IMPORTANT**: The current package name (`uk.xmlangel.googledrivesync`) and configurations are specific to the original development environment. To build and use this in your own environment, you must update the **package name** or register your own **SHA-1 fingerprint** in your Google Cloud Console project.

### 1. Google Cloud Setup

1. Go to the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project.
3. Enable the **Google Drive API**.
4. Configure the **OAuth consent screen** (Internal or External).
5. Create **OAuth 2.0 Client IDs** for Android:
    - **Package name**: `uk.xmlangel.googledrivesync`
    - **SHA-1 certificate fingerprint**: You need to provide your debug/release certificate fingerprint.

### 2. Getting SHA-1 Fingerprint

Run the following command in your terminal to get the debug SHA-1 fingerprint:

```bash
./gradlew signingReport
```

Look for the `SHA1` under the `debug` variant.

### 3. Local Setup

1. Clone this repository.
2. Open the project in **Android Studio (Ladybug or newer)**.
3. Ensure you have **JDK 17** configured.
4. Build and run the app.

### 4. How to Generate APK

To manually generate an APK file, use one of the following methods:

#### Using Terminal (Gradle)

Run the following command to generate the APK file in the `app/build/outputs/apk/debug/` directory:

```bash
./gradlew assembleDebug
```

#### Using Android Studio UI

1. Go to `Build` > `Build Bundle(s) / APK(s)` > `Build APK(s)` in the top menu.
2. Once complete, click the `locate` link in the notification popup to find the generated file.

## Development Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose (Material3)
- **Database**: Room
- **Background Work**: WorkManager
- **API**: Google Drive API (Mobile)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
