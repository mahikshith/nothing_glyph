# DotForge

A private, personal Android app for the Nothing Phone (3) Glyph Matrix (25x25 grid, 489 round LEDs):
notification rules with a spam filter, progress rings, Glyph Toys, a pixel editor and a torch, plus a
Diagnostics bench for checking the hardware. Package `com.dotforge`.

## Screens

Three tabs; detail pages open full screen with a back arrow.

- **Glyph**: live matrix preview and brightness, then feature tiles. *Notifications*: Rules (rule list with
  the Calls, OTP and Messages presets; each rule reads as "When [apps] from [people] mention [keywords],
  light up [glyph]") and Spam filter (switch on the tile; the page has today's count, recent catches with
  Allow, "Always allow" Contacts / Starred, words and patterns under Advanced). *Live*: Progress rings and
  Torch (switches). *Toys*: tap for how to add it in Glyph Toys, hold for its settings. *Customise*:
  Essential Key, Ringtones, App integrations and Import, all marked Coming soon.
- **Create**: saved designs; + gives Static or Animated. The editor mirrors the drawing on the matrix while
  open and plays it in a corner preview. Saved designs feed the DF Player toy and the "My design" effect.
- **Settings**: permission checklist (Notification access, Contacts), Nothing's Glyph switch and bedtime,
  Glyph Toys, Advanced (Diagnostics, restore presets), About. A red dot on the tab means setup is incomplete.

Permissions are asked when a feature first needs them, always with "Not now". Notification access for a
sideloaded app is blocked by Android 13+ restricted settings: if access is still missing after the settings
page, the app shows the unlock steps (App info > menu > Allow restricted settings) with a button to App info.

## Modules

| Module | Contents |
| --- | --- |
| `:core-device` | Pure Kotlin/JVM. `DeviceProfile` (25x25 grid, round mask `(x-12)^2 + (y-12)^2 <= 153` = 489 LEDs, 68-cell outer ring clockwise from 12 o'clock, 625 <-> 489 helpers), `Frame` / `Animation`, `Effects` (pulse, ring chase, flash, sender letter, marquee, sparkle, progress ring, dice, clock, checkerboard), the 5x5 `PixelFont` / `DotText` and 3x5 `BigDigits`, and the Dot Hub / Glyph Museum JSON model (`GlyphMuseumJson`). |
| `:glyph-driver` | `GlyphDriver`: the only code that touches Nothing's `GlyphMatrixManager`. Process-wide, ref-counted, one IO thread, newest-frame-wins per channel (app / toy), reconnect with backoff, `StateFlow` state, fps and call log. Frames are 0-255; the driver rescales to the SDK's raw 0-4095 range. |
| `:composition` | Pure Kotlin/JVM. Ogg page reader/writer, Vorbis comment / OpusTags parser, glyph `AUTHOR` codec (base64 -> zlib -> CSV). Used by the Diagnostics ringtone reader. |
| `:app` | Compose + Material 3 UI (Glyph, Create, Settings tabs, Diagnostics), `DisplayArbiter`, rules engine, spam filter and notification listener, designs store, toys, torch tile. |

### Who owns the matrix

- Toys draw on the toy channel (`setMatrixFrame`) through `GlyphDriver`.
- Everything else goes through `DisplayArbiter` -> `MatrixDevice` -> `GlyphDriver` on the app channel
  (`setAppMatrixFrame`). `MatrixDevice` is a thin adapter; on non-Nothing phones it is a preview-only
  device and the matrix is just drawn in the app.
- Arbiter priority: torch (70) > Create preview (60) > notification (50) > progress (30); newest wins on ties.
  Diagnostics suspends the arbiter while it drives the driver directly.
- Firmware lights always win over app frames (ringtone > active toy > Nothing notification glyph > app
  frames) and the app is never told when it was covered, so the arbiter re-sends the current frame every
  3 s and immediately on screen-on / unlock.
- Notifications and progress ("ambient" requests) follow Nothing's `led_effect_enable` switch and the Glyph
  bedtime schedule (`led_effect_schedule_time_ebable`, `led_bed_time_custom_*`) when the firmware lets apps
  read them; unreadable settings never block anything. Torch and the Create preview always work.

## Build

Requirements: JDK 17+, Android SDK platform 36 (set `ANDROID_HOME` or `sdk.dir` in `local.properties`).

```
./scripts/fetch-glyph-sdk.sh      # downloads Nothing's AAR into app/libs/ (not redistributable, gitignored)
./gradlew test assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. CI (`.github/workflows/android.yml`) runs the same.

Unit tests cover the device geometry and effects, the JSON model, the Ogg/AUTHOR codec, the rules engine
(matching modes, ordering, spam, persistence), the spam filter (allow list, catches),
the rule sentence text, the designs store, the notification-access flow, the arbiter (priority, fallback,
timeouts, master switch, gate, re-send), the Glyph Button press decoder, the 12-hour clock and the bedtime
schedule.

## On-phone checks

Install the APK and open DotForge.

1. **Notification access.** Switch on the Spam filter tile; tap Open settings. If Android says the setting
   is restricted, come back: the unlock steps appear. Follow them, tap Try again, and Settings should show
   "Granted" and the red dot on the Settings tab should go.
2. **Rules.** Glyph > Rules > New rule > Save. Send yourself messages that hit each rule; the editor's
   "Would match" list shows recent notifications the rule catches. Check an incoming call (Calls preset),
   an OTP SMS, hold until dismissed, clear on unlock and Do Not Disturb.
3. **Spam filter.** Send a notification with a spam word; it should not light up and should appear under
   Recent catches; Allow it. Turn on Always allow > Contacts and grant Contacts.
4. **Live.** Progress rings during a download or a Live Update; the Torch tile and the Quick Settings tile
   (Settings > Torch in Quick Settings).
5. **Toys.** Tap each toy, Open Glyph Toys, add it. Hold a toy for its settings (12/24-hour clock, charging
   animation, shake to roll, which design DF Player plays). Check long press and double press.
6. **Create.** Create > New design > Static: dots should appear on the matrix as you draw. Make an animated
   design, save it and check DF Player plays it.
7. **Diagnostics (Settings > Advanced).** Driver test bench, Nothing settings keys, ringtone reader and toy
   event log.
8. **Firmware overlap.** While a rule or the torch shows, trigger a call / Nothing notification glyph; the
   DotForge frame should come back within a few seconds and on unlock. Turn Glyph off and set a bedtime:
   notifications should stop lighting (if the settings are readable).

## Licences

- The Nothing Glyph Matrix SDK AAR is **not** included and must not be committed or redistributed (its EULA
  forbids it). `scripts/fetch-glyph-sdk.sh` downloads it from Nothing's public repository.
- Code adapted from MIT-licensed projects is listed in `NOTICE`.
- Nothing setting key names and formats come from inspecting Phone (3) firmware behaviour; no Nothing code is included.
