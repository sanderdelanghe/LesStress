# LesStress

Modern, native Android companion app for students at **KU Leuven Kulak** and **VIVES Kortrijk**.

[**Download latest APK**](https://github.com/sanderdelanghe/LesStress/releases/latest/download/LesStress.apk)

---

## Features

### 📅 Smart Timetable & Schedule
- **Direct KU Leuven TimeEdit Integration**: Seamlessly parses official schedule data directly from TimeEdit JSON, eliminating parsing fragility.
- **Secondary Calendar Feed (Google Calendar / Custom ICS)**: Add your personal or exam calendar alongside your university schedule with dedicated custom colors and full location display.
- **Google Calendar Sync**: Export university lectures to a dedicated 'LesStress' Google Calendar without duplicating your personal appointments.
- **Adaptive Calendar Views**: Full 1-day, 3-day, 5-day, 7-day, and Agenda list views with smooth pinch-to-zoom, responsive layouts, and quick date navigation.
- **Instant Search**: Find courses, professors, or rooms instantly with active keyboard actions.
- **Deep-Linked Lesson Notifications**: Get notified before lectures start; tapping a notification opens the app directly into that lesson's details (room, lecturer, timing, and notes).
- **Schedule Change Detection**: Background sync monitors your timetable for cancellations or room moves, highlighting recent changes and storing a history diff.

### 🍽️ Modular Dining & Restaurants
- **Alma Kulak Restaurant**: Daily hot buffet & soup menus with swipeable date pager, dish photos, student prices, and **Vegan** / **Veggie** indicators.
- **Alma Broodjesbar**: Quick access to live sandwich counter opening hours and one-tap OrderBilly online ordering.
- **Eat @ Cube (VIVES)**: Dynamic weekly menu link with automatic opening hours detection (11:30–14:00) and holiday/closure status parsing.
- **Live Status Badges**: Real-time status badges indicating whether venues are Open, Closing Soon, Closed, or Uncertain with countdowns.

### 📚 Modular Libraries & Study Spaces
- **Bibliotheek KBIB (Kulak)**: Live seat occupancy percentage meter, opening hours, one-tap KURT seat reservations, and instant QR seat check-in by seat number.
- **CoCon Kortrijk (VIVES)**: Automatically parsed opening hours and intelligent alerts for exceptions, team days, and modified staffing.

### ⚙️ Modern Settings & Customization
- **Full-Screen Categorized Settings**: Manage your Schedule, Notifications, Providers, and App Updates in clean sections.
- **16-Color Palette**: Personalize event colors with an expanded, high-contrast palette.
- **Provider Ordering & Toggles**: Enable, disable, and rearrange the display order of dining and library providers to fit your campus routine.
- **In-App GitHub APK Updater**: Checks for new releases directly on GitHub, downloads updates in-app with a progress indicator, and triggers installation without manual browser downloads.
- **Material 3 Theming**: Full support for System, Light, and Dark themes with accessible, high-contrast status colors.

---

## Setup

### Adding your schedule
1. Go to the [KU Leuven schedule portal](https://webwsp.aps.kuleuven.be/sap/bc/ui2/flp?sap-language=NL#zr_1014-display).
2. Copy your TimeEdit subscription / iCal link (the app automatically converts it to the robust JSON endpoint).
3. Paste the URL during the initial setup or anytime under **Settings > Rooster / Schedule**.
4. *(Optional)* Paste your personal Google Calendar or external ICS link to view personal events and exams with a custom color.

---

## Roadmap

Planned future additions:
- 🚆 **Live NMBS Train Departures (Kortrijk Station)**: Live train schedule and platform departure board for Friday afternoon commuters heading to Gent, Brugge, Brussel, and Antwerpen.
- 🚌 **De Lijn Campus Bus Departures**: Real-time bus arrivals for *Halte Kulak Universiteit* and *Halte VIVES*.
- 📍 **Kulak Campus Room Finder**: Interactive building and floor guide for navigating Kulak room codes (A, B, C, E, Spina, Vesalius, Jan Palfijn).

---

## Building from Source

### Requirements
- Android SDK (API 35, Build Tools 35.0.0)
- Java 17 / Kotlin 2.0

### Compile & Build APK
```bash
# Windows
.\gradlew.bat assembleRelease

# Linux / macOS
./gradlew assembleRelease
```

The output APK will be generated at:
`app/build/outputs/apk/release/app-release.apk`
