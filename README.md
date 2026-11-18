# DotForge

A private, personal Android app for the Nothing Phone (3) Glyph Matrix (25x25 grid, 489 round LEDs):
notification rules with a spam filter, progress rings, Glyph Toys, a matrix Studio and a Quick Settings
torch, plus a Diagnostics bench for checking the hardware. Package `com.dotforge`.

## Modules

| Module | Contents |
| --- | --- |
| `:core-device` | Pure Kotlin/JVM. `DeviceProfile` (25x25 grid, round mask `(x-12)^2 + (y-12)^2 <= 153` = 489 LEDs, 68-cell outer ring clockwise from 12 o'clock, 625 <-> 489 helpers), `Frame` / `Animation`, `Effects` (pulse, ring chase, flash, sender letter, marquee, sparkle, progress ring, dice, clock, checkerboard), the 5x5 `PixelFont` / `DotText` and 3x5 `BigDigits`, and the Dot Hub / Glyph Museum JSON model (`GlyphMuseumJson`). |
| `:glyph-driver` | `GlyphDriver`: the only code that touches Nothing's `GlyphMatrixManager`. Process-wide, ref-counted, one IO thread, newest-frame-wins per channel (app / toy), reconnect with backoff, `StateFlow` state, fps and call log. Frames are 0-255; the driver rescales to the SDK's raw 0-4095 range. |
| `:composition` | Pure Kotlin/JVM. Ogg page reader/writer, Vorbis comment / OpusTags parser, glyph `AUTHOR` codec (base64 -> zlib -> CSV). Used by the Diagnostics ringtone reader. |
| `:app` | Compose + Material 3 UI (Home, Rules, Studio, Toys, Settings, Diagnostics), `DisplayArbiter`, rules engine and notification listener, toys, torch tile. |

### Who owns the matrix

- Toys draw on the toy channel (`setMatrixFrame`) through `GlyphDriver`.
- Everything else goes through `DisplayArbiter` -> `MatrixDevice` -> `GlyphDriver` on the app channel
  (`setAppMatrixFrame`). `MatrixDevice` is a thin adapter; on non-Nothing phones it is a preview-only
  device and the matrix is just drawn in the app.
- Arbiter priority: torch (70) > Studio preview (60) > notification (50) > progress (30); newest wins on ties.
  A master switch hides everything. Diagnostics suspends the arbiter while it drives the driver directly.
- Firmware lights always win over app frames (ringtone > active toy > Nothing notification glyph > app
  frames) and the app is never told when it was covered, so the arbiter re-sends the current frame every
  3 s and immediately on screen-on / unlock.
- Notifications and progress ("ambient" requests) follow Nothing's `led_effect_enable` switch and the Glyph
  bedtime schedule (`led_effect_schedule_time_ebable`, `led_bed_time_custom_*`) when the firmware lets apps
  read them; unreadable settings never block anything. Torch and Studio preview always work.

## Build

Requirements: JDK 17+, Android SDK platform 36 (set `ANDROID_HOME` or `sdk.dir` in `local.properties`).

```
./scripts/fetch-glyph-sdk.sh      # downloads Nothing's AAR into app/libs/ (not redistributable, gitignored)
./gradlew test assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. CI (`.github/workflows/android.yml`) runs the same.

Unit tests cover the device geometry and effects, the JSON model, the Ogg/AUTHOR codec, the rules engine
(matching modes, ordering, spam, persistence), the arbiter (priority, fallback, timeouts, master switch,
gate, re-send), the Glyph Button press decoder and the bedtime schedule.

## On-phone checks

Install the APK and open DotForge.

1. **Diagnostics (Settings > Open Diagnostics).**
   - *Driver*: status should reach READY. All on / Checkerboard / Ring sweep (clockwise on exactly the edge
     LEDs) / Scroll / brightness / Clear; "FPS stress 5 s" reports the achieved frame rate. Confirm full
     brightness at 255 (the driver sends 0-4095).
   - *Nothing settings*: shows which `led_*` / `glyph_*` keys are readable.
   - *Ringtone reader*: pick a Composer `.ogg`, check COMPOSER / CUSTOM1 / CUSTOM2 and the AUTHOR size;
     625-column rows can be played at 60 fps.
   - *Toy event log*: add any DF toy, press / hold the Glyph Button; every event the toys receive is logged.
2. **Home.** Grant notification access. "Message", "Progress" and "Torch" should light the matrix in that
   priority order; the "Now showing" list matches.
3. **Rules.** Send yourself messages that hit each rule; the rule tester shows which rule matched. Check
   hold until dismissed, clear on unlock, DND, and a download or Live Update progress ring.
4. **Toys.** In Glyph Toys add DF Clock, DF Battery, DF Dice, DF Player. Check long press (date, percent,
   pause), double press on Player, shake on Dice, and Clock / Battery / Player as the always-on toy.
5. **Studio.** Draw, add frames, save, "Show on Glyphs", export and re-import JSON (also a Dot Hub file).
6. **Torch tile.** Settings > Add tile, then toggle it from Quick Settings.
7. **Firmware overlap.** While a rule or the torch shows, trigger a call / Nothing notification glyph; the
   DotForge frame should come back within a few seconds and on unlock. Turn Glyph off and set a bedtime:
   notifications should stop lighting (if the settings are readable).

## Licences

- The Nothing Glyph Matrix SDK AAR is **not** included and must not be committed or redistributed (its EULA
  forbids it). `scripts/fetch-glyph-sdk.sh` downloads it from Nothing's public repository.
- Code adapted from MIT-licensed projects is listed in `NOTICE`.
- Nothing setting key names and formats come from inspecting Phone (3) firmware behaviour; no Nothing code is included.
