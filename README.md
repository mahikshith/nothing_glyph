# DotForge

A private, personal Android app for the Nothing Phone (3) Glyph Matrix (25x25 grid, 489 round LEDs):
notification glyphs with rules and filters, progress rings, Glyph Toys, a pixel editor and a torch, plus a
Diagnostics bench for checking the hardware. Package `com.dotforge`.

## Screens

Three tabs; detail pages open full screen with a back arrow.

- **Glyph**: live matrix preview and brightness, then feature tiles. *Notifications* opens one page (below).
  *Live*: Progress rings and Torch (switches). *Toys*: tap for how to add it in Glyph Toys, hold for its
  settings. *Customise*: Ringtones (below); Essential Key, App integrations and Import are marked Coming soon.
- **Notifications** page, top to bottom:
  - *Rules*: each reads "When [apps] from [people] mention [keywords], show [glyph] until [read / unlock /
    timeout]"; slots default to Any. Preset chips add Calls, Messages, OTP codes and Delivery. The editor's
    People picker offers Contacts (asked when it first opens, with Not now), Starred and Unknown senders;
    Keywords take Any / All / None, with regular expressions under Advanced. The Glyph picker shows cards
    (pulse, ring chase, flash, sparkle, comet, app letter, sender name and every saved design) and plays the
    choice in the preview and on the real matrix. More options: priority (Low / Normal / High).
  - *Filters*: Spam filter ("Blocked today: N", words, unknown senders, always allow, patterns under
    Advanced, recent catches with Allow), Quiet hours (time range and days, plus Follow Nothing bedtime when
    readable), Respect Do Not Disturb, Only when locked, Only when face down.
  - *Rule tester*: the last 20 notifications with the rule that matched and why (or what blocked or held
    it back); tap one to replay its glyph.
  - *Nothing glyphs*: Nothing's own notification glyphs outrank ours, so a button opens Nothing's Glyph
    notification settings (or Glyph Interface, or Settings) to set those apps to Sound only.
- **Ringtones** page: the user's DotForge glyph ringtones (tap to preview and Set as, hold to delete) and a
  note to keep Nothing's Glyph ringtone setting on (the OS plays the light track itself while ringing). + gives
  *Import a file* (audio or .ogg) or *From a song* (audio only); "Open with" or Share of an audio / .ogg file
  lands in the same import page. On import:
  - a Phone (3) glyph ringtone (625 columns) keeps its lights;
  - a glyph ringtone for another phone (Phone (1) 5/15, (2) 33, (2a) 26, (3a) 36, or any other count, read as
    an ordered list of zones) is converted with a layout card: Rings, Arcs, Bars or Radial burst; timing is kept;
  - anything else is audio only, and lights are composed from it (first 40 s, faded out) with a style card:
    Pulse, Spectrum, Ripple or Sparkle.
  The preview plays the sound with the lights on screen and on the matrix, with a scrubber, Intensity and
  (for songs) Speed. Save writes an Ogg Opus file (Ogg Vorbis inputs stay Vorbis) with COMPOSER
  `v1-Metroid Glyph Composer`, AUTHOR (625 columns), CUSTOM1 (empty zlib), CUSTOM2 `625cols`, TITLE and ALBUM
  `DotForge` into `Ringtones/Compositions` through MediaStore (IS_RINGTONE). Set as: Phone ringtone (asks for
  Modify system settings then), A contact (contact picker; asks for editing Contacts then) or Notification sound
  for a rule ("... and play [name]"; it plays on the notification stream, at most 8 s, in normal ringer mode,
  when the rule's glyph shows).
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
| `:composition` | Pure Kotlin/JVM. Ogg page reader/writer and CRC check, Vorbis comment / OpusTags parser, glyph `AUTHOR` codec (base64 -> zlib -> CSV, plus Composer-style output), `OggRepager` (rewrites the comment header of an Ogg Opus / Vorbis file: keeps the identification page, re-pages the comments, renumbers later pages and recomputes CRCs), `GlyphRingtone` writer, `RingtoneConverter` (other phones' zones to the matrix), own FFT, `AudioAnalyzer` (60 fps band energies and spectral-flux onsets) and `AutoComposer`. |
| `:app` | Compose + Material 3 UI (Glyph, Create, Settings tabs, Notifications and Ringtones pages, Diagnostics), ringtone import (MediaCodec decode, Opus encode, MediaMuxer OGG), MediaStore library and Set as, `DisplayArbiter` and the notification `AlertQueue` / `AlertPlayer`, rules engine, filters and notification listener, designs store, toys, torch tile. |

### Who owns the matrix

- Toys draw on the toy channel (`setMatrixFrame`) through `GlyphDriver`.
- Everything else goes through `DisplayArbiter` -> `MatrixDevice` -> `GlyphDriver` on the app channel
  (`setAppMatrixFrame`). `MatrixDevice` is a thin adapter; on non-Nothing phones it is a preview-only
  device and the matrix is just drawn in the app.
- Arbiter priority: torch (70) > Create preview (60) > notification (50) > progress (30); newest wins on ties.
  Diagnostics suspends the arbiter while it drives the driver directly.
- Notifications reach the arbiter through one alert at a time: `Delivery` runs the spam filter, picks the
  most specific rule (person > app and words > words > app > anything, then manual priority, then list
  order) and applies quiet hours, Do Not Disturb, locked and face down. `AlertQueue` puts ringing calls first,
  keeps at most six waiting, never cuts an alert mid-animation (it switches at the end of a cycle), ignores
  re-posts of a conversation for 10 s, and keeps "until read" alerts looping until the notification is
  removed or the phone is unlocked ("until unlock" survives removal; "timeout" shows for about 6 s).
- Firmware lights always win over app frames (ringtone > active toy > Nothing notification glyph > app
  frames) and the app is never told when it was covered, so the arbiter re-sends the current frame every
  3 s, immediately on screen-on / unlock and again 1.5 s later, and 1.5 s after a notification we show
  (when Nothing's own notification glyph has finished).
- Notifications and progress ("ambient" requests) follow Nothing's `led_effect_enable` switch and the Glyph
  bedtime schedule (`led_effect_schedule_time_ebable`, `led_bed_time_custom_*`; switchable with Quiet hours >
  Follow Nothing bedtime) when the firmware lets apps read them; unreadable settings never block anything.
  Torch and the Create preview always work.
- Saved data: `rules.json` (`{"version":2,"rules":[…]}`; version 1 arrays are migrated on first load),
  `filters.json` (spam filter, catches, quiet hours and switches; replaces `spam.json`), `designs.json`.

## Build

Requirements: JDK 17+, Android SDK platform 36 (set `ANDROID_HOME` or `sdk.dir` in `local.properties`).

```
./scripts/fetch-glyph-sdk.sh      # downloads Nothing's AAR into app/libs/ (not redistributable, gitignored)
./gradlew test assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. CI (`.github/workflows/android.yml`) runs the same.

Unit tests cover the device geometry and effects, the JSON model, the Ogg/AUTHOR codec, the Ogg re-pager (page
layout, renumbering, CRCs, audio packets and granules unchanged, Vorbis setup sharing a page, audio on a header
page), the ringtone writer (read back with identical AUTHOR, Composer-style CSV and base64, empty-zlib CUSTOM1),
the converter per layout and column count, the analyser and composer (onsets on a synthetic signal,
determinism, silence, Intensity / Speed, the LED mask), the rules engine
(keyword modes, regex, people, specificity and priority, explanations, presets), rules persistence and the
version 1 migration, the spam filter (words, patterns, unknown senders, allow list, catches, spam.json
migration), quiet hours across midnight and the filter order, the alert queue (calls first, bound, no cut
mid-cycle, cooldown, hold until read / unlock, timeout) and its player, the rule sentence text, the designs
store, the notification-access flow, the arbiter (priority, fallback, timeouts, master switch, gate, re-send,
delayed re-send), the Glyph Button press decoder, the 12-hour clock and the bedtime schedule.

## On-phone checks

Install the APK and open DotForge.

1. **Notification access.** Glyph > Notifications > switch on Spam filter; tap Open settings. If Android says
   the setting is restricted, come back: the unlock steps appear. Follow them, tap Try again, and Settings
   should show "Granted" and the red dot on the Settings tab should go.
2. **Rules.** Glyph > Notifications > New rule > Save. Send yourself messages that hit each rule and watch the
   Rule tester say which rule matched and why; tap a line to replay it. Check an incoming call while a
   message glyph plays (the call goes next, the message is not cut), an OTP SMS, "until read" clearing when
   you dismiss the notification and on unlock, and a second message from the same chat within 10 s (tester
   says cooldown). Open the People slot (Contacts is asked) and the Glyph slot (the matrix follows the cards).
3. **Filters.** Send a notification with a spam word; it should not light up and should appear under the
   Spam filter with Allow. Try Block unknown senders, Quiet hours across midnight, Do Not Disturb, Only when
   locked and Only when face down; the tester names the filter that held a notification back.
4. **Sound only.** Tap Open Glyph notifications at the bottom of the Notifications page and check it lands on
   Nothing's per-app Glyph notification settings.
5. **Live.** Progress rings during a download or a Live Update; the Torch tile and the Quick Settings tile
   (Settings > Torch in Quick Settings).
6. **Toys.** Tap each toy, Open Glyph Toys, add it. Hold a toy for its settings (12/24-hour clock, charging
   animation, shake to roll, which design DF Player plays). Check long press and double press.
7. **Create.** Create > New design > Static: dots should appear on the matrix as you draw. Make an animated
   design, save it and check DF Player plays it.
8. **Ringtones.** Glyph > Ringtones > + > Import a file: try a Phone (3) Composer ringtone (lights kept), a
   Phone (1) or (2) ringtone (try each layout card) and an MP3 (From a song; try each style, Intensity and
   Speed). Lights should follow the sound on screen and on the matrix while previewing and scrubbing. Save,
   then Set as > Phone ringtone (Modify system settings is asked once), A contact (editing Contacts is asked
   once) and Notification sound (the rule sentence ends "and play [name]"). Check that Nothing's Glyph ringtone
   picker lists the file, that the lights play while ringing (Glyph ringtone setting on), and that a converted
   Phone (1) / (2) ringtone lights the matrix. Also try "Open with" and Share of an .ogg from a file manager.
9. **Diagnostics (Settings > Advanced).** Driver test bench, Nothing settings keys, ringtone reader and toy
   event log.
10. **Firmware overlap.** While a rule or the torch shows, trigger a call / Nothing notification glyph; the
   DotForge frame should come back about 1.5 s after the notification and on unlock. Turn Glyph off and set a
   bedtime: notifications should stop lighting (if the settings are readable).

## Licences

- The Nothing Glyph Matrix SDK AAR is **not** included and must not be committed or redistributed (its EULA
  forbids it). `scripts/fetch-glyph-sdk.sh` downloads it from Nothing's public repository.
- Code adapted from MIT- and Apache-2.0-licensed projects is listed in `NOTICE` (Apache licence text in
  `licenses/`).
- Nothing setting key names and formats come from inspecting Phone (3) firmware behaviour; no Nothing code is included.
