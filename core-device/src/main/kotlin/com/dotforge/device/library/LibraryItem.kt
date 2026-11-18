package com.dotforge.device.library

import com.dotforge.device.AnimFrame
import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import com.dotforge.device.Frame
import java.util.concurrent.ConcurrentHashMap

/** A group of library items, for example "Alerts" or "Nature & weather". */
data class Category(val id: String, val title: String)

/** Where an item comes from. */
enum class ItemKind { SPRITE, GENERATOR, ORIGINAL }

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
    private val maker: (DeviceProfile) -> Animation,
) {
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
        if (kind != ItemKind.SPRITE && frames.size < MIN_GENERATED_FRAMES) {
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
