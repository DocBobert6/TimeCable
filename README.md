# TimeCable

Android phone + TV app: a 2000s digital-cable guide and "live" tuner over public Internet Archive video.
Each row is a looping, time-anchored playlist, so what is on at any moment depends only on the clock.
No backend. Phone-first touch UI (portrait or landscape) with a mid-2000s cable-box look; Android TV remotes also work (API 26+).

## Build
1. Open this folder in Android Studio (Ladybug or newer). Let Gradle sync. If Studio proposes
   newer AGP/Kotlin/library versions, accepting them is fine.
2. Run on a Google TV / Android TV emulator (1080p, API 34) or a device with ADB debugging.
   Command line: run `gradle wrapper` once, then `./gradlew assembleDebug`
   -> app/build/outputs/apk/debug/app-debug.apk, then `adb install -r` it.

## Touch (phone)
- Watch: tap = info banner + controls (CH ▲/▼, LAST, GUIDE, CRT); swipe up/down = change channel
- Guide: tap a channel name to preview its info, tap a show to watch, ◀ ▶ = move through time, ✕ = close
- CRT panel: ON/OFF, pick style, − / + for each setting

## Remote (secondary)
- Watch: Up/Down = next/prev, Left = last, 0-9 = jump (3 digits or pause), OK/Guide/Menu = guide, Info = banner
- Guide: Up/Down = row, Left/Right = time (+/- 30 min), OK = tune, Back/Guide = close, ChUp/ChDn = 5 rows
- CRT: Red / L1 / F1 = switch CRT on/off (off by default; remembered)
- CRT panel: Green / R1 / F2 opens it. First row picks the style (Lottes Fast or Lottes) with Left/Right.
  Up/Down select, Left/Right adjust, OK or Green closes.

## CRT shaders
Media3 Effects (OpenGL ES) ports of Timothy Lottes' public-domain CRT algorithm:
- **Lottes Fast** – scanlines + aperture mask + mild barrel warp (cheap, good on sticks)
- **Lottes** – full single-pass: Gaussian soft pixels, scanline weighting, aperture/VGA mask, bloom

All parameters (hard scan, hard pix, warp, mask, bloom, brightness, shape, TV lines) are adjustable live
from the on-screen panel; Fast hides the ones it does not use. TV LINES sets the virtual scanline count (default 240).

Note: Android video effects run on OpenGL ES, not Vulkan. The math is the same Lottes
algorithm; only the shading language / pipeline differs from RetroArch-style Vulkan shaders.

## Edit the lineup
`Lineup.kt`: number, callsign, and an archive.org search query per row. First launch resolves 24 items
per row (needs network) and caches them in app storage; later launches start instantly.
Items that fail to decode or return 404/403 are removed permanently (bad.txt).

## Notes
- While a row is still filling on first run its program order can shift as items are added; it is stable once cached.
- Mixed licensing: Internet Archive collections are not uniformly public domain. Prelinger, silent films and
  commercials are mostly safe; check the others before distributing.
- Audio focus is requested so the app behaves correctly with other media and system sounds.
- Guide window defaults to 6 columns × 30 minutes (3 hours). Change `guideHalfMinutes` / `guideColumns`
  on the CableBox if you want a different density.
