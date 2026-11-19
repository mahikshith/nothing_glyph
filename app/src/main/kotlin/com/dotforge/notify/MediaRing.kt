package com.dotforge.notify

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dotforge.AppGraph
import com.dotforge.device.Animation
import com.dotforge.device.Effects
import com.dotforge.engine.DisplayRequest
import com.dotforge.engine.Priority
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The media ring's switches (Integrations page). Off until the user switches it on. */
class MediaRingSettings(private val prefs: SharedPreferences) {
    private val _on = MutableStateFlow(prefs.getBoolean(KEY_ON, false))
    val on: StateFlow<Boolean> = _on.asStateFlow()
    private val _onlyAway = MutableStateFlow(prefs.getBoolean(KEY_ONLY_AWAY, false))
    /** "Only when the phone is locked" (the name is from when it also meant face down; face down hides app lights anyway). */
    val onlyLockedOrFaceDown: StateFlow<Boolean> = _onlyAway.asStateFlow()

    fun setOn(v: Boolean) {
        prefs.edit().putBoolean(KEY_ON, v).apply()
        _on.value = v
    }

    fun setOnlyLockedOrFaceDown(v: Boolean) {
        prefs.edit().putBoolean(KEY_ONLY_AWAY, v).apply()
        _onlyAway.value = v
    }

    private companion object {
        const val KEY_ON = "media_ring"
        const val KEY_ONLY_AWAY = "media_ring_only_away"
    }
}

/**
 * While media plays, its position fills the outer ring. Sessions come from [MediaSessionManager] through our
 * notification listener ([listener]), so no other permission is needed. Runs while the listener is connected.
 */
class MediaRing(private val context: Context, private val listener: ComponentName) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var controller: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() = pick(current())
    }
    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }
    private val tick = Runnable { update() }

    fun start() {
        runCatching { sessions?.addOnActiveSessionsChangedListener(changed, listener, main) }
        pick(current())
        scope.launch {
            AppGraph.media.on.combine(AppGraph.media.onlyLockedOrFaceDown) { a, b -> a to b }.collect { update() }
        }
    }

    fun stop() {
        scope.cancel()
        runCatching { sessions?.removeOnActiveSessionsChangedListener(changed) }
        controller?.unregisterCallback(callback)
        controller = null
        main.removeCallbacks(tick)
        AppGraph.arbiter.remove(ID)
    }

    /** Needs notification access; empty without it. */
    private fun current(): List<MediaController> = runCatching { sessions?.getActiveSessions(listener) }.getOrNull().orEmpty()

    /** Follows the playing session, else the most recent one. */
    private fun pick(list: List<MediaController>) {
        val next = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
        }
        update()
    }

    private fun update() {
        main.removeCallbacks(tick)
        val c = controller
        val state = c?.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        if (!AppGraph.media.on.value || !playing) {
            AppGraph.arbiter.remove(ID)
            return
        }
        // while playing, look again every second: the position moves, and the phone may be locked
        main.postDelayed(tick, TICK_MS)
        val fraction = Progress.media(
            position = state!!.position,
            duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            updatedAt = state.lastPositionUpdateTime,
            now = SystemClock.elapsedRealtime(),
            speed = state.playbackSpeed,
            playing = true,
        )
        // face down, Nothing hides app lights anyway: "only when locked" is what this option can promise
        val away = keyguard?.isKeyguardLocked == true
        if (fraction == null || (AppGraph.media.onlyLockedOrFaceDown.value && !away)) {
            AppGraph.arbiter.remove(ID)
            return
        }
        val profile = AppGraph.profile
        AppGraph.arbiter.post(
            DisplayRequest(ID, Priority.PROGRESS, Animation.single(Effects.ring(profile, fraction), TICK_MS.toInt()), loop = true, label = "Media: ${label(c.packageName)}"),
        )
    }

    private fun label(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        const val ID = "media"
        private const val TICK_MS = 1_000L
    }
}
