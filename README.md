# Flishly

Flishly is a lightweight Android file server and browser for local networks. Install it on the Android device, start the server, and open the displayed address from another device's browser.

## Features

- Local web-based file manager
- Browse shared storage
- Upload and download files
- Create folders
- Rename, move, and delete files and folders
- View and edit text files
- Read and update the Android clipboard
- Optional HTTP Basic Authentication
- Select the network IPv4 address to bind to
- Foreground service for background operation
- Battery-optimization support for longer-running transfers
- Responsive browser interface

## Build

GitHub Actions builds a debug APK on every push. The workflow is in `.github/workflows/build-apk.yml`.

To build locally, open the project in Android Studio and build the `app` module.

## Use

1. Install Flishly on the Android device that contains the files.
2. Grant the requested permissions.
3. Choose a network address if needed.
4. Optionally set a password.
5. Tap **Start Server**.
6. Open the displayed `http://IP:4444` address on another device connected to the same network.

## Security

Flishly is intended for trusted local networks. HTTP traffic is not encrypted. If password protection is enabled, credentials are sent using HTTP Basic Authentication and should not be considered secure on an untrusted network.

The server restricts filesystem paths to the Android shared-storage root and rejects paths that escape that root.

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/info` | Device and storage information |
| GET | `/api/ls?path=` | List a directory |
| GET | `/api/fs?path=` | Download a file and support range requests |
| GET | `/api/read?path=` | Read a text file |
| POST | `/api/write` | Write a text file |
| POST | `/api/upload?path=` | Upload files |
| POST | `/api/mkdir` | Create a folder |
| POST | `/api/delete` | Delete files or folders |
| POST | `/api/move` | Move a file or folder |
| POST | `/api/rename` | Rename a file or folder |
| GET/POST | `/api/clipboard` | Read or update the device clipboard |
