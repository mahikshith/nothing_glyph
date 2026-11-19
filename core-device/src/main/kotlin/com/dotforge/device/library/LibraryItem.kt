package com.dotforge.device.library

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import com.dotforge.device.Layers
import com.dotforge.device.TextSpec
import java.util.concurrent.ConcurrentHashMap

/** A group of library items, for example "Alerts" or "Nature & weather". */
data class Category(val id: String, val title: String)

/** Where an item comes from: converted sprites, ported generators, DotForge originals and DotForge glyph art. */
enum class ItemKind { SPRITE, GENERATOR, ORIGINAL, GLYPH }

/** What an item is: a still picture, a moving one, a ready text design or a glyph with text on top. */
enum class ItemForm(val label: String) { STILL("Still"), ANIMATION("Animated"), TEXT("Text"), LAYERED("Glyph + text") }

/**
 * One ready-made animation. Nothing is built until [load] is called: generators run on demand and sprite
 * data is parsed on first use, then the result is cached per device profile.
 */
class LibraryItem(
    val id: String,
    name: String,
    val category: Category,
    val tags: List<String>,
    /** Attribution text to show with the item, or null when the item needs none. */
    val credit: String? = null,
    val kind: ItemKind = ItemKind.ORIGINAL,
    val form: ItemForm = ItemForm.ANIMATION,
    /** A sub-group inside the category (the Text category's Greetings, Love, Status...), or null. */
    val group: String? = null,
    /** The ready text design of a [ItemForm.TEXT] item; it opens in the Text screen. */
    val text: TextSpec? = null,
    private val layersOf: ((DeviceProfile) -> Layers)? = null,
    /** The still a tile shows, when the item has a better one than its brightest frame (text, glyph + text). */
    private val posterOf: ((DeviceProfile) -> Frame)? = null,
    private val maker: (DeviceProfile) -> Animation,
) {
    /** The shown name: sentence case ("Heart balloon"); text designs keep their words as they appear. */
    val name: String = if (form == ItemForm.TEXT || form == ItemForm.LAYERED) name else Names.sentence(name)

    /** The tags people see: [tags] without brand names, which stay searchable only. */
    val displayTags: List<String> get() = tags.filter { it !in Names.brands }

    /** True when the item moves: it has more than one frame. */
    val animated: Boolean get() = load().frames.size > 1

    /** The layers of a [ItemForm.LAYERED] item (background + text); it opens in the editor with them. */
    fun layers(profile: DeviceProfile = DeviceProfile.Phone3): Layers? = layersOf?.invoke(profile)

    private val cache = ConcurrentHashMap<String, Animation>()
    private val thumbs = ConcurrentHashMap<String, Frame>()

    private fun key(p: DeviceProfile) = "${p.size}:${p.radiusSquared}"

    /** The full animation for [profile]: every pixel lies inside the mask, every frame lasts 20 ms or more. */
    fun load(profile: DeviceProfile = DeviceProfile.Phone3): Animation =
        cache.getOrPut(key(profile)) { normalise(maker(profile)) }

    /** Builds the animation again without the cache; used to check that generators are deterministic. */
    internal fun loadUncached(profile: DeviceProfile): Animation = normalise(maker(profile))

    /**
     * The fixed still every tile and the item sheet start on, showing the whole design: a still's picture, an
     * animation's fullest frame, a text's words held whole (or their first screenful), a glyph + text design's
     * picture with its words. Never blank while the item lights anything.
     */
    internal fun thumbnailFor(profile: DeviceProfile): Frame =
        thumbs.getOrPut(key(profile)) {
            val frames = load(profile).frames.map { it.frame }
            val fullest = frames.maxByOrNull { f -> f.pixels.sum() } ?: frames.first()
            val own = posterOf?.invoke(profile)?.let { Frame.of(profile, it.pixels) }
            if (own != null && own.pixels.any { it > 0 }) own else fullest
        }

    private fun normalise(a: Animation): Animation {
        var frames = a.frames.map { AnimFrame(Frame.of(a.profile, it.frame.pixels), it.durationMs.coerceIn(Animation.MIN_FRAME_MS, Animation.MAX_FRAME_MS)) }
        // Generated loops that are very short are repeated so every animation has at least 24 frames.
        if (form == ItemForm.ANIMATION && (kind == ItemKind.GENERATOR || kind == ItemKind.ORIGINAL) && frames.size < MIN_GENERATED_FRAMES) {
            val times = (MIN_GENERATED_FRAMES + frames.size - 1) / frames.size
            frames = List(times) { frames }.flatten()
        }
        return Animation(a.profile, frames)
    }

    override fun toString() = "LibraryItem($id)"

    companion object {
        const val MIN_GENERATED_FRAMES = 24
        const val MAX_GENERATED_FRAMES = 120
    }
}

/** Display names and the tags that stay hidden. */
internal object Names {
    /**
     * "Heart Balloon" to "Heart balloon": the first word keeps its capital, later Capitalised words go lower
     * case, and words in capitals or mixed case (UFO, UwU, DJ) stay as they are.
     */
    fun sentence(name: String): String {
        // names of days keep their capitals: New Year
        if (name.contains("New Year")) return sentence(name.replace("New Year", "\u0000")).replace("\u0000", "New Year")
        val words = name.trim().split(' ')
        return words.mapIndexed { i, w ->
            when {
                w.isEmpty() -> w
                i == 0 -> w.replaceFirstChar { it.uppercaseChar() }
                w.length > 1 && w[0].isUpperCase() && w.drop(1).all { !it.isLetter() || it.isLowerCase() } -> w.lowercase()
                else -> w
            }
        }.joinToString(" ")
    }

    /** Brand names: searchable, never shown. */
    val brands = setOf(
        "swiggy", "zomato", "zepto", "blinkit", "instamart", "bigbasket", "dunzo", "amazon", "flipkart", "myntra",
        "uber", "ola", "rapido", "irctc", "ipl", "whatsapp", "instagram", "telegram", "youtube", "spotify", "netflix",
        "gpay", "phonepe", "paytm", "google", "apple", "nothing", "dominos", "starbucks", "mcdonalds", "kfc",
    )
}
