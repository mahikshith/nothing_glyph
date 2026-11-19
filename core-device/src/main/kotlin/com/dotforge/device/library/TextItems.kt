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

    /** About how long one pass of [text] scrolling across takes at Normal speed. */
    private fun scrollMs(text: String, font: DotFont) = (25 + DotFonts.width(font, text) + 1) * TextRender.STEP_MS + 600

    private fun text(
        cat: Category, group: String?, text: String, tags: String, style: TextStyle? = null,
        dir: TextDirection = TextDirection.LEFT, font: DotFont = DotFont.REGULAR, border: Boolean = false,
    ): LibraryItem {
        // Wave bends letters out of shape on 25 rows, so library texts don't use it
        val wanted = style?.takeIf { it != WAVE } ?: auto(text)
        val limit = if (border || wanted == MARQUEE) BORDER_WIDTH else FULL_WIDTH
        val parts = text.split(' ')
        fun fits(f: DotFont) = parts.all { DotFonts.width(f, it) <= limit }
        var f = font
        var st = wanted
        // Styles that play in place would split a word that is too wide: such words drop to Small, else scroll.
        if ((!wanted.moves || wanted == MARQUEE) && wanted != TYPEWRITER && wanted != CURSOR && !fits(font)) {
            if (fits(DotFont.SMALL)) f = DotFont.SMALL else st = SCROLL
        }
        // A scroll that takes much over LOOP_MS shows its words in turn instead, each one still and whole.
        if (st == SCROLL && scrollMs(text, f) > LOOP_MS + 400 && (fits(f) || fits(DotFont.SMALL))) {
            st = if (parts.size == 1) BOUNCE else WORDS
            if (!fits(f)) f = DotFont.SMALL
        }
        val spec = TextSpec(text = text, font = f, style = st, direction = dir, border = border)
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
            T("Greetings", "GOOD NIGHT", "good night gn sleep greeting", FADE),
            T("Greetings", "COME IN", "come in welcome hello home greeting", MARQUEE, border = true),
            T("Greetings", "HEY THERE", "hey there hi hello greeting", WAVE),
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
            T("Status", "ON THE ROAD", "on the road driving car status", SCROLL),
            T("Status", "AT THE GYM", "gym workout status", SCROLL),
            T("Status", "ON A CALL", "call phone busy status", MARQUEE, border = true),
            T("Status", "BRB", "brb be right back status", FIT, font = DotFont.BOLD),
            T("Status", "AFK", "afk away status", FIT, font = DotFont.BOLD),
            T("Status", "AWAY", "away out status", FADE),
            T("Status", "SLEEP", "asleep sleeping sleep zzz night status", FADE),
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
            T("Celebrations", "BRAVO!", "bravo congrats congratulations well done celebrate", RAIN),
            T("Celebrations", "CLINK!", "cheers clink toast party celebrate", WAVE),
            T("Celebrations", "NEW YEAR", "happy new year party celebrate", SCROLL),
            T("Celebrations", "PARTY TIME", "party time celebrate fun", WAVE),
            T("Celebrations", "WELL DONE", "well done great job celebrate", ASSEMBLE),
            T("Celebrations", "HAPPY DIWALI", "happy diwali festival lights celebrate", FADE),
            T("Celebrations", "MERRY XMAS", "merry christmas xmas festival celebrate", SCROLL),
            T("Celebrations", "HAPPY EID", "happy eid mubarak festival celebrate", SCROLL),
            T("Celebrations", "HAPPY HOLI", "happy holi colours festival celebrate", WAVE),
            T("Celebrations", "HBD", "hbd happy birthday short bday celebrate", ZOOM, font = DotFont.BOLD),
            T("Celebrations", "XMAS", "xmas christmas short festival celebrate", FADE),
            T("Celebrations", "HNY", "hny happy new year short party celebrate", ZOOM, font = DotFont.BOLD),
            // Utility
            T("Utility", "POWER UP", "power up charging battery", TYPEWRITER),
            T("Utility", "LOW BATT", "low power battery charge batt", FLASH),
            T("Utility", "NEW TEXT", "new text message sms chat", SCROLL),
            T("Utility", "CALL ME", "call me phone", FLASH),
            T("Utility", "CALL BACK", "call back phone missed", SCROLL),
            T("Utility", "TIMER DONE", "timer done time up alarm", FLASH),
            T("Utility", "MUTED", "muted silent mute quiet", FADE),
            T("Utility", "MEMO", "memo reminder alert note", MARQUEE, border = true),
            T("Utility", "PICK UP!", "pick up answer call phone", FLASH),
            // Hinglish
            T("Hinglish", "CHAL BHAI", "chal bhai lets go hinglish", SCROLL),
            T("Hinglish", "KYA SCENE", "kya scene whats up hinglish", WAVE),
            T("Hinglish", "JALDI AAO", "jaldi aao come fast hurry hinglish", FLASH),
            T("Hinglish", "ARRE YAAR", "arre yaar oh man hinglish", SCROLL),
            T("Hinglish", "CHAI PE CHALO?", "chai pe chalo tea break hinglish", SCROLL),
            T("Hinglish", "SAB THEEK", "sab theek badhiya all good fine hinglish", WAVE),
            T("Hinglish", "ACHHA JI", "achha ji okay really hinglish", BOUNCE),
            T("Hinglish", "PAKKA", "pakka sure promise hinglish", ZOOM),
            T("Hinglish", "KAHAN HO?", "kahan ho where are you hinglish", SCROLL),
            T("Hinglish", "MAST", "mast awesome great hinglish", BOUNCE, font = DotFont.BOLD),
            T("Hinglish", "BAS KAR", "bas kar stop enough hinglish", GLITCH),
        ) + listOf(
            // matching words for each glyph category
            text(C.Anime, null, "KAWAII", "kawaii cute anime", WAVE),
            text(C.Anime, null, "SUGOI!", "sugoi amazing anime", ZOOM),
            text(C.Anime, null, "UwU", "uwu cute soft anime", BOUNCE),
            text(C.Utility, null, "NO WIFI", "no wifi offline internet", FLASH),
            text(C.Utility, null, "PLUG IN", "charge me plug in battery low power", FLASH),
            text(C.Utility, null, "KEEP OUT", "locked lock keep out private secure", FADE),
            text(C.Love, null, "MY ♥", "my heart love", BOUNCE),
            text(C.Love, null, "4EVER", "always forever 4ever love", FADE),
            text(C.Heartbreak, null, "IT'S OVER", "its over breakup heartbreak", FADE),
            text(C.Heartbreak, null, "WHY?", "why sad heartbreak", GLITCH),
            text(C.Heartbreak, null, "SAD :(", "sad unhappy heartbreak", FADE),
            text(C.Moods, null, "HAPPY", "happy glad mood", BOUNCE),
            text(C.Moods, null, "MEH", "meh bored mood", FIT, font = DotFont.BOLD),
            text(C.Moods, null, "GRR", "grr angry mad mood", GLITCH, font = DotFont.BOLD),
            text(C.Seasonal, null, "PARTY!", "party celebrate fun", RAIN),
            text(C.Seasonal, null, "HAPPY BDAY", "happy birthday bday celebrate", MARQUEE, border = true),
            text(C.Sports, null, "GOAL!", "goal football score sports", ZOOM, font = DotFont.BOLD),
            text(C.Sports, null, "SIXER!", "sixer six cricket sports", ZOOM),
            text(C.Sports, null, "GAME ON", "game on match sports", SCROLL),
            text(C.Food, null, "FOOD'S HERE", "food here delivery order", SCROLL),
            text(C.Food, null, "ORDER READY", "order ready pickup food", SCROLL),
            text(C.Food, null, "FEED ME", "feed me hungry food eat", WAVE),
            text(C.Travel, null, "CAB'S HERE", "cab here taxi ride travel", FLASH),
            text(C.Travel, null, "WHEELS DOWN", "wheels down landed flight arrived travel", ZOOM),
            text(C.Work, null, "FOCUS", "focus work study", FADE),
            text(C.Work, null, "DUE TODAY", "due today deadline work", FLASH),
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

    /**
     * One glyph + text design. [id] keeps the id the item first shipped with (favourites and recents name it);
     * [words] is what shows: one short word held still, or up to two words that take turns ("MISS/YOU").
     * [tags] keeps the long phrase searchable ("happy birthday" finds HBD).
     */
    private class Pair2(val id: String, val glyph: String, val words: String, val tags: String, val effect: BackgroundEffect)

    private fun G(id: String, glyph: String, words: String, tags: String, effect: BackgroundEffect = BackgroundEffect.STILL) =
        Pair2("gt-" + slug(id), glyph, words, tags, effect)

    /** The longest word a glyph + text design shows: five letters fit the bottom band whole in Small. */
    const val MAX_WORD = 5

    private val pairs = listOf(
        G("I ♥ U", "g-heart", "I♥U", "i love you heart love", BackgroundEffect.BREATHE),
        G("HAPPY BDAY", "g-cake", "HBD", "happy birthday hbd cake bday"),
        G("HAPPY DIWALI", "g-diya", "DIYA", "happy diwali diya festival lights", BackgroundEffect.BREATHE),
        G("GN", "g-bedtime", "GN", "good night gn moon sleep"),
        G("ON MY WAY", "g-car", "OMW", "on my way omw car ride"),
        G("FOOD'S HERE", "g-food-bag", "FOOD", "food here bag delivery order"),
        G("REMINDER", "g-bell", "MEMO", "reminder memo bell alert", BackgroundEffect.BREATHE),
        G("BRB", "g-takeaway-cup", "BRB", "brb coffee break be right back"),
        G("SIXER", "g-cricket", "SIXER", "sixer cricket bat six"),
        G("FOR YOU", "g-gift", "4 YOU", "for you gift present surprise"),
        G("PARTY", "g-balloons", "PARTY", "party balloons celebrate"),
        G("WINNER", "g-trophy", "WON!", "winner won trophy champion"),
        G("GOAL!", "g-football", "GOAL!", "goal football score"),
        G("GOOD MORNING", "g-sun", "GM", "good morning gm sun"),
        G("RAINY DAY", "g-rain", "RAIN!", "rainy day rain weather"),
        G("UMBRELLA", "g-umbrella", "WET!", "wet take umbrella rain reminder weather"),
        G("DELIVERED", "g-parcel", "DONE", "delivered done parcel package courier"),
        G("GROCERIES", "g-grocery-basket", "SHOP", "groceries shop basket shopping order"),
        G("ARRIVING", "g-scooter", "SOON", "arriving soon delivery scooter order"),
        G("LANDED", "g-plane", "SAFE", "landed safe plane flight travel"),
        G("TRIP TIME", "g-suitcase", "TRIP", "trip time suitcase holiday travel"),
        G("I'M HERE", "g-pin", "HERE", "im here location pin arrived"),
        G("WFH", "g-laptop", "WFH", "wfh work from home laptop"),
        G("AT WORK", "g-briefcase", "WORK", "at work office briefcase busy"),
        G("STUDYING", "g-book", "STUDY", "studying study book exam"),
        G("PASSED!", "g-grad-cap", "PASS!", "passed pass exam graduation results"),
        G("WAKE UP", "g-alarm", "WAKE/UP!", "wake up alarm morning", BackgroundEffect.BREATHE),
        G("TAKE MEDS", "g-pill", "MEDS", "take meds medicine pill reminder"),
        G("DRINK WATER", "g-water", "H2O", "drink water glass hydrate reminder"),
        G("WAIT", "g-hourglass", "WAIT", "wait hourglass patience"),
        G("IT'S OVER", "g-heart-broken", "OVER", "its over broken heart breakup heartbreak"),
        G("MISS YOU", "g-teardrop", "MISS/YOU", "miss you tear sad"),
        G("BOO!", "g-ghosted", "BOO!", "boo ghost halloween ghosted"),
        G("KAWAII", "g-anime-sparkle", "CUTE!", "kawaii cute anime sparkle"),
        G("YAY!", "g-anime-joy", "YAY!", "yay anime happy joy"),
        G("GRR", "g-mood-angry", "GRR", "grr angry mad mood"),
        G("CHILL", "g-mood-cool", "CHILL", "chill cool relax mood"),
        G("ZZZ", "g-mood-sleepy", "ZZZ", "zzz sleepy tired sleep"),
        G("HAPPY NEW YEAR", "g-cheers", "HNY", "happy new year hny cheers party"),
        G("MARRY ME?", "g-ring", "WED?", "marry me ring propose engagement"),
        G("MWAH", "g-lips", "MWAH", "mwah kiss lips love"),
        G("CHARGE ME", "g-battery-low", "LOW!", "charge me low battery power"),
        G("NO WIFI", "g-wifi", "OFF!", "no wifi offline internet"),
        G("GYM TIME", "g-dumbbell", "GYM", "gym time workout fitness"),
        G("LET'S RUN", "g-shoe", "RUN!", "lets run running jog fitness"),
        G("SANKRANTI", "g-kite", "KITE", "happy sankranti kite festival uttarayan"),
        G("HOLI", "g-holi-splash", "HOLI", "happy holi colours festival splash"),
        G("EID", "g-eid-crescent", "EID", "eid mubarak moon crescent festival"),
        G("XMAS", "g-xmas-tree", "XMAS", "merry christmas xmas tree festival"),
        G("RAKHI", "g-rakshabandhan-rakhi", "RAKHI", "raksha bandhan rakhi sister brother festival"),
    )

    /** First and last rows of the picture and of the words in a glyph + text design (the bottom band). */
    val PICTURE_ROWS = 1..13
    val WORD_ROWS = TextBand.BOTTOM.rows

    /** The font for [words]: Regular when every word fits the band in it, else Small. */
    private fun wordFont(words: List<String>): DotFont =
        if (words.all { DotFonts.width(DotFont.REGULAR, it) <= BAND_WIDTH }) DotFont.REGULAR else DotFont.SMALL

    /**
     * The one layout of a glyph + text design, used for its playback and its poster alike: the glyph shrunk into
     * rows 1-13, the words in the bottom band (rows 14-20, the letters on 15-19). One word holds still and
     * centred; two words take turns, each still and centred. Nothing scrolls, nothing blinks fully off, and the
     * words sit below the picture, so nothing is cut out of it.
     */
    fun layersFor(p: DeviceProfile, glyph: String, words: String, effect: BackgroundEffect): Layers {
        val picture = GlyphArt.shrink(GlyphArt.still(glyph, p), p, top = PICTURE_ROWS.first, bottom = PICTURE_ROWS.last)
        val name = GlyphArt.entry(glyph)?.name ?: glyph
        val parts = words.split('/')
        val font = wordFont(parts)
        val spec = if (parts.size == 1) {
            TextSpec(text = words, font = font, style = FIT, band = TextBand.BOTTOM)
        } else {
            TextSpec(text = parts.joinToString(" "), font = font, style = WORDS, band = TextBand.BOTTOM, pauseMs = 900)
        }
        return Layers(
            background = BackgroundLayer(Animation.single(picture, 1000).toMuseumJson(), name, if (effect == BackgroundEffect.BLINK) BackgroundEffect.BREATHE else effect),
            text = TextLayer(spec),
            behind = BehindText.CLEAR,
        )
    }

    /**
     * The still a tile shows, from the same layout as the playback: the picture at full brightness and the first
     * word once it has come to rest.
     */
    fun posterFor(p: DeviceProfile, layers: Layers): Frame {
        val still = layers.copy(background = layers.background?.copy(effect = BackgroundEffect.STILL))
        val rest = if (layers.text?.spec?.style == WORDS) TextRender.WORD_ARRIVE_MS else 0
        return Frame.of(p, Compositor.frameAt(p, still, Animation.single(Frame.blank(p), 1000), rest))
    }

    /** The words each glyph + text design shows, as typed ("MISS/YOU"), by item id: for checks. */
    internal val wordsById: Map<String, String> get() = pairs.associate { it.id to it.words }

    val layered: List<LibraryItem> by lazy {
        pairs.map { g ->
            val shown = g.words.replace('/', ' ')
            val words = (g.tags + " " + shown).lowercase().split(' ').filter { it.isNotBlank() }.distinct()
            val make = { p: DeviceProfile -> layersFor(p, g.glyph, g.words, g.effect) }
            LibraryItem(g.id, shown, C.GlyphText, words, null, ItemKind.ORIGINAL, ItemForm.LAYERED, null, null, make, posterOf = { p -> posterFor(p, make(p)) }) { p ->
                Compositor.render(p, make(p), Animation.single(Frame.blank(p), 1000)).let { a ->
                    if (a.frames.isEmpty()) Animation(p, listOf(AnimFrame(Frame.blank(p), 1000))) else a
                }
            }
        }
    }
}
