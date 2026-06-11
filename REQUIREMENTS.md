# Weather Map App Requirements

## Product Goal

Build a modern Android weather map app whose primary surface is an interactive map with weather overlays. The app should open directly into the user's local weather context and make current conditions and near-term movement understandable at a glance.

## Core User Expectations

- Starts directly on the map, not on a landing screen.
- Uses the current device location with clear fallback behavior.
- Supports normal map gestures: drag, pinch-to-zoom, double-tap/controls, and responsive re-rendering.
- Shows weather as continuous map overlays, not isolated markers.
- Lets users switch layers quickly without leaving the map.
- Shows numeric values on the map where useful, especially temperature.
- Provides a compact bottom sheet with current conditions and forecast.
- Works on modern large Android phones in portrait orientation with thumb-reachable controls.
- Fails visibly and recoverably when network, location, or provider data is unavailable.

## Weather Layers

- Radar precipitation: real raster weather-tile overlay, animated across available frames.
- Temperature: smoothed field with map labels in degrees Celsius.
- Wind: current speed and direction in the detail panel; future map layer is desirable.
- Layer controls: current selected layer is obvious, one tap to switch.
- Legends: always match the selected layer and explain colors compactly.

## Forecast and Time UX

- Current conditions must be visible immediately.
- A horizontal timeline should allow users to scrub current/near-future forecast hours.
- Radar layer should support play/pause animation and frame stepping.
- Forecast layer should update when the selected forecast hour changes.
- Forecast map data should follow the current viewport and zoom level instead of staying fixed around the first location.
- Forecast data loading should use a buffered viewport cache so small pans do not trigger network requests.
- Time labels should use local device/user timezone formatting.
- The map should always show the effective data time for the selected radar frame or forecast hour.

## Map UX

- Pinch zoom must zoom around the pinch midpoint and feel continuous, not jump only between integer zoom levels.
- Drag must be smooth and should not accidentally scroll the whole page.
- Weather overlays must move and scale with the map.
- Text labels must remain readable over tiles.
- Controls should avoid the bottom install/navigation area and status bar.
- Attribution must remain visible for map/radar providers.

## Data and Providers

- Use OpenStreetMap tiles for the base map.
- Use RainViewer Weather Maps API for radar tiles where available.
- Use Open-Meteo forecast API for current and hourly model data.
- Support querying multiple nearby coordinates in one Open-Meteo request for local fields.
- Avoid API keys for this prototype.

## Android Requirements

- Kotlin, Gradle, Jetpack Compose.
- Native Android shell with WebView map implementation is acceptable for this prototype.
- Request foreground location only; support precise and approximate location.
- Do not request background location.
- Use INTERNET, ACCESS_COARSE_LOCATION, and ACCESS_FINE_LOCATION only.
- Debug APK must build with the local SDK and be installable.

## Quality Bar

- The app must render a visible base map even if weather providers fail.
- Weather provider failures should show an inline message, not a blank screen.
- Build must pass `assembleDebug`.
- APK should be sent via the existing Telegram script when available.
