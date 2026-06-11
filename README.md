# Weather Map

A small native Android weather map prototype built with Kotlin, Gradle, Jetpack Compose, and MapLibre.

## Features

- OpenStreetMap base map
- Current device location with fallback
- Temperature overlay from Open-Meteo forecast data
- Temperature labels with reduced density
- RainViewer radar overlay with zoom capped to supported levels
- Compose UI with layer controls, forecast timeline, and weather details

## Build

This project expects an Android SDK. If one is not already configured, the helper script installs a local SDK into `.android-sdk`:

```bash
./setup-android-sdk.sh
./gradlew assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/
```

## Data Sources

- Map tiles: OpenStreetMap
- Forecast: Open-Meteo
- Radar: RainViewer

No API keys are required for this prototype.
