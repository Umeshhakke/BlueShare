# BlueShare

BlueShare (internally named `ScreenCopy`) is a native Android application that mirrors your device's screen to another Bluetooth-paired device, without needing Wi-Fi or an internet connection. It captures the screen frame-by-frame using Android's `MediaProjection` API and streams each frame as a compressed image over a classic Bluetooth (RFCOMM) socket.

<p align="center">
  <img src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java" />
  <img src="https://img.shields.io/badge/Bluetooth-0082FC?style=for-the-badge&logo=bluetooth&logoColor=white" alt="Bluetooth" />
  <img src="https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white" alt="Gradle" />
  <img src="https://img.shields.io/badge/Min%20SDK-25-blue?style=for-the-badge" alt="Min SDK" />
  <img src="https://img.shields.io/badge/Target%20SDK-36-blue?style=for-the-badge" alt="Target SDK" />
</p>

---

## What This App Does

BlueShare turns a phone into a "screen sender." When the user taps **Connect to Server**, the app:

1. Asks the OS for permission to record the screen.
2. Opens a Bluetooth RFCOMM connection to a paired device (the "server," identified by its MAC address).
3. Continuously captures the screen as bitmap frames.
4. Compresses each frame to JPEG and sends it over the Bluetooth socket, prefixed with its byte size so the receiving side knows how much data to read.

There is no companion "server" app included in this repository - the receiving end is expected to be a separate application (for example, a desktop or another Android app) that knows this simple length-prefixed JPEG protocol.

---

## Core Concepts Used in This Project

This project is a good example of several distinct Android and networking concepts working together. Here's what each one does and why it's used:

### 1. MediaProjection API
![Android](https://img.shields.io/badge/API-MediaProjection-3DDC84?style=flat-square&logo=android&logoColor=white)

Android's `MediaProjectionManager` is the only public way for an app to capture what's on screen (outside of its own UI) without root access. The user must explicitly grant permission through a system dialog (`createScreenCaptureIntent()`), which returns a `MediaProjection` token that the app can then use to record the display. This project requests that permission through an `ActivityResultLauncher`, following the modern Android pattern for handling activity results instead of the deprecated `onActivityResult` override.

### 2. Virtual Display + ImageReader
![Android](https://img.shields.io/badge/API-VirtualDisplay%20%2F%20ImageReader-3DDC84?style=flat-square&logo=android&logoColor=white)

Once permission is granted, the app doesn't get an image directly - instead, it creates a **virtual display** (`MediaProjection.createVirtualDisplay`) that mirrors the real screen's pixels onto a `Surface`. That surface belongs to an `ImageReader`, which is a buffer that lets the app pull frames out as they arrive (`acquireLatestImage()`). This producer/consumer setup is the standard way to get raw pixel data out of Android's display pipeline.

### 3. Foreground Service with `mediaProjectionType`
![Android](https://img.shields.io/badge/API-Foreground%20Service-3DDC84?style=flat-square&logo=android&logoColor=white)

Since Android 10+ (and more strictly from Android 14), screen-capture must run inside a **foreground service** declared with `foregroundServiceType="mediaProjection"`, and that service must show a persistent notification while it runs. `ScreenCaptureService` in this project exists purely to satisfy that OS requirement - it creates a notification channel and posts an ongoing "Screen Capture Active" notification so the system knows recording is happening and doesn't kill the app in the background.

### 4. Frame Throttling
![Performance](https://img.shields.io/badge/Concept-Backpressure%20Control-FF6F00?style=flat-square&logo=speedtest&logoColor=white)

Capturing and sending every single frame the display produces would flood a Bluetooth connection (which is far slower than Wi-Fi or USB). The app enforces a **minimum frame interval** (`MIN_FRAME_INTERVAL = 1000 / 15`, i.e. roughly 15 FPS) and drops any frame that arrives sooner than that, closing it immediately to avoid buffer leaks. This is a simple but effective form of backpressure control.

### 5. Bitmap Conversion & Row Padding
![Java](https://img.shields.io/badge/Language-Java-ED8B00?style=flat-square&logo=openjdk&logoColor=white)

Raw image data from `ImageReader` isn't a plain packed bitmap - each row can include padding bytes added by the hardware for memory alignment (`rowStride` vs `pixelStride`). The app calculates this **row padding** manually and builds a `Bitmap` that accounts for it, then scales it back down to the real screen dimensions. This is a common gotcha when working directly with `ImageReader` output on Android.

### 6. Classic Bluetooth (RFCOMM) Sockets
![Bluetooth](https://img.shields.io/badge/Protocol-RFCOMM%20%2F%20SPP-0082FC?style=flat-square&logo=bluetooth&logoColor=white)

Rather than Bluetooth Low Energy (BLE), which is designed for small, infrequent data packets, this app uses **classic Bluetooth RFCOMM sockets** (`BluetoothSocket`, `createRfcommSocketToServiceRecord`) - the same transport used by things like Bluetooth serial/SPP connections. RFCOMM behaves like a normal streaming socket, which makes it well-suited to continuously pushing image data. A fixed UUID (`00001101-0000-1000-8000-00805F9B34FB`, the standard Serial Port Profile UUID) is used to identify the service on the receiving device.

### 7. Length-Prefixed Streaming Protocol
![Bluetooth](https://img.shields.io/badge/Data-Length%20Prefixed%20Framing-0082FC?style=flat-square&logo=bluetooth&logoColor=white)

Because a raw byte stream has no built-in message boundaries, the app sends the size of each JPEG frame as a 4-byte big-endian integer immediately before the image bytes themselves. The receiving side is expected to read exactly 4 bytes to learn the payload size, then read that many bytes to reconstruct the frame. This length-prefix framing is a lightweight, common pattern for streaming variable-sized binary messages over a socket.

---

## Project Structure

```
BlueShare/
├── app/
│   ├── build.gradle.kts                 - App-level Gradle config (SDK versions, dependencies)
│   ├── proguard-rules.pro               - ProGuard/R8 rules for release builds
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      - Permissions, activity & service declarations
│       │   ├── java/com/example/screencopy/
│       │   │   ├── MainActivity.java            - UI, permission flow, capture & Bluetooth logic
│       │   │   └── ScreenCaptureService.java     - Foreground service required for screen capture
│       │   └── res/
│       │       ├── layout/activity_main.xml      - Main screen UI (status card, connect button)
│       │       ├── values/strings.xml            - App name & string resources
│       │       ├── values/colors.xml             - Color palette
│       │       └── values/themes.xml             - App theme
│       ├── test/                         - Local JVM unit tests
│       └── androidTest/                  - Instrumented (on-device) tests
├── gradle/                               - Gradle wrapper & version catalog (libs.versions.toml)
├── build.gradle.kts                       - Project-level Gradle config
└── settings.gradle.kts                    - Module & repository configuration
```

---

## Tech Stack

| Layer              | Technology |
|--------------------|------------|
| Language           | ![Java](https://img.shields.io/badge/-Java-ED8B00?style=flat-square&logo=openjdk&logoColor=white) |
| Platform           | ![Android](https://img.shields.io/badge/-Android-3DDC84?style=flat-square&logo=android&logoColor=white) min SDK 25, target/compile SDK 36 |
| Screen capture     | `MediaProjection`, `VirtualDisplay`, `ImageReader` |
| Data transport     | ![Bluetooth](https://img.shields.io/badge/-Bluetooth-0082FC?style=flat-square&logo=bluetooth&logoColor=white) Classic (`BluetoothSocket`, RFCOMM/SPP) |
| UI                 | ![Material Design](https://img.shields.io/badge/-Material%20Design-757575?style=flat-square&logo=materialdesign&logoColor=white) AppCompat, CardView, ConstraintLayout |
| Build system       | ![Gradle](https://img.shields.io/badge/-Gradle-02303A?style=flat-square&logo=gradle&logoColor=white) Kotlin DSL with a version catalog (`libs.versions.toml`) |

---

## Permissions Required

| Permission | Why it's needed |
|------------|------------------|
| ![Bluetooth](https://img.shields.io/badge/-Bluetooth-0082FC?style=flat-square&logo=bluetooth&logoColor=white) `BLUETOOTH`, `BLUETOOTH_ADMIN` | Legacy Bluetooth access (pre-Android 12) |
| ![Bluetooth](https://img.shields.io/badge/-Bluetooth-0082FC?style=flat-square&logo=bluetooth&logoColor=white) `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | Runtime Bluetooth permissions required on Android 12+ |
| ![Android](https://img.shields.io/badge/-Android-3DDC84?style=flat-square&logo=android&logoColor=white) `FOREGROUND_SERVICE` | Required to run the screen-capture service in the foreground |
| ![Android](https://img.shields.io/badge/-Android-3DDC84?style=flat-square&logo=android&logoColor=white) `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Specific foreground service type required for media projection on Android 14+ |

---

## Getting Started

### Prerequisites
- Android Studio (Koala or newer recommended)
- An Android device or emulator running API level 25+
- A second Bluetooth-paired device or app acting as the receiver, listening on the SPP UUID (`00001101-0000-1000-8000-00805F9B34FB`)

### Setup

```bash
git clone https://github.com/Umeshhakke/BlueShare.git
cd BlueShare
```

Open the project in Android Studio and let Gradle sync, or build from the command line:

```bash
./gradlew assembleDebug
```

### Configure the Receiver Address

Before building, set the MAC address of the Bluetooth device that will receive the screen stream. This is currently hardcoded in `MainActivity.java`:

```java
private final String DEVICE_ADDRESS = "XX:XX:XX:XX:XX:XX"; // Replace with server MAC
```

Replace it with the paired receiving device's actual Bluetooth MAC address.

### Run

1. Pair your Android device with the target receiver device via system Bluetooth settings first.
2. Install and launch the app.
3. Tap **Connect to Server**.
4. Grant the screen-capture permission when prompted.
5. The app will connect over Bluetooth and begin streaming JPEG frames of the screen.

---

## Known Limitations

- The receiver's MAC address is hardcoded rather than discovered/selected at runtime.
- There is no pairing or device-picker UI - the target device must already be paired via system settings.
- Bluetooth classic bandwidth is limited, so frame rate and resolution are intentionally throttled; this is not intended as a low-latency mirroring solution.
- No corresponding "receiver" application is included in this repository.

---

## Contributing

Contributions are welcome. Useful directions include a device picker/discovery UI, adaptive frame quality based on connection speed, and a reference receiver application implementing the length-prefixed JPEG protocol described above.
