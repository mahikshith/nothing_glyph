package com.dotforge.device.library

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile

/** DotForge's own animations, drawn from the idea list (alerts, love, loaders, music, games, time, text, seasonal). */
internal object Originals {
    private val C = Categories
    private fun o(id: String, name: String, cat: Category, tags: String, make: (DeviceProfile) -> Animation, poster: ((DeviceProfile) -> com.dotforge.device.Frame)? = null) =
        LibraryItem("orig-$id", name, cat, tags.split(' '), null, ItemKind.ORIGINAL, posterOf = poster, maker = make)

    val items: List<LibraryItem> = listOf(
        // alerts
        o("ripple-ping", "Ripple ping", C.Alerts, "ripple ping notification ring", OrigAlerts::ripplePing),
        o("edge-chase", "Edge chase", C.Alerts, "edge rim chase dot notification", OrigAlerts::edgeChase),
        o("pulse-thump", "Pulse thump", C.Alerts, "pulse thump beat notification", OrigAlerts::pulseThump),
        o("call-rings", "Incoming call", C.Alerts, "call phone ring incoming", OrigAlerts::callRings),
        o("message-pop", "Message pop", C.Alerts, "message chat bubble typing sms", OrigAlerts::messagePop),
        o("envelope", "Envelope", C.Alerts, "envelope mail email message", OrigAlerts::envelope),
        o("bell", "Bell swing", C.Alerts, "bell notification alarm ring", OrigAlerts::bell, poster = OrigAlerts::bellPoster),
        o("siren", "Siren wipe", C.Alerts, "siren police emergency alarm", OrigAlerts::siren),
        o("countdown", "Countdown flash", C.Alerts, "countdown 3 2 1 timer burst", OrigAlerts::countdown),
        o("charging", "Charging bolt", C.Alerts, "charging bolt battery power", OrigAlerts::chargingBolt),
        o("low-battery", "Low battery", C.Alerts, "battery low blink warning", OrigAlerts::lowBattery),
        o("delivery", "Delivery truck", C.Alerts, "delivery truck parcel package", OrigAlerts::truck),
        o("missed-call", "Missed call", C.Alerts, "missed call phone", OrigAlerts::missedCall),
        o("calendar", "Calendar tick", C.Alerts, "calendar event reminder date", OrigAlerts::calendarTick),
        o("rim-breathing", "Rim breathing", C.Alerts, "rim breathing ring glow calm", OrigAlerts::rimBreathing),
        o("morse-sos", "Morse beacon (SOS)", C.Alerts, "morse sos beacon blink", OrigAlerts::morse),
        o("spotlight", "Spotlight sweep", C.Alerts, "spotlight sweep roam light", OrigAlerts::spotlight),
        // nature and weather
        o("sun-pulse", "Sun pulse", C.NatureWeather, "sun sunny rays weather", OrigAlerts::sunPulse),
        o("sunrise", "Sunrise", C.NatureWeather, "sunrise sun morning horizon", OrigAlerts::sunrise),
        o("twinkling-stars", "Twinkling stars", C.NatureWeather, "stars twinkle night sky", OrigAlerts::twinklingStars),
        o("shooting-star", "Shooting star", C.NatureWeather, "shooting star wish night", OrigAlerts::shootingStar),
        o("wind-gust", "Wind gust", C.NatureWeather, "wind gust breeze weather", OrigAlerts::windGust),
        o("tornado", "Tornado", C.NatureWeather, "tornado funnel storm twister", OrigAlerts::tornado),
        o("rainbow-arcs", "Rainbow arcs", C.NatureWeather, "rainbow arcs sky", OrigAlerts::rainbowArcs),
        o("falling-leaves", "Falling leaves", C.NatureWeather, "leaves autumn fall drift", OrigAlerts::fallingLeaves),
        o("fog-drift", "Fog drift", C.NatureWeather, "fog mist haze drift", OrigAlerts::fogDrift),
        o("flower", "Blooming flower", C.NatureWeather, "flower bloom spring plant", OrigAlerts::bloomingFlower),
        o("rain-splash", "Rain and splash", C.NatureWeather, "rain splash drops puddle", OrigAlerts::rainSplash),
        o("water-slosh", "Water slosh", C.NatureWeather, "water slosh level tilt", OrigAlerts::waterSlosh),
        // love, heartbreak and moods
        o("heart-fill", "Heart fill", C.Love, "heart fill love liquid", OrigLove::heartFill),
        o("heart-rain", "Heart rain", C.Love, "heart rain love falling", OrigLove::heartRain),
        o("broken-heart", "Broken heart", C.Heartbreak, "heart broken sad split", OrigLove::brokenHeart),
        o("heart-arrow", "Heart and arrow", C.Love, "heart arrow cupid love", OrigLove::heartArrow),
        o("kiss", "Blown kiss", C.Love, "kiss heart love face", OrigLove::kiss),
        o("wink", "Wink", C.Moods, "wink smile emoji face", OrigLove::wink),
        o("cry-laugh", "Crying laugh", C.Moods, "laugh cry tears emoji face", OrigLove::cryLaugh),
        o("sleepy", "Sleepy Zzz", C.Moods, "sleep zzz tired emoji face", OrigLove::sleepyZzz),
        o("surprise", "Surprise", C.Moods, "surprise shock wow emoji face", OrigLove::surprise),
        o("eye", "Looking eye", C.Moods, "eye look watch pupil", OrigLove::eyeTracker),
        o("thumbs-up", "Thumbs up", C.Moods, "thumbs up like ok", OrigLove::thumbsUp),
        o("sparkle-cheer", "Sparkle cheer", C.Moods, "sparkle cheer stars happy", OrigLove::sparkleCheer),
        o("rose-spin", "Spinning rose", C.Love, "rose flower love spin", OrigLove::roseSpin),
        // loaders
        o("radial-fill", "Radial fill", C.Loaders, "radial pie fill loader", OrigMore::radialFill),
        o("ring-progress", "Ring progress", C.Loaders, "ring progress percent loader", OrigMore::ringProgress),
        o("pulse-dots", "Pulse dots", C.Loaders, "dots pulse typing loader", OrigMore::pulseDots),
        o("bar-wave", "Bar wave", C.Loaders, "bars wave loader", OrigMore::barWave),
        o("hourglass", "Hourglass", C.Loaders, "hourglass sand wait timer", OrigMore::hourglass),
        o("snake-loader", "Snake loader", C.Loaders, "snake spiral loader", OrigMore::snakeLoader),
        o("scan-line", "Scan line", C.Loaders, "scan line sweep loader", OrigMore::scanLine),
        o("dot-grid", "Dot grid ripple", C.Loaders, "dot grid ripple loader", OrigMore::dotGridRipple),
        o("download", "Download arrow", C.Loaders, "download arrow tray loader", OrigMore::downloadArrow),
        o("battery-fill", "Battery fill", C.Loaders, "battery fill charge loader", OrigMore::batteryFill),
        o("pomodoro", "Pomodoro disc", C.Loaders, "pomodoro disc timer focus", OrigMore::pomodoro),
        // music
        o("circular-spectrum", "Circular spectrum", C.Music, "spectrum circular music audio", OrigMore::circularSpectrum),
        o("beat-pulse", "Beat pulse", C.Music, "beat pulse music bass", OrigMore::beatPulse),
        o("vinyl", "Vinyl spin", C.Music, "vinyl record disc music", OrigMore::vinyl),
        o("decibel", "Decibel meter", C.Music, "decibel meter gauge sound", OrigMore::decibel),
        o("notes", "Notes float", C.Music, "notes music float song", OrigMore::notesFloat),
        o("bass-ripple", "Bass ripple", C.Music, "bass ripple music rings", OrigMore::bassRipple),
        o("tunnel-pulse", "Tunnel pulse", C.Music, "tunnel pulse music beat", OrigMore::tunnelPulse),
        // time
        o("clock-digital", "Digital clock", C.Time, "clock digital time digits", OrigMore::digitalClock),
        o("clock-analog", "Analog clock", C.Time, "clock analog hands time", OrigMore::analogClock),
        o("clock-binary", "Binary clock", C.Time, "clock binary dots time", OrigMore::binaryClock),
        o("moon-phases", "Moon phases", C.Time, "moon phases night lunar", OrigMore::moonPhases),
        o("seconds-ring", "Seconds ring", C.Time, "seconds ring minute time", OrigMore::secondsRing),
        o("stopwatch", "Stopwatch spin", C.Time, "stopwatch sweep timer", OrigMore::stopwatch),
        o("countdown-timer", "Countdown timer", C.Time, "countdown timer minute", OrigMore::countdownTimer),
        o("sun-clock", "Sun and moon clock", C.Time, "sun moon clock day night", OrigMore::sunClock),
        o("date-flip", "Date flip", C.Time, "date calendar flip day", OrigMore::dateFlip),
        o("sleep-timer", "Sleep timer", C.Time, "sleep timer stars night fade", OrigMore::sleepTimer),
        // abstract
        o("star-warp", "Starfield warp", C.Abstract, "stars warp hyperspace speed", OrigMore::starWarp),
        o("fidget-spinner", "Fidget spinner", C.Abstract, "fidget spinner spin", OrigMore::fidgetSpinner),
        o("breathing-circle", "Breathing circle", C.Abstract, "breathing circle calm breathe", OrigMore::breathingCircle),
        // games
        o("snake", "Snake", C.Games, "snake game classic", OrigPlay::snake),
        o("pong", "Pong", C.Games, "pong paddle ball game", OrigPlay::pong),
        o("brick-breaker", "Brick breaker", C.Games, "bricks breakout ball game", OrigPlay::brickBreaker),
        o("invaders", "Space invaders", C.Games, "invaders aliens space game", OrigPlay::invaders),
        o("flappy", "Flappy dot", C.Games, "flappy bird pipes game", OrigPlay::flappy),
        o("racer", "Racer", C.Games, "racer car road game", OrigPlay::racer),
        o("blocks", "Falling blocks", C.Games, "blocks tetris stack game", OrigPlay::blocks),
        o("dice", "Dice roll", C.Games, "dice roll random game", OrigPlay::dice),
        o("magic-8", "Magic 8 ball", C.Games, "magic eight ball answer", OrigPlay::magic8),
        o("coin-flip", "Coin flip", C.Games, "coin flip heads tails", OrigPlay::coinFlip),
        o("marble", "Maze marble", C.Games, "maze marble tilt", OrigPlay::marble),
        o("reaction", "Reaction test", C.Games, "reaction test reflex", OrigPlay::reactionTest),
        o("pet", "Tiny pet", C.Games, "pet tamagotchi creature", OrigPlay::pet),
        // text
        o("marquee", "Scrolling marquee", C.Text, "marquee scroll text ticker", OrigPlay::marquee),
        o("marquee-big", "Big hello", C.Text, "hello scroll text big", OrigPlay::marqueeBig),
        o("wavy-text", "Wavy text", C.Text, "wavy text wave scroll", OrigPlay::wavyText),
        o("typewriter", "Typewriter", C.Text, "typewriter text cursor type", OrigPlay::typewriter),
        o("letter-flip", "Letter flip", C.Text, "letter flip text hi", OrigPlay::letterFlip),
        o("rain-in", "Letters rain in", C.Text, "letters rain falling text", OrigPlay::rainIn),
        o("ticker", "Ticker counter", C.Text, "ticker counter odometer digits", OrigPlay::ticker),
        o("banner-blink", "Banner blink", C.Text, "banner blink sale text", OrigPlay::bannerBlink),
        o("emoji-caption", "Emoji caption", C.Text, "emoji caption heart love text", OrigPlay::emojiCaption),
        // seasonal
        o("tree", "Christmas tree", C.Seasonal, "christmas tree lights holiday", OrigPlay::tree),
        o("pumpkin", "Glowing pumpkin", C.Seasonal, "pumpkin halloween glow", OrigPlay::pumpkin),
        o("ghost", "Friendly ghost", C.Seasonal, "ghost halloween spooky", OrigPlay::ghost),
        o("easter-egg", "Easter egg", C.Seasonal, "easter egg spring", OrigPlay::easterEgg),
        o("new-year", "New Year sparkle", C.Seasonal, "new year sparkle fireworks party", OrigPlay::newYearSparkle),
    )
}
