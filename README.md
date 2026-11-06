# LesStress

Android companion app for KU Leuven Kulak students.

[**Download latest APK**](https://github.com/sanderdelanghe/LesStress/releases/latest/download/LesStress.apk)

## What it does

- **Timetable**: Syncs your personal TimeEdit schedule via iCal (.ics), checks for room changes and cancelled lectures, and lets you search or filter by course.
- **Alma Kulak**: Shows the daily warm lunch menu, sandwich bar options, and live opening hours.
- **Library (KBIB)**: Live seat occupancy counter and direct links for Kurt seat reservations / QR scanning.
- **Notifications**: Background worker that checks for schedule changes and alerts you about cancellations or room moves.

## Setup

### Adding your schedule
1. Go to the [KU Leuven schedule portal](https://webwsp.aps.kuleuven.be/sap/bc/ui2/flp?sap-language=NL#zr_1014-display).
2. Copy your TimeEdit subscription / iCal link.
3. Paste it in the app during the initial setup screen (or update it anytime in the Notifications tab).

## Building from source

Requirements:
- Android SDK (API 35, Build Tools 35.0.0)
- Java 17

Build the release APK:

```bash
# Windows
.\gradlew.bat assembleRelease

# Linux / macOS
./gradlew assembleRelease
```

The output APK will be at `app/build/outputs/apk/release/app-release.apk`.
