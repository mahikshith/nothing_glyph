package com.dotforge.studio

import com.dotforge.device.Animation
import com.dotforge.device.DeviceProfile
import java.io.File

/**
 * Stores the user's Studio animation as Dot Hub / Glyph Museum JSON.
 * The DF Player toy and the "My Studio animation" rule effect use it.
 */
class StudioRepository(private val profile: DeviceProfile, private val file: File) {

    /** The saved animation, or null when there is none or it cannot be read. */
    fun load(): Animation? =
        if (!file.exists()) null else runCatching { Animation.fromMuseumJson(profile, file.readText()) }.getOrNull()

    fun save(animation: Animation) = file.writeText(animation.toMuseumJson())
}
