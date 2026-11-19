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
    val name: String,
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
    private val maker: (DeviceProfile) -> Animation,
) {
    /** True when the item moves (everything except still pictures). */
    val animated: Boolean get() = form != ItemForm.STILL

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

    internal fun thumbnailFor(profile: DeviceProfile): Frame =
        thumbs.getOrPut(key(profile)) {
            val frames = load(profile).frames.map { it.frame }
            frames.maxByOrNull { f -> f.pixels.sum() } ?: frames.first()
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
