# DotForge

A private, personal Android app for the Nothing Phone (3) Glyph Matrix (25x25 grid, 489 round LEDs):
notification glyphs with rules and filters, call screening and a spam filter for calls and messages, progress rings, Glyph Toys, a pixel editor and a torch, plus a
Diagnostics bench for checking the hardware. Package `com.dotforge`.

## Screens

Three tabs; detail pages open full screen with a back arrow.

- **Glyph**: live matrix preview and brightness, then feature tiles. *Notifications*, *Calls* and *Spam filter*
  open their pages (below).
  *Live*: Progress rings, Torch and Music visualiser (switches; hold Music visualiser to pick Bars, Ring pulse
  or Radial spectrum). *Toys*: tap for how to add it in Glyph Toys, hold for its
  settings. *Customise*: Ringtones (below); Essential Key, App integrations and Import are marked Coming soon.
- **Notifications** page, top to bottom:
  - *Rules* (the ones that show a glyph): each reads as a sentence, "When [WhatsApp] and [sender starts with
    "+44"], show [Pulse] until [read] with [no sound] [no vibration]"; every part is a chip to tap. Preset
    chips add Calls, Messages, OTP codes and Delivery. See *The rule editor* below.
  - *Filters*: Spam filter (opens the Spam page), Quiet hours (time range and days, plus Follow Nothing
    bedtime when readable), Respect Do Not Disturb, Only when locked, Only when face down.
  - *Rule tester*: the last 20 notifications with the rule that matched and why (or what blocked or held
    it back). Tap one for Replay its glyph, Make a rule from this (app and sender filled in), Mark as spam
    (the sender goes on Blocked senders) and, for numbers, Block numbers starting with ... (the number less
    its last four digits goes on Spam prefixes).
  - *Nothing glyphs*: Nothing's own notification glyphs outrank ours, so a button opens Nothing's Glyph
    notification settings (or Glyph Interface, or Settings) to set those apps to Sound only.
- **The rule editor** is the same for notification, call and spam rules. *Applies to*: Any app, Some apps,
  Phone calls, or Calls and any app; presets (SMS, WhatsApp, Gmail, Telegram) only fill in the apps and add
  the useful field. *Add condition* goes field, then operator, then value(s):
  - fields: sender, phone number, sender email, in Contacts, starred, title or text, title, text, long text,
    subtext, summary, app, notification channel, category, group chat, ongoing; for calls also calling again
    (repeated caller), hidden number, caller ID check (STIR/SHAKEN failed) and spam list; and time, day, phone
    locked, Do Not Disturb, charging, quiet hours;
  - operators: is, is not, starts with, ends with, contains, does not contain, is in list (a named list from
    the Spam page) and, under More, matches regex and Not. Several values mean any of them (none of them for
    "is not" / "does not contain"); matching ignores case. Phone numbers compare normalised: "+91 98...",
    "0091 98..." and "098..." (in India) are the same start.
  All / Any joins the conditions; Advanced holds groups (one level: "(sender is A or sender is B)", each
  with All / Any and Not) and priority. *Then*: show a glyph (glyph, until, sound or none, vibration),
  always allow, silence it, hide it, mark as spam, and for calls reject it or reject it with no call log.
  Would match lists recent notifications the rule catches. Contacts, call screening and posting
  notifications are asked when a rule that needs them is saved, each with Not now.
- **Calls** page: a banner when DotForge is not the call screening app (Android's role dialog, asked when a
  call rule is first saved or switched on); *Call rules* (rules for phone calls, including the spam rules
  that apply to calls); *Caller glyphs*: "When [contact or Contacts group] calls, ring with [composition]"
  sets the contact's (or each group member's) ringtone to that DotForge composition through the same code as
  Set as > A contact, so the firmware plays its lights; removing one restores the default ringtone. *Options*:
  repeated callers (twice in 3 / 5 / 10 / 15 minutes), a short glyph for silenced or rejected calls (a bar or
  an X in a ring), and a missed call glyph that repeats until unlock. *Blocked calls* with Allow this
  sender, Always block and Make a rule.
- **Spam** page (calls and messages): spam rules run before every other rule. Defaults: Allowed senders,
  Contacts and Repeated callers always allowed; Blocked senders, Spam prefixes and the Spam list marked as
  spam; Spam words, Unknown senders, Hidden numbers (silence) and Faked numbers (reject) off until switched
  on. *Lists*: named lists of senders, numbers or words shared by rules (Allowed senders, Blocked senders, Spam
  words, Spam prefixes, or new ones such as VIP senders). *Spam alert*: its own glyph (default: a "!" in a
  ring, three blinks) and sound (none by default; a composition or a system sound), each switchable. *Spam
  list*: count, date, Update now and the source. *Spam log*: calls and notifications that rules stopped, with
  Allow this sender, Always block and Make a rule. The page says plainly that a message stays in its app: for
  another app's notification, silence removes it and posts a quiet copy (same tap action) in DotForge's
  "Silenced and spam" channel; hide removes it.
- **Ringtones** page: the user's DotForge glyph ringtones (tap to preview and Set as, hold to delete) and a
  note to keep Nothing's Glyph ringtone setting on (the OS plays the light track itself while ringing). + gives
  *Import a file* (audio or .ogg), *From a song* (audio only) or *Get more*; "Open with" or Share of an audio /
  .ogg file lands in the same import page. On import:
  - a Phone (3) glyph ringtone (625 columns) keeps its lights;
  - a glyph ringtone for another phone (Phone (1) 5/15, (2) 33, (2a) 26, (3a) 36, or any other count, read as
    an ordered list of zones) is converted with a layout card: Rings, Arcs, Bars or Radial burst; timing is kept;
  - anything else is audio only, and lights are composed from it with a style card: Pulse, Spectrum, Ripple or
    Sparkle. *Part of the song* shows the song's waveform with a window of up to 40 s (the loudest part at
    first): drag it to move, drag its right edge to change the length (5-40 s), tap to centre it there. The
    lights are composed for that part; it fades out when it ends before the song does.
  The preview plays the sound with the lights on screen and on the matrix, with a scrubber, Intensity and
  (for songs) Speed. Save writes an Ogg Opus file (Ogg Vorbis inputs stay Vorbis) with COMPOSER
  `v1-Metroid Glyph Composer`, AUTHOR (625 columns), CUSTOM1 (empty zlib), CUSTOM2 `625cols`, TITLE and ALBUM
  `DotForge` into `Ringtones/Compositions` through MediaStore (IS_RINGTONE). Set as: Phone ringtone (asks for
  Modify system settings then), A contact (contact picker; asks for editing Contacts then) or Notification sound
  for a rule ("... and play [name]"; it plays on the notification stream, at most 8 s, in normal ringer mode,
  when the rule's glyph shows).
  - *Edit* (on the import page of a new or saved ringtone) opens one timeline screen: waveform on top, then a
    lane per region (Whole matrix, Ring 1-4, Arc 1-4, Bar 1-5, Ring outline). Tap a lane to add a key at that
    time and brightness, drag a key to move it, tap a key for its sheet (Hold / Fade / Pulse, brightness,
    delete), Add lane adds one, Undo steps back. Play and the scrubber preview on screen and on the matrix. Save
    renders AUTHOR at 60 fps and keeps the editable timeline as JSON in CUSTOM1 (zlib + base64), so it opens
    again as lanes; a file without one opens as a single Imported lights lane that can be trimmed and scaled.
  - *Get more*: a sheet listing glyph ringtones from GitHub collections whose licence allows sharing (checked
    with GitHub's licence API, again at runtime), read through the unauthenticated contents API and cached for a
    day; when GitHub's hourly limit is used up it says so with Try again. A tapped file downloads and opens on
    the import page, so other phones' files are converted. Glyphtones opens in the browser.
- **Music visualiser**: the Live switch (and the *Music on Glyph* Quick Settings tile) runs a foreground
  service that draws what is playing on the matrix at about 30 fps, only while music plays. It listens with
  Android's Visualizer on the output mix; if that gives nothing while music plays it switches to the
  microphone and says so in its notification. The microphone permission is asked when it is first switched on,
  with Not now. The *DF Music* Glyph Toy draws the same styles (long press: next style).
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
| `:core-device` | Pure Kotlin/JVM. `DeviceProfile` (25x25 grid, round mask `(x-12)^2 + (y-12)^2 <= 153` = 489 LEDs, 68-cell outer ring clockwise from 12 o'clock, 625 <-> 489 helpers), `Frame` / `Animation`, `Effects` (pulse, ring chase, flash, sender letter, marquee, sparkle, progress ring, dice, clock, checkerboard, spam, call blocked / silenced, missed call), the 5x5 `PixelFont` / `DotText` and 3x5 `BigDigits`, and the Dot Hub / Glyph Museum JSON model (`GlyphMuseumJson`). |
| `:glyph-driver` | `GlyphDriver`: the only code that touches Nothing's `GlyphMatrixManager`. Process-wide, ref-counted, one IO thread, newest-frame-wins per channel (app / toy), reconnect with backoff, `StateFlow` state, fps and call log. Frames are 0-255; the driver rescales to the SDK's raw 0-4095 range. |
| `:composition` | Pure Kotlin/JVM. Ogg page reader/writer and CRC check, Vorbis comment / OpusTags parser, glyph `AUTHOR` codec (base64 -> zlib -> CSV, plus Composer-style output), `OggRepager` (rewrites the comment header of an Ogg Opus / Vorbis file: keeps the identification page, re-pages the comments, renumbers later pages and recomputes CRCs), `GlyphRingtone` writer, `RingtoneConverter` (other phones' zones to the matrix), own FFT, `AudioAnalyzer` (60 fps band energies and spectral-flux onsets) and `AutoComposer`, `SongWindow` / `PeakEnvelope`, the editable `Timeline` (lanes of keys, `TimelineRenderer`, CUSTOM1 `TimelineCodec`), and the visualiser's `SpectrumBands`, `LiveLevels` and `VisualiserRenderer`. |
| `:app` | Compose + Material 3 UI (Glyph, Create, Settings tabs, Notifications and Ringtones pages, Diagnostics), ringtone import (MediaCodec decode, Opus encode, MediaMuxer OGG), MediaStore library and Set as, timeline editor, Get more catalog (GitHub contents API), music visualiser engine (Visualizer / microphone), Live service and tile, `DisplayArbiter` and the notification `AlertQueue` / `AlertPlayer`, filter engine (`filter/`: fields, operators, named lists, sender extraction), rules engine, filters and notification listener, call screening service, phone number normaliser, spam list store and weekly worker, caller glyphs, designs store, toys, torch tile. |

### Who owns the matrix

- Toys draw on the toy channel (`setMatrixFrame`) through `GlyphDriver`.
- Everything else goes through `DisplayArbiter` -> `MatrixDevice` -> `GlyphDriver` on the app channel
  (`setAppMatrixFrame`). `MatrixDevice` is a thin adapter; on non-Nothing phones it is a preview-only
  device and the matrix is just drawn in the app.
- Arbiter priority: torch (70) > Create preview (60) > notification (50) > progress (30); newest wins on ties.
  Diagnostics suspends the arbiter while it drives the driver directly.
- Notifications reach the arbiter through one alert at a time: `Delivery` turns the notification into a
  filter `Subject` (sender from MessagingStyle messages, the title, the conversation title or the people URIs;
  number normalised to E.164; email from a mailto: person), then `RuleEngine.decide` runs the allow and
  blocking rules first (the most specific decides; allow wins a tie), then the most specific rule that shows
  (exact sender > person > app and words > words > app > caller flags > anything, then manual priority, rules
  limited by time first, then list order), then quiet hours, Do Not Disturb, locked and face down. Calls take
  the same path from `GlyphCallScreeningService` (contacts, the spam list and the phone's state are read only
  when a condition needs them). `AlertQueue` puts ringing calls first,
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
- Saved data: `rules.json` (`{"version":3,"rules":[…],"lists":[…]}`), `filters.json` (spam alert, Spam log,
  quiet hours and switches; replaces `spam.json`), `calls.json` (call options, recent calls for repeated
  callers, caller glyphs), `spam-list/` (the downloaded list, its ETag and version), `designs.json`.
  Version 1 rules (a bare array) and version 2 rules (apps / people / keywords slots) are migrated once into
  filter-engine rules without losing a setting; the version 2 spam filter's words, patterns, unknown senders
  and allowed senders become the spam rules and the Spam words / Allowed senders lists. The project has no
  database, so this is a JSON migration with tests rather than a Room migration.
- The spam list is CallShield's `data/spam_numbers.txt` from GitHub (MIT; US FCC / FTC complaint data and
  community reports; no key or paid API): fetched by WorkManager when a rule first uses it, then weekly on any
  network, with ETag, a 16 MB cap and a sanity check; kept as a sorted array for binary search.

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
determinism, silence, Intensity / Speed, the LED mask), the song window (limits, loudest part, moves and
resizes, envelope), the timeline (key shapes, regions, render to AUTHOR, imported lane trim and scale, CUSTOM1
round trip through the writer and parser, other apps' CUSTOM1 ignored) and its editor state (undo), the
visualiser band mapping (band edges, a sine lights its band, Visualizer FFT bytes, levels, frames inside the
mask), the Get more catalog (licence filter, contents parsing, rate limits, cache), the rules engine
(scope per source, specificity and priority, spam before showing, the default spam rules, spam prefixes,
call rules, explanations, lazy contact lookups), the filter engine (every operator, number normalisation in
starts / ends / contains / is, named lists, All / Any / Not, nested groups, flags, time ranges across
midnight, days, descriptions), sender extraction (SMS, WhatsApp direct and group, Gmail-style), phone number
normalisation (E.164 and prefixes), the spam list parser, the repeated-caller window, rules persistence and
the version 1 and 2 migrations (including the old spam settings), named lists, the Spam log and spam.json
migration, call settings and caller glyphs, quiet hours across midnight and the filter order, the alert queue (calls first, bound, no cut
mid-cycle, cooldown, hold until read / unlock, timeout) and its player, the rule sentence text, the designs
store, the notification-access flow, the arbiter (priority, fallback, timeouts, master switch, gate, re-send,
delayed re-send), the Glyph Button press decoder, the 12-hour clock and the bedtime schedule.

## On-phone checks

Install the APK and open DotForge.

1. **Notification access.** Glyph > Notifications > switch a rule off and on; tap Open settings. If Android says
   the setting is restricted, come back: the unlock steps appear. Follow them, tap Try again, and Settings
   should show "Granted" and the red dot on the Settings tab should go.
2. **Rules.** Glyph > Notifications > New rule > Save. Send yourself messages that hit each rule and watch the
   Rule tester say which rule matched and why; tap a line to replay it. Check an incoming call while a
   message glyph plays (the call goes next, the message is not cut), an OTP SMS, "until read" clearing when
   you dismiss the notification and on unlock, and a second message from the same chat within 10 s (tester
   says cooldown). Add a condition (sender is ..., then an "in Contacts" one: Contacts is asked on Save), try
   All / Any, a group under Advanced, and the Glyph chip (the matrix follows the cards).
3. **Filters.** Glyph > Spam filter: switch on Spam words and send yourself a notification with one; it
   should not light up, should be replaced by a quiet copy in the shade (allow posting notifications when
   asked) and should appear in the Spam log. Try Allow this sender, Always block and Make a rule. Make a
   WhatsApp rule "sender is ...", a Gmail rule "sender email ends with ..." and an SMS rule "phone number
   starts with ..." and check the Rule tester names them (Gmail's sender fields are not yet checked on a
   phone). Try Quiet hours across midnight, Do Not Disturb, Only when locked and Only when face down; the
   tester names the filter that held a notification back.
4. **Calls.** Glyph > Calls > New call rule (e.g. Phone calls and phone number starts with your own prefix,
   reject it) > Save: allow DotForge as the call screening app, and Contacts. Call the phone from another
   number: it should be rejected, appear under Blocked calls and show the short glyph. Call twice within 5
   minutes from a number a rule silences: the second call rings. Check silence (rings silently), reject with no
   call log, a contact still ringing, and a hidden number with Hidden numbers on. Spam filter > Spam list >
   Update now: the count appears. Add a caller glyph for a contact and a Contacts group, then call from that
   contact: the composition rings and its lights play. Switch on Missed call glyph and miss a call: the glyph
   repeats until you unlock.
5. **Sound only.** Tap Open Glyph notifications at the bottom of the Notifications page and check it lands on
   Nothing's per-app Glyph notification settings.
6. **Live.** Progress rings during a download or a Live Update; the Torch tile and the Quick Settings tile
   (Settings > Torch in Quick Settings).
7. **Toys.** Tap each toy, Open Glyph Toys, add it. Hold a toy for its settings (12/24-hour clock, charging
   animation, shake to roll, which design DF Player plays). Check long press and double press.
8. **Create.** Create > New design > Static: dots should appear on the matrix as you draw. Make an animated
   design, save it and check DF Player plays it.
9. **Ringtones.** Glyph > Ringtones > + > Import a file: try a Phone (3) Composer ringtone (lights kept), a
   Phone (1) or (2) ringtone (try each layout card) and an MP3 (From a song; try each style, Intensity and
   Speed). Lights should follow the sound on screen and on the matrix while previewing and scrubbing. Save,
   then Set as > Phone ringtone (Modify system settings is asked once), A contact (editing Contacts is asked
   once) and Notification sound (the rule sentence ends "and play [name]"). Check that Nothing's Glyph ringtone
   picker lists the file, that the lights play while ringing (Glyph ringtone setting on), and that a converted
   Phone (1) / (2) ringtone lights the matrix. Also try "Open with" and Share of an .ogg from a file manager.
   From a song: drag the window under Part of the song and check the lights follow the new part (the saved
   file starts there and fades out). Tap Edit: add keys on a few lanes, drag them, change a shape, Undo,
   Play and scrub (the matrix follows), Save; open the ringtone again and tap Edit: the lanes come back. Edit
   on a Phone (1) ringtone shows one Imported lights lane; trim and scale it. + > Get more: the list loads,
   a file opens on the import page and converts; Glyphtones opens in the browser.
10. **Music visualiser.** Play music in any app, then Glyph > Music visualiser on (allow the microphone, or
   Not now). The matrix should follow the music within a second and go back when it stops; hold the tile for
   the styles. If the notification says it uses the microphone, Android gave no music data on this phone.
   Try the Music on Glyph Quick Settings tile, and add DF Music in Glyph Toys (long press changes style).
11. **Diagnostics (Settings > Advanced).** Driver test bench, Nothing settings keys, ringtone reader and toy
   event log.
12. **Firmware overlap.** While a rule or the torch shows, trigger a call / Nothing notification glyph; the
   DotForge frame should come back about 1.5 s after the notification and on unlock. Turn Glyph off and set a
   bedtime: notifications should stop lighting (if the settings are readable).

## Licences

- The Nothing Glyph Matrix SDK AAR is **not** included and must not be committed or redistributed (its EULA
  forbids it). `scripts/fetch-glyph-sdk.sh` downloads it from Nothing's public repository.
- Code adapted from MIT- and Apache-2.0-licensed projects is listed in `NOTICE` (Apache licence text in
  `licenses/`).
- Nothing setting key names and formats come from inspecting Phone (3) firmware behaviour; no Nothing code is included.
