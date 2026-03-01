# CozmoPlay

A replacement companion app for the Anki Cozmo robot, built for Amazon Fire HD 10 tablets running Fire OS.

## Why this exists

The official Cozmo app crashes consistently on Fire OS. This is a stable, fully offline, child-centred replacement built with native Android (Kotlin + Jetpack Compose).

## Module Structure

```
cozmo_app/
├── cozmo-types/       # Shared data types (no dependencies) — all agents
├── cozmo-wifi/        # WiFi detection, connection, socket binding — Agent 2
├── cozmo-protocol/    # Cozmo UDP/protobuf protocol engine — Agent 1
├── cozmo-camera/      # JPEG decode pipeline + CozmoCamera composable — Agent 4
├── app/               # All Compose UI screens and navigation — Agent 3
└── test-suite/        # Integration tests across all modules — Agent 5
```

## Dependency Flow

```
cozmo-types (no deps)
    ↓
cozmo-wifi
    ↓
cozmo-protocol
    ↓
cozmo-camera
    ↓
app (UI)
    ↑
test-suite (depends on all + mocks)
```

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material3
- **Async:** Kotlin Coroutines + StateFlow
- **DI:** Koin
- **Protocol:** UDP sockets + protobuf-lite
- **Min SDK:** API 28 (Fire OS 7 / Android 9)
- **Target SDK:** API 34

## Build Requirements

- Android Studio Hedgehog or newer
- JDK 17
- Gradle 8.7 (wrapper included)

## Getting Started

```bash
git clone https://github.com/macdougallg/cozmo_app.git
cd cozmo_app
./gradlew assembleDebug
```

## Phase Status

| Module | Status | Notes |
|---|---|---|
| cozmo-types | ✅ Complete | All shared data types |
| cozmo-wifi | ✅ Complete | Full implementation + MockCozmoWifi + unit tests |
| cozmo-protocol | 🚧 In Progress | Agent 1 — see Protocol PRD |
| cozmo-camera | 🚧 Pending | Agent 4 — see Camera PRD |
| app (UI) | 🚧 In Progress | ConnectScreen done; others pending Agent 3 |
| test-suite | 🚧 Pending | Agent 5 — integration tests |

## Reference

- [PyCozmo](https://github.com/zayfod/pycozmo) — canonical protocol reference
- [Cozmo SDK](https://github.com/anki/cozmo-python-sdk) — protobuf definitions
- [Fire OS Developer Docs](https://developer.amazon.com/docs/fire-tablets/ft-overview.html)
