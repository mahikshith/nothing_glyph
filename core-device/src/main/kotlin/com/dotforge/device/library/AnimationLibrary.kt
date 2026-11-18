package com.dotforge.device.library

import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame

/** The categories of the library, in the order they are listed. */
object Categories {
    /** A curated first page: bold shapes and smooth motion that read well on 25x25. Its items also live in a real category. */
    val Featured = Category("featured", "Featured")
    val Alerts = Category("alerts", "Alerts")
    val LoveEmoji = Category("love_emoji", "Love & emoji")
    val NatureWeather = Category("nature_weather", "Nature & weather")
    val Loaders = Category("loaders", "Loaders")
    val Music = Category("music", "Music")
    val Games = Category("games", "Games")
    val Time = Category("time", "Time")
    val Abstract = Category("abstract", "Abstract")
    val Text = Category("text", "Text")
    val Seasonal = Category("seasonal", "Seasonal")
    val AnimalsFood = Category("animals_food", "Animals & food")
    val Symbols = Category("symbols", "Symbols")
    val StoriesArt = Category("stories_art", "Stories & art")

    val all = listOf(Featured, Alerts, LoveEmoji, NatureWeather, Loaders, Music, Games, Time, Abstract, Text, Seasonal, AnimalsFood, Symbols, StoriesArt)
    fun byId(id: String): Category = all.first { it.id == id }
}

/**
 * DotForge's ready-made animations: converted sprite packs (MIT / CC0 / public-domain redrawings, see NOTICE),
 * ported effect generators and DotForge originals. Everything is loaded lazily.
 *
 * Not to be confused with `com.dotforge.device.AnimationLibrary`, the small older list of twelve presets, which
 * this library re-exports as generator items.
 */
object AnimationLibrary {
    val categories: List<Category> get() = Categories.all

    /** Every item, sprites first. Building the list only reads the small sprite index. */
    val all: List<LibraryItem> by lazy {
        val sprites = SpriteStore.entries.map { e ->
            LibraryItem(e.id, e.name, Categories.byId(e.category), e.tags, e.credit, ItemKind.SPRITE) { p -> SpriteStore.load(e, p) }
        }
        sprites + Generators.items + Originals.items
    }

    private val byCategory by lazy { all.groupBy { it.category } }
    private val byId by lazy { all.associateBy { it.id } }

    /** Ids of the Featured items, in display order. */
    private val featuredIds = listOf(
        "sprite-mini-heart", "gen-heartbeat", "orig-heart-fill", "orig-wink", "orig-thumbs-up", "orig-bell", "orig-call-rings",
        "orig-ripple-ping", "orig-charging", "orig-rim-breathing", "gen-ripple", "gen-radar", "gen-loading", "gen-spinner",
        "orig-pulse-dots", "orig-radial-fill", "orig-hourglass", "gen-fireworks", "gen-spiral", "gen-swirl", "gen-rings",
        "gen-metaballs", "gen-galaxy", "orig-breathing-circle", "gen-bounce", "orig-dice", "orig-sun-pulse", "orig-moon-phases",
        "gen-snow", "gen-lightning", "gen-aurora", "gen-ocean-waves", "gen-fire", "gen-candle", "orig-beat-pulse", "orig-vinyl",
        "gen-equalizer", "orig-clock-analog", "orig-tree", "orig-pumpkin",
    )

    private val featured by lazy { featuredIds.map { requireNotNull(byId[it]) { "unknown featured id $it" } } }

    /** Items of [category]; [Categories.Featured] lists the curated picks, which keep their own real category too. */
    fun items(category: Category): List<LibraryItem> = if (category == Categories.Featured) featured else byCategory[category].orEmpty()

    fun item(id: String): LibraryItem? = byId[id]

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
            val other = (item.id + " " + item.category.title).lowercase()
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
