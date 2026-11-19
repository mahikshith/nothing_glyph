package com.dotforge.device.library

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.BackgroundEffect
import com.dotforge.device.BackgroundLayer
import com.dotforge.device.BehindText
import com.dotforge.device.Compositor
import com.dotforge.device.DeviceProfile
import com.dotforge.device.DotFont
import com.dotforge.device.DotFonts
import com.dotforge.device.Frame
import com.dotforge.device.Layers
import com.dotforge.device.TextBand
import com.dotforge.device.TextDirection
import com.dotforge.device.TextLayer
import com.dotforge.device.TextRender
import com.dotforge.device.TextSpec
import com.dotforge.device.TextStyle
import com.dotforge.device.TextStyle.ASSEMBLE
import com.dotforge.device.TextStyle.BOUNCE
import com.dotforge.device.TextStyle.CURSOR
import com.dotforge.device.TextStyle.FADE
import com.dotforge.device.TextStyle.FIT
import com.dotforge.device.TextStyle.FLASH
import com.dotforge.device.TextStyle.GLITCH
import com.dotforge.device.TextStyle.MARQUEE
import com.dotforge.device.TextStyle.RAIN
import com.dotforge.device.TextStyle.SCROLL
import com.dotforge.device.TextStyle.TYPEWRITER
import com.dotforge.device.TextStyle.WAVE
import com.dotforge.device.TextStyle.WORDS
import com.dotforge.device.TextStyle.ZOOM

/**
 * Ready text designs (they open in the Text screen) and glyph + text designs (a glyph-art background with words
 * in front; they open in the editor with both layers). Every one is DotForge's own.
 */
internal object TextItems {
    private val C = Categories

    /** The Text category's groups, in display order. */
    val groups = listOf("Greetings", "Love", "Status", "Reactions", "Celebrations", "Utility", "Hinglish")

    /** Widest word that plays in place on the whole Glyph (less with a border), and under a glyph in the bottom band. */
    const val FULL_WIDTH = 23
    const val BORDER_WIDTH = 19
    const val BAND_WIDTH = 19

    /** A glyph + text loop finishes within this, so long words scroll a little faster. */
    const val LOOP_MS = 4_000

    private fun slug(s: String) = s.lowercase().replace('♥', 'h').replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** A sensible style for [text] when none is given: short words pop in place, longer ones scroll. */
    private fun auto(text: String): TextStyle = if (DotFonts.width(DotFont.REGULAR, text) <= FULL_WIDTH) BOUNCE else SCROLL

    private fun text(
        cat: Category, group: String?, text: String, tags: String, style: TextStyle? = null,
        dir: TextDirection = TextDirection.LEFT, font: DotFont = DotFont.REGULAR, border: Boolean = false,
    ): LibraryItem {
        // Wave bends letters out of shape on 25 rows, so library texts don't use it
        val wanted = style?.takeIf { it != WAVE } ?: auto(text)
        // Styles that play in place split a word that is too wide; such words scroll instead.
        val limit = if (border || wanted == MARQUEE) BORDER_WIDTH else FULL_WIDTH
        val tooWide = text.split(' ').any { DotFonts.width(font, it) > limit }
        val st = if (!wanted.moves && wanted != TYPEWRITER && wanted != CURSOR && tooWide) SCROLL else wanted
        val spec = TextSpec(text = text, font = font, style = st, direction = dir, border = border)
        val id = "text-" + (if (cat == C.Text) "" else cat.id + "-") + slug(text)
        val words = (tags + " text words " + (group ?: "") + " " + text).lowercase().split(' ').filter { it.isNotBlank() }.distinct()
        return LibraryItem(id, text, cat, words, null, ItemKind.ORIGINAL, ItemForm.TEXT, group, spec, posterOf = { p -> TextRender.poster(p, spec) }) { p -> TextRender.render(p, spec) }
    }

    private fun T(group: String, s: String, tags: String, style: TextStyle? = null, dir: TextDirection = TextDirection.LEFT, font: DotFont = DotFont.REGULAR, border: Boolean = false) =
        text(C.Text, group, s, tags, style, dir, font, border)

    val texts: List<LibraryItem> by lazy {
        listOf(
            // Greetings
            T("Greetings", "HELLO", "hello hi greeting", WAVE),
            T("Greetings", "HI!", "hi hello hey greeting", BOUNCE, font = DotFont.BIG),
            T("Greetings", "GOOD MORNING", "good morning gm wake greeting", SCROLL),
            T("Greetings", "GOOD NIGHT", "good night gn sleep greeting", FADE),
            T("Greetings", "GOOD EVENING", "good evening greeting", SCROLL),
            T("Greetings", "WELCOME", "welcome hello home greeting", MARQUEE, border = true),
            T("Greetings", "HEY THERE", "hey there hi hello greeting", WAVE),
            T("Greetings", "NAMASTE", "namaste hello india greeting", ASSEMBLE),
            T("Greetings", "BYE!", "bye goodbye see you later", ZOOM),
            T("Greetings", "SEE YOU SOON", "see you soon bye later", SCROLL),
            T("Greetings", "GM", "gm good morning short greeting", FIT, font = DotFont.BOLD),
            T("Greetings", "GN", "gn good night short greeting sleep", FIT, font = DotFont.BOLD),
            // Love
            T("Love", "I ♥ U", "i love you heart love", BOUNCE),
            T("Love", "LOVE YOU", "love you heart", FADE),
            T("Love", "MISS YOU", "miss you love far", SCROLL),
            T("Love", "XOXO", "xoxo hugs kisses love", FLASH),
            T("Love", "BE MINE", "be mine valentine love", RAIN),
            T("Love", "HUGS", "hugs hug love care", ZOOM),
            T("Love", "♥ ♥ ♥", "hearts love", WAVE),
            T("Love", "YOU + ME", "you and me couple love", ASSEMBLE),
            T("Love", "MWAH", "mwah kiss love", BOUNCE),
            // Status
            T("Status", "BUSY", "busy dnd status", FLASH, border = true),
            T("Status", "IN A MEETING", "meeting busy work status", SCROLL),
            T("Status", "DRIVING", "driving car road status", SCROLL),
            T("Status", "AT THE GYM", "gym workout status", SCROLL),
            T("Status", "ON A CALL", "call phone busy status", MARQUEE, border = true),
            T("Status", "BRB", "brb be right back status", FIT, font = DotFont.BOLD),
            T("Status", "AFK", "afk away status", FIT, font = DotFont.BOLD),
            T("Status", "AWAY", "away out status", FADE),
            T("Status", "SLEEPING", "sleeping zzz night status", FADE),
            T("Status", "ON MY WAY", "on my way omw coming status", SCROLL),
            T("Status", "WFH", "wfh work from home status", FIT, font = DotFont.BOLD),
            // Reactions
            T("Reactions", "LOL", "lol laugh funny reaction", BOUNCE, font = DotFont.BOLD),
            T("Reactions", "OMG", "omg wow shock reaction", ZOOM, font = DotFont.BOLD),
            T("Reactions", "WOW", "wow amazing reaction", ZOOM),
            T("Reactions", "YAY!", "yay happy cheer reaction", BOUNCE),
            T("Reactions", "NOPE", "nope no reaction", GLITCH),
            T("Reactions", "OK", "ok okay fine reaction", FIT, font = DotFont.BIG),
            T("Reactions", "HAHA", "haha laugh funny reaction", WAVE),
            T("Reactions", "WHAT?!", "what huh shock reaction", GLITCH),
            T("Reactions", "COOL", "cool nice reaction", FADE),
            T("Reactions", "YES!", "yes yeah agree reaction", FLASH),
            T("Reactions", "NO!", "no disagree reaction", FLASH),
            T("Reactions", "SAME", "same me too reaction", BOUNCE),
            // Celebrations
            T("Celebrations", "HAPPY BIRTHDAY", "happy birthday bday party celebrate", MARQUEE, border = true),
            T("Celebrations", "CONGRATS!", "congrats congratulations well done celebrate", RAIN),
            T("Celebrations", "CHEERS", "cheers toast party celebrate", WAVE),
            T("Celebrations", "HAPPY NEW YEAR", "happy new year party celebrate", SCROLL),
            T("Celebrations", "PARTY TIME", "party time celebrate fun", WAVE),
            T("Celebrations", "WELL DONE", "well done great job celebrate", ASSEMBLE),
            T("Celebrations", "HAPPY DIWALI", "happy diwali festival lights celebrate", MARQUEE, border = true),
            T("Celebrations", "MERRY CHRISTMAS", "merry christmas xmas festival celebrate", SCROLL),
            T("Celebrations", "EID MUBARAK", "eid mubarak festival celebrate", SCROLL),
            T("Celebrations", "HAPPY HOLI", "happy holi colours festival celebrate", WAVE),
            T("Celebrations", "HBD", "hbd happy birthday short bday celebrate", ZOOM, font = DotFont.BOLD),
            T("Celebrations", "XMAS", "xmas christmas short festival celebrate", FADE),
            T("Celebrations", "HNY", "hny happy new year short party celebrate", ZOOM, font = DotFont.BOLD),
            // Utility
            T("Utility", "CHARGING", "charging battery power", TYPEWRITER),
            T("Utility", "LOW BATTERY", "low battery charge power", FLASH),
            T("Utility", "NEW MESSAGE", "new message sms chat", SCROLL),
            T("Utility", "CALL ME", "call me phone", FLASH),
            T("Utility", "CALL BACK", "call back phone missed", SCROLL),
            T("Utility", "TIMER DONE", "timer done time up alarm", FLASH),
            T("Utility", "SILENT", "silent mute quiet", FADE),
            T("Utility", "REMINDER", "reminder alert note", MARQUEE, border = true),
            T("Utility", "PICK UP!", "pick up answer call phone", FLASH),
            // Hinglish
            T("Hinglish", "CHAL BHAI", "chal bhai lets go hinglish", SCROLL),
            T("Hinglish", "KYA SCENE?", "kya scene whats up hinglish", WAVE),
            T("Hinglish", "JALDI AAO", "jaldi aao come fast hurry hinglish", FLASH),
            T("Hinglish", "ARRE YAAR", "arre yaar oh man hinglish", SCROLL),
            T("Hinglish", "CHAI PE CHALO?", "chai pe chalo tea break hinglish", SCROLL),
            T("Hinglish", "SAB BADHIYA", "sab badhiya all good fine hinglish", WAVE),
            T("Hinglish", "ACHHA JI", "achha ji okay really hinglish", BOUNCE),
            T("Hinglish", "PAKKA?", "pakka sure promise hinglish", ZOOM),
            T("Hinglish", "KAHAN HO?", "kahan ho where are you hinglish", SCROLL),
            T("Hinglish", "MAST", "mast awesome great hinglish", BOUNCE, font = DotFont.BOLD),
            T("Hinglish", "BAS KAR", "bas kar stop enough hinglish", GLITCH),
        ) + listOf(
            // matching words for each glyph category
            text(C.Anime, null, "KAWAII", "kawaii cute anime", WAVE),
            text(C.Anime, null, "SUGOI!", "sugoi amazing anime", ZOOM),
            text(C.Anime, null, "UwU", "uwu cute soft anime", BOUNCE),
            text(C.Utility, null, "NO WIFI", "no wifi offline internet", FLASH),
            text(C.Utility, null, "CHARGE ME", "charge battery low power", FLASH),
            text(C.Utility, null, "LOCKED", "locked private secure", FADE),
            text(C.Love, null, "LOVE YOU", "love you heart", WAVE),
            text(C.Love, null, "MY ♥", "my heart love", BOUNCE),
            text(C.Love, null, "FOREVER", "forever always love", FADE),
            text(C.Heartbreak, null, "IT'S OVER", "its over breakup heartbreak", FADE),
            text(C.Heartbreak, null, "WHY?", "why sad heartbreak", GLITCH),
            text(C.Heartbreak, null, "SAD :(", "sad unhappy heartbreak", FADE),
            text(C.Moods, null, "HAPPY", "happy glad mood", BOUNCE),
            text(C.Moods, null, "MEH", "meh bored mood", FIT, font = DotFont.BOLD),
            text(C.Moods, null, "GRR", "grr angry mad mood", GLITCH, font = DotFont.BOLD),
            text(C.Seasonal, null, "PARTY!", "party celebrate fun", RAIN),
            text(C.Seasonal, null, "CHEERS!", "cheers toast celebrate", WAVE),
            text(C.Seasonal, null, "HAPPY BDAY", "happy birthday bday celebrate", MARQUEE, border = true),
            text(C.Sports, null, "GOAL!", "goal football score sports", ZOOM, font = DotFont.BOLD),
            text(C.Sports, null, "SIXER!", "sixer six cricket sports", ZOOM),
            text(C.Sports, null, "GAME ON", "game on match sports", SCROLL),
            text(C.Food, null, "FOOD'S HERE", "food here delivery order", SCROLL),
            text(C.Food, null, "ORDER READY", "order ready pickup food", SCROLL),
            text(C.Food, null, "HUNGRY", "hungry food eat", WAVE),
            text(C.Travel, null, "ON MY WAY", "on my way omw travel", SCROLL),
            text(C.Travel, null, "CAB'S HERE", "cab here taxi ride travel", FLASH),
            text(C.Travel, null, "LANDED", "landed flight arrived travel", ZOOM),
            text(C.Work, null, "FOCUS", "focus work study", FADE),
            text(C.Work, null, "DEADLINE", "deadline work due", FLASH),
            text(C.Work, null, "EXAM DAY", "exam day study school", SCROLL),
            text(C.Time, null, "WAKE UP!", "wake up morning alarm", FLASH),
            text(C.Time, null, "TAKE MEDS", "take meds medicine reminder", MARQUEE, border = true),
            text(C.Time, null, "DRINK WATER", "drink water hydrate reminder", SCROLL),
            text(C.NatureWeather, null, "RAINY DAY", "rainy day rain weather", SCROLL),
            text(C.NatureWeather, null, "SUNNY", "sunny sun weather", BOUNCE),
            text(C.NatureWeather, null, "STAY WARM", "stay warm cold winter weather", SCROLL),
        )
    }

    // ---------------- Glyph + text ----------------

    private class Pair2(val glyph: String, val words: String, val tags: String, val style: TextStyle?, val effect: BackgroundEffect)

    private fun G(glyph: String, words: String, tags: String, style: TextStyle? = null, effect: BackgroundEffect = BackgroundEffect.STILL) =
        Pair2(glyph, words, tags, style, effect)

    private val pairs = listOf(
        G("g-heart", "I ♥ U", "i love you heart love", effect = BackgroundEffect.BREATHE),
        G("g-cake", "HAPPY BDAY", "happy birthday cake bday"),
        G("g-diya", "HAPPY DIWALI", "happy diwali diya festival lights", effect = BackgroundEffect.BREATHE),
        G("g-bedtime", "GN", "good night gn moon sleep"),
        G("g-car", "ON MY WAY", "on my way car ride omw"),
        G("g-food-bag", "FOOD'S HERE", "food here bag delivery order"),
        G("g-bell", "REMINDER", "reminder bell alert", effect = BackgroundEffect.BREATHE),
        G("g-takeaway-cup", "BRB", "brb coffee break be right back"),
        G("g-cricket", "SIXER", "sixer cricket bat six ipl"),
        G("g-gift", "FOR YOU", "for you gift present surprise"),
        G("g-balloons", "PARTY", "party balloons celebrate"),
        G("g-trophy", "WINNER", "winner trophy champion"),
        G("g-football", "GOAL!", "goal football score"),
        G("g-sun", "GOOD MORNING", "good morning sun gm"),
        G("g-rain", "RAINY DAY", "rainy day rain weather"),
        G("g-umbrella", "UMBRELLA", "take umbrella rain reminder weather"),
        G("g-parcel", "DELIVERED", "delivered parcel package courier"),
        G("g-grocery-basket", "GROCERIES", "groceries basket shopping order"),
        G("g-scooter", "ARRIVING", "arriving delivery scooter order"),
        G("g-plane", "LANDED", "landed plane flight travel"),
        G("g-suitcase", "TRIP TIME", "trip time suitcase holiday travel"),
        G("g-pin", "I'M HERE", "im here location pin arrived"),
        G("g-laptop", "WFH", "wfh work from home laptop"),
        G("g-briefcase", "AT WORK", "at work office briefcase busy"),
        G("g-book", "STUDYING", "studying study book exam"),
        G("g-grad-cap", "PASSED!", "passed exam graduation results"),
        G("g-alarm", "WAKE UP", "wake up alarm morning", effect = BackgroundEffect.BREATHE),
        G("g-pill", "TAKE MEDS", "take meds medicine pill reminder"),
        G("g-water", "DRINK WATER", "drink water glass hydrate reminder"),
        G("g-hourglass", "WAIT", "wait hourglass patience"),
        G("g-heart-broken", "IT'S OVER", "its over broken heart breakup heartbreak", FADE),
        G("g-teardrop", "MISS YOU", "miss you tear sad"),
        G("g-ghosted", "BOO!", "boo ghost halloween ghosted"),
        G("g-anime-sparkle", "KAWAII", "kawaii anime cute sparkle"),
        G("g-anime-joy", "YAY!", "yay anime happy joy"),
        G("g-mood-angry", "GRR", "grr angry mad mood", GLITCH),
        G("g-mood-cool", "CHILL", "chill cool relax mood"),
        G("g-mood-sleepy", "ZZZ", "zzz sleepy tired sleep", FADE),
        G("g-cheers", "HAPPY NEW YEAR", "happy new year cheers party"),
        G("g-ring", "MARRY ME?", "marry me ring propose engagement"),
        G("g-lips", "MWAH", "mwah kiss lips love"),
        G("g-battery-low", "CHARGE ME", "charge me battery low power"),
        G("g-wifi", "NO WIFI", "no wifi offline internet", FLASH),
        G("g-dumbbell", "GYM TIME", "gym time workout fitness"),
        G("g-shoe", "LET'S RUN", "lets run running jog fitness"),
        G("g-kite", "SANKRANTI", "happy sankranti kite festival uttarayan"),
        G("g-holi-splash", "HOLI", "happy holi colours festival splash"),
        G("g-eid-crescent", "EID", "eid mubarak moon crescent festival"),
        G("g-xmas-tree", "XMAS", "merry christmas xmas tree festival"),
        G("g-rakshabandhan-rakhi", "RAKHI", "raksha bandhan rakhi sister brother festival"),
    )

    /**
     * The glyph shrunk into rows 1-13 and the words in the bottom band (rows 14-20), where the circle is wide
     * enough. Words that fit hold still and centred; longer ones scroll fast enough to finish in [LOOP_MS]. The
     * picture never goes fully dark (a blink becomes a breathe), and nothing cuts into it: the words sit below it.
     */
    fun layersFor(p: DeviceProfile, glyph: String, words: String, style: TextStyle?, effect: BackgroundEffect): Layers {
        val picture = GlyphArt.shrink(GlyphArt.still(glyph, p), p)
        val name = GlyphArt.entry(glyph)?.name ?: glyph
        val still = DotFonts.width(DotFont.SMALL, words) <= BAND_WIDTH
        val spec = TextSpec(text = words, style = if (still) FIT else SCROLL, band = TextBand.BOTTOM, pauseMs = if (still) 900 else 400)
        val speed = if (still) 1f else {
            val steps = p.size + DotFonts.width(DotFont.REGULAR, words) + 1
            ((steps * TextRender.STEP_MS + spec.pauseMs) / LOOP_MS.toFloat()).coerceIn(1f, 2f)
        }
        return Layers(
            background = BackgroundLayer(Animation.single(picture, 1000).toMuseumJson(), name, if (effect == BackgroundEffect.BLINK) BackgroundEffect.BREATHE else effect),
            text = TextLayer(spec, speed = speed),
            // the words sit below the picture, so nothing needs cutting out of it
            behind = BehindText.CLEAR,
        )
    }

    /** The still a tile shows: the picture with the words whole (or their first screenful). */
    fun posterFor(p: DeviceProfile, layers: Layers): Frame {
        val bg = layers.background?.let { Animation.fromMuseumJson(p, it.frames).frames.first().frame.pixels }
        val text = layers.text?.let { TextRender.poster(p, it.spec).pixels }
        return Frame.of(p, Compositor.compose(p, layers.behind, layers.text?.spec?.band ?: TextBand.BOTTOM, bg, text, null))
    }

    val layered: List<LibraryItem> by lazy {
        pairs.map { g ->
            val words = (g.tags + " glyph text " + g.words).lowercase().split(' ').filter { it.isNotBlank() }.distinct()
            val make = { p: DeviceProfile -> layersFor(p, g.glyph, g.words, g.style, g.effect) }
            LibraryItem("gt-" + slug(g.words), g.words, C.GlyphText, words, null, ItemKind.ORIGINAL, ItemForm.LAYERED, null, null, make, posterOf = { p -> posterFor(p, make(p)) }) { p ->
                Compositor.render(p, make(p), Animation.single(Frame.blank(p), 1000)).let { a ->
                    if (a.frames.isEmpty()) Animation(p, listOf(AnimFrame(Frame.blank(p), 1000))) else a
                }
            }
        }
    }
}
