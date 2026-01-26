# Google Drive Sync Functional Specifications

## 1. Project Overview

**Google Drive Sync** is an Android application that provides seamless data synchronization between local folders on the device and cloud folders in Google Drive. It aims to build a personalized backup and collaboration environment through powerful synchronization policy settings alongside a familiar file browsing experience.

---

## 2. Usage Features

These are the core features where users directly manipulate the app to manage data and check status.

### 2.1 Account and Authentication Management

* **Google Login/Logout**: Supports OAuth 2.0-based authentication for Google Drive API access.
* **Multi-Account Support**: Users can register multiple Google accounts and switch the active account as needed.
* **Connection Status**: The email address and status of the currently connected account can be checked in real-time on the dashboard.

### 2.2 Sync Pair Configuration

* **Drive Browsing**: Explore the folder structure in Google Drive in a tree format to select target folders for synchronization.
* **Local Folder Selection**: Specify folders within the device storage (internal storage) to sync.
* **Pairing Registration**: Bind the selected local and Drive folders into a single 'Sync Pair' for registration.

### 2.3 Dashboard and Real-time Monitoring

* **List View**: View a list of all currently registered sync pairs and their last synchronization time.
* **Status Indicators**: Visually display statuses such as Syncing (file name, progress %), Pending, or Error.
* **Progress Details**: Shows the current file name being processed, the current index relative to the total number of files, and the bytes transferred in real-time.

### 2.4 Manual Control

* **Instant Sync**: Regardless of the schedule, users can press the `Sync Now` button to perform the task immediately.
* **Cancel Task**: Safely interrupts an ongoing synchronization process.

### 2.5 Sync History Management

* **Detailed Log View**: Records the success/failure of past sync tasks, start/end times, number of files uploaded/downloaded, and error counts.
* **Error Tracking**: Provides detailed messages on which files encountered issues in case of sync failure.

---

## 3. Setting Features

These are detailed settings for personalizing the app's behavior and automation rules.

### 3.1 Sync Direction Policy

Defines the data flow for each sync pair.

* **Bidirectional Sync**: Compares changes on both sides to keep them up-to-date.
* **Upload Only**: Reflects only local changes to Google Drive.
* **Download Only**: Reflects only Drive changes to the local device.

### 3.2 Automation Scheduling

* **Auto-Sync Switch**: Globally turn background auto-sync on or off.
* **Sync Interval Setting**: Set schedules for auto-sync at regular intervals such as 15 minutes, 30 minutes, 1 hour, or 6 hours. (Minimum 15 minutes recommended due to Android OS constraints)

### 3.3 Execution Constraints

Configure constraints to optimize battery and data consumption.

* **Wi-Fi Only Mode**: Performs synchronization only when connected to Wi-Fi to protect mobile data.
* **Charging Only Mode**: Performs synchronization only when the device is connected to power to prevent battery drain.

### 3.4 Notification Control

* **Completion Notification**: Set whether to receive push notifications when a sync task ends successfully.
* **Error Notification**: Receive notifications when problems occur during sync or in conflict situations requiring user intervention.

### 3.5 Conflict Resolution Strategy

Predefines how to handle cases where the same file is modified simultaneously on both local and remote sides.

* **Always Ask**: The user manually selects a resolution method through the dashboard or notifications.
* **Use Local**: Overwrites with the local file's content.
* **Use Drive**: Updates the local device with the content from the cloud.
* **Keep Both**: Keeps both versions by renaming the conflicting files.
* **Skip**: Ignores the synchronization of the file and proceeds to the next.

---

## 4. Technical Specifications Summary

| Feature | Description |
| :--- | :--- |
| **Database** | Room DB (Stores settings, state, and history) |
| **Background Work** | WorkManager (Manages schedules and constraints) |
| **Change Detection** | Comparison of Modification Timestamp and Size |
| **Authentication** | Google OAuth 2.0 (Drive API Permissions) |

---

## 5. Main Feature Summary

| Category | Feature | Key Description | Detailed Description |
| :--- | :--- | :--- | :--- |
| **Usage** | Account Management | Google account authentication and account switching | Supports secure OAuth 2.0 login and allows real-time switching between multiple registered accounts. |
| | Sync Pair Config | Registration and management of local/Drive folder pairs | Maps specific local storage folders with Google Drive folders to apply individual sync rules. |
| | Monitoring | Real-time display of sync status and progress | Dashboard provides live tracking of current file names, progress (%), and total bytes transferred. |
| | History | Success/failure records and detailed log access | Logs start/end times, number of files processed, and provides detailed error logs for transparency. |
| **Settings** | Sync Policy | Choice of Bidirectional, Upload Only, or Download Only | Determines the direction of file movement (Bidirectional, Local to Drive, Drive to Local) based on user goals. |
| | Automation | Sync intervals (min 15m) and auto-sync scheduling | Leverages WorkManager for scheduled background synchronization with battery-aware optimizations. |
| | Constraints | Operation limited to Wi-Fi connection and charging state | Controls execution under specific network or power conditions to prevent data loss and battery drain. |
| | Conflict Resolution | Local/Drive priority rules and 'Keep Both' renaming policy | Handles simultaneous edits via overwriting, skipping, or keeping both versions with a '_(local)' suffix. |
