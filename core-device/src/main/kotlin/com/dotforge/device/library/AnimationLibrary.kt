package com.dotforge.device.library

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame

/** The categories of the library, in the order they are listed. */
object Categories {
    /** A curated first page: bold shapes and smooth motion that read well on 25x25. Its items also live in a real category. */
    val Featured = Category("featured", "Featured")
    val Alerts = Category("alerts", "Alerts")
    val Love = Category("love", "Love")
    val Heartbreak = Category("heartbreak", "Heartbreak")
    val Moods = Category("moods", "Moods")
    val Anime = Category("anime", "Anime style")
    val Seasonal = Category("seasonal", "Celebrations")
    val NatureWeather = Category("nature_weather", "Weather & nature")
    val Time = Category("time", "Reminders & time")
    val Utility = Category("utility", "Utility")
    val Food = Category("food", "Food & delivery")
    val Travel = Category("travel", "Travel & rides")
    val Work = Category("work", "Work & study")
    val Sports = Category("sports", "Sports")
    val Text = Category("text", "Text")
    val GlyphText = Category("glyph_text", "Glyph + text")
    val Loaders = Category("loaders", "Loaders")
    val Music = Category("music", "Music")
    val Games = Category("games", "Games")
    val Abstract = Category("abstract", "Abstract")
    val AnimalsFood = Category("animals_food", "Animals")
    val Symbols = Category("symbols", "Symbols")
    val StoriesArt = Category("stories_art", "Stories & art")

    val all = listOf(
        Featured, Alerts, Love, Heartbreak, Moods, Anime, Seasonal, NatureWeather, Time, Utility, Food, Travel, Work, Sports,
        Text, GlyphText, Loaders, Music, Games, Abstract, AnimalsFood, Symbols, StoriesArt,
    )

    /** Old ids of merged categories: "love_emoji" was split into Love, Heartbreak and Moods. */
    fun byId(id: String): Category = all.firstOrNull { it.id == id } ?: if (id == "love_emoji") Moods else error("unknown category $id")
}

/**
 * DotForge's library: DotForge glyph art (stills and animations), ready text designs, glyph + text designs,
 * converted sprite packs (MIT / CC0 / public-domain redrawings, see NOTICE), ported effect generators and DotForge
 * originals. Everything is loaded lazily.
 */
object AnimationLibrary {
    val categories: List<Category> get() = Categories.all

    /** Every item: DotForge glyph art first, then text, glyph + text, sprites, generators, originals and the alert icons. */
    val all: List<LibraryItem> by lazy {
        val art = GlyphArt.entries.map { e ->
            LibraryItem(e.id, e.name, Categories.byId(artCategory(e.category)), e.tags, null, ItemKind.GLYPH,
                if (e.still) ItemForm.STILL else ItemForm.ANIMATION) { p -> GlyphArt.load(e, p) }
        }
        val sprites = SpriteStore.entries.map { e ->
            LibraryItem(e.id, e.name, spriteCategory(e), e.tags, e.credit, ItemKind.SPRITE,
                if (e.sourceFrames == 1) ItemForm.STILL else ItemForm.ANIMATION) { p -> SpriteStore.load(e, p) }
        }
        val drawn = (art + TextItems.texts + TextItems.layered).map { it.name.lowercase() }.toSet()
        val icons = com.dotforge.device.GlyphLibrary.all.filter { it.name.lowercase() !in drawn }.map { g ->
            LibraryItem(g.id, g.name, if (g.id in ALERT_ICONS) Categories.Alerts else Categories.Symbols, listOf("icon", "alert", g.name.lowercase()),
                null, ItemKind.ORIGINAL, if (g.animated) ItemForm.ANIMATION else ItemForm.STILL) { p -> g.animation(p) }
        }
        dedupe(art + TextItems.texts + TextItems.layered + sprites + Generators.items + Originals.items + icons)
    }

    /**
     * Retired pictures (the first anime set, redrawn designs that didn't read) and the closest current item, so an
     * alert or a saved design that names one still finds a picture.
     */
    private val RETIRED = mapOf(
        "g-anime-sweat" to "g-anime-sweat-drop", "g-anime-heart" to "g-anime-sparkle", "g-anime-star" to "g-anime-sparkle",
        "g-anime-cat" to "g-anime-chibi-cat", "g-anime-sleep" to "g-anime-shy", "g-anime-smug" to "g-anime-joy",
        "g-anime-uwu" to "g-anime-joy", "g-anime-dizzy" to "g-anime-shock", "g-anime-dash" to "g-anime-speed-dash",
        "g-anime-teary" to "g-anime-cry", "a-anime-blink" to "a-anime-sparkle", "a-anime-cry" to "g-anime-cry",
        "a-anime-sweat" to "g-anime-sweat-drop", "a-anime-heart" to "a-anime-sparkle",
        "g-heart-rain" to "a-hearts-float", "a-heart-rain" to "a-hearts-float", "g-confetti" to "g-party-popper",
        "g-road" to "a-car",
    )

    /** Ids of items merged into an earlier one with the same name: old id to the one kept. */
    private val aliases = HashMap<String, String>(RETIRED)

    /** One item per shown name (pictures only; text designs are their words): the first, by source order, stays. */
    private fun dedupe(items: List<LibraryItem>): List<LibraryItem> {
        val kept = HashMap<String, LibraryItem>()
        return items.filter { item ->
            if (item.form == ItemForm.TEXT || item.form == ItemForm.LAYERED) return@filter true
            val key = item.name.lowercase()
            val first = kept[key]
            if (first == null) { kept[key] = item; true } else { aliases[item.id] = first.id; false }
        }
    }

    private val ALERT_ICONS = setOf("chat", "mail", "bell", "phone", "spam", "alarm", "warning", "chat-dots", "at", "info", "question", "pulse", "ripple", "spinner", "blink", "wave")

    private fun artCategory(id: String) = when (id) { "celebrations" -> "seasonal"; "weather" -> "nature_weather"; else -> id }

    private val FOOD_SPRITES = setOf(
        "pizza", "coffee", "tea", "ice-cream", "birthday-cake", "cupcake", "donut", "burger", "fries", "watermelon", "apple", "cherries",
        "popcorn", "soda", "boba", "taco", "sushi", "lollipop", "cookie", "fried-egg", "ramen",
    )

    /** Sprite packs were sorted before Love, Heartbreak, Moods and Food & delivery existed. */
    private fun spriteCategory(e: SpriteStore.Entry): Category = when {
        e.category == "animals_food" && e.id.removePrefix("sprite-") in FOOD_SPRITES -> Categories.Food
        e.category != "love_emoji" -> Categories.byId(e.category)
        e.id == "sprite-heart-broken" -> Categories.Heartbreak
        e.id.contains("heart") || e.id.contains("love") || e.id.contains("ring") || e.id.contains("rose") || e.id == "sprite-kiss" -> Categories.Love
        else -> Categories.Moods
    }

    private val byCategory by lazy { all.groupBy { it.category } }
    private val byId by lazy { all.associateBy { it.id } }

    private fun resolve(id: String): LibraryItem? = byId[id] ?: all.let { aliases[id] }?.let { byId[it] }

    /** Ids of the Featured items, in display order. */
    private val featuredIds = listOf(
        "a-heart-sparkle", "gt-i-h-u", "a-mood-laugh", "a-cupid", "gt-happy-bday", "a-food-bag", "text-hello", "a-scooter",
        "sprite-mini-heart", "gen-heartbeat", "orig-heart-fill", "orig-wink", "orig-thumbs-up", "orig-bell", "orig-call-rings",
        "orig-ripple-ping", "orig-charging", "orig-rim-breathing", "gen-ripple", "gen-radar", "gen-loading", "gen-spinner",
        "orig-pulse-dots", "orig-radial-fill", "orig-hourglass", "gen-fireworks", "gen-spiral", "gen-swirl", "gen-rings",
        "gen-metaballs", "gen-galaxy", "orig-breathing-circle", "gen-bounce", "orig-dice", "orig-sun-pulse", "orig-moon-phases",
        "gen-snow", "gen-lightning", "gen-aurora", "gen-ocean-waves", "gen-fire", "gen-candle", "orig-beat-pulse", "orig-vinyl",
        "gen-equalizer", "orig-clock-analog", "a-xmas-tree", "orig-pumpkin",
    )

    private val featured by lazy { featuredIds.map { requireNotNull(resolve(it)) { "unknown featured id $it" } }.distinct() }

    /** Items of [category]; [Categories.Featured] lists the curated picks, which keep their own real category too. */
    fun items(category: Category): List<LibraryItem> = if (category == Categories.Featured) featured else byCategory[category].orEmpty()

    /** The item with [id], or the one it was merged into. */
    fun item(id: String): LibraryItem? = resolve(id)

    /** The Text category's groups (Greetings, Love, Status...), in display order. */
    val textGroups: List<String> get() = TextItems.groups

    /**
     * Items whose name, tags, category or id contain every word of [query] (case-insensitive), best matches first:
     * name starts with the word, then name contains it, then tags, then anything else.
     */
    fun search(query: String): List<LibraryItem> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.mapNotNull { item ->
            val name = item.name.lowercase()
            val tags = item.tags.map { it.lowercase() }
            val other = (item.id + " " + item.category.title + " " + (item.group ?: "") + " " + item.form.label).lowercase()
            var score = 0
            for (w in words) {
                score += when {
                    name.split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(w) } -> 4
                    name.contains(w) -> 3
                    tags.any { it == w } -> 3
                    tags.any { it.contains(w) } -> 2
                    other.contains(w) -> 1
                    else -> return@mapNotNull null
                }
            }
            item to score
        }.sortedWith(compareByDescending<Pair<LibraryItem, Int>> { it.second }.thenBy { it.first.name }).map { it.first }
    }

    /** One representative frame of [item] (its brightest), for grids and lists. */
    fun thumbnail(item: LibraryItem, profile: DeviceProfile = DeviceProfile.Phone3): Frame = item.thumbnailFor(profile)
}
