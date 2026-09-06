# FileBridge

A modern replacement for the old "Sweech" client/server pair. Install **one APK
on the Android device you want to access** — nothing needs to be installed on
the other device. Just open a browser there and go to the IP:port the app
shows you.

## What it does

- Runs a small embedded web server *inside* the Android app (NanoHTTPD).
- Serves a full single-page file manager UI (dark, modern, mobile-friendly)
  directly from the phone — no other app, browser extension, or driver needed
  on the machine you're browsing from.
- Full file operations: browse, download, upload (drag-and-drop or picker),
  create folders, rename, move, delete, view/edit text files, and get/set the
  device clipboard remotely.
- Optional password protection (HTTP Basic Auth) — leave blank for none.
- Keeps running via a foreground service + persistent notification so the
  server survives while you use it.

## ⚠️ Important — why there's no .apk file attached directly

I cannot compile and sign an Android `.apk` binary myself in this sandboxed
environment (no Android SDK/Gradle toolchain, no internet access to fetch
one). What you have here is the **complete, ready-to-build source project**.
There are two easy ways to turn it into an actual installable APK — pick
whichever is easier for you:

### Option A — Let GitHub build it for you (no Android Studio needed)

1. Create a new **public or private** GitHub repository.
2. Upload/push everything in this folder to that repo (keep the folder
   structure as-is).
3. GitHub Actions will automatically run (see
   `.github/workflows/build-apk.yml`) and compile a debug APK in the cloud.
4. Go to the repo's **Actions** tab → click the latest run → download the
   `FileBridge-debug-apk` artifact (a zip containing `app-debug.apk`).
5. Transfer that APK to your Android phone (e.g. via the same FileBridge-style
   transfer, Google Drive, USB, email, etc.) and install it (you'll need to
   allow "install unknown apps" for whichever app you use to open the file).

This requires no local setup at all — just a free GitHub account.

### Option B — Build locally with Android Studio

1. Install [Android Studio](https://developer.android.com/studio).
2. Open this folder as a project (`File → Open`).
3. Let Gradle sync (it will download the Android Gradle Plugin, Kotlin, and
   the NanoHTTPD library automatically — needs internet once).
4. Click **Run ▶** with your phone connected (USB debugging on), or
   `Build → Build Bundle(s)/APK(s) → Build APK(s)` to get an installable file
   under `app/build/outputs/apk/debug/`.

## Using the app

1. Install and open FileBridge on the Android device.
2. (Optional) type a password.
3. Tap **Start Server**. The app shows something like
   `http://192.168.1.42:4444`.
4. On any other device connected to the **same Wi-Fi network**, open a
   browser and type that address.
5. Browse, upload, download, edit text files, and manage the clipboard — all
   from the browser, no app install required on that side.

## Security notes

- This is designed for use on a **trusted local network** (home/office
  Wi-Fi). There is no HTTPS/TLS, so don't expose port 4444 to the public
  internet without adding your own reverse proxy + TLS in front of it.
- The optional password uses HTTP Basic Auth, which is fine on a private LAN
  but is not encrypted in transit — treat it as a light deterrent, not strong
  security.
- The app requests broad storage access (`requestLegacyExternalStorage`) so
  it can browse the whole shared storage area, similar to the original
  Sweech app.

## Project structure

```
FileBridge/
├── app/
│   ├── build.gradle                 # app module config + dependencies
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/filebridge/server/
│       │   ├── MainActivity.kt      # start/stop UI, shows IP:port
│       │   ├── WebServerService.kt  # foreground service hosting the server
│       │   ├── BridgeHttpServer.kt  # NanoHTTPD routes (ls/fs/upload/etc.)
│       │   └── ServerConfig.kt
│       ├── res/                     # layout, colors, theme, launcher icons
│       └── assets/web/              # the browser UI (index.html/app.js/styles.css)
├── build.gradle / settings.gradle / gradle.properties
└── .github/workflows/build-apk.yml  # auto-builds the APK on push
```

## API reference (for the curious)

| Method | Path             | Purpose                                   |
|--------|------------------|--------------------------------------------|
| GET    | `/api/info`      | Device brand/model, storage stats          |
| GET    | `/api/ls?path=`  | List a directory                           |
| GET    | `/api/fs?path=`  | Download a file (supports range requests)  |
| GET    | `/api/read?path=`| Read a text file's contents                |
| POST   | `/api/write`     | Save text file contents                    |
| POST   | `/api/upload?path=` | Upload files (multipart) into a folder |
| POST   | `/api/mkdir`     | Create a folder                            |
| POST   | `/api/delete`    | Delete file(s)/folder(s)                   |
| POST   | `/api/move`      | Move/rename by full paths                  |
| POST   | `/api/rename`    | Rename in place                            |
| GET/POST | `/api/clipboard` | Get/set the device clipboard             |
