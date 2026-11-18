package com.dotforge.diagnostics

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dotforge.composition.ComposerInfo
import com.dotforge.composition.GlyphAuthor
import com.dotforge.composition.RingtoneParser
import com.dotforge.device.DotText
import com.dotforge.device.Effects
import com.dotforge.device.Frame
import com.dotforge.driver.DriverState
import com.dotforge.driver.GlyphDriver
import com.dotforge.system.NothingSettings
import com.dotforge.system.SettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RingtoneUi(
    val name: String? = null,
    val info: ComposerInfo? = null,
    val sizeBytes: Int = 0,
    val message: String? = null,
    val playing: Boolean = false,
)

/** The Diagnostics bench: drives [GlyphDriver] directly (the arbiter is suspended while the screen is open). */
class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val profile = GlyphDriver.profile

    private var anim: Job? = null

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _stress = MutableStateFlow<String?>(null)
    val stress: StateFlow<String?> = _stress.asStateFlow()

    private val _settings = MutableStateFlow<List<SettingRow>>(emptyList())
    val settings: StateFlow<List<SettingRow>> = _settings.asStateFlow()

    private val _ringtone = MutableStateFlow(RingtoneUi())
    val ringtone: StateFlow<RingtoneUi> = _ringtone.asStateFlow()

    init { refreshSettings() }

    private fun stopAnim() { anim?.cancel(); anim = null }

    private fun run(label: String, block: suspend CoroutineScope.() -> Unit) {
        stopAnim()
        _status.value = label
        anim = viewModelScope.launch(Dispatchers.Default) {
            try { block() } finally { if (_status.value == label) _status.value = "idle" }
        }
    }

    fun stop() { stopAnim(); _status.value = "idle"; _ringtone.value = _ringtone.value.copy(playing = false) }

    fun allOn() { stopAnim(); _status.value = "all on"; GlyphDriver.submit(Frame.blank(profile).fill(255).pixels) }

    fun checkerboard() { stopAnim(); _status.value = "checkerboard"; GlyphDriver.submit(Effects.checkerboard(profile).pixels) }

    fun clear() { stopAnim(); _status.value = "clear"; GlyphDriver.clear() }

    fun closeApp() { stopAnim(); _status.value = "closeAppMatrix"; GlyphDriver.closeApp() }

    fun ringSweep() = run("ring sweep") {
        var i = 0
        while (isActive) {
            GlyphDriver.submit(Effects.comet(profile, i++, tail = 10).pixels)
            delay(25)
        }
    }

    fun scrollText(text: String) = run("scrolling text") {
        val t = text.ifBlank { "DOTFORGE" }.uppercase()
        val len = DotText.scrollLength(profile.size, t)
        while (isActive) {
            for (o in 0..len) {
                GlyphDriver.submit(DotText.scrollFrame(profile.size, t, o))
                delay(70)
                if (!isActive) break
            }
        }
    }

    fun setBrightness(v: Int) = GlyphDriver.setBrightness(v)

    /** Submit as fast as possible for 5 s; report frames the SDK accepted per second. */
    fun stressTest() {
        stopAnim()
        _status.value = "fps stress test (5 s)"
        _stress.value = "running..."
        anim = viewModelScope.launch(Dispatchers.Default) {
            val before = GlyphDriver.framesSentTotal
            val failedBefore = GlyphDriver.stats.value.framesFailed
            val t0 = System.nanoTime()
            var submitted = 0
            while (isActive && System.nanoTime() - t0 < 5_000_000_000L) {
                GlyphDriver.submit(Effects.checkerboard(profile, submitted and 1).pixels)
                submitted++
                delay(1)
            }
            val secs = (System.nanoTime() - t0) / 1e9
            val sent = GlyphDriver.framesSentTotal - before
            val failed = GlyphDriver.stats.value.framesFailed - failedBefore
            _stress.value = if (GlyphDriver.state.value != DriverState.Ready && sent == 0L) {
                "no frames sent: driver ${GlyphDriver.state.value}"
            } else {
                "%.1f fps achieved (%d accepted, %d failed, %d submitted in %.1f s)".format(sent / secs, sent, failed, submitted, secs)
            }
            _status.value = "idle"
        }
    }

    fun refreshSettings() {
        _settings.value = NothingSettings.readAll(getApplication<Application>().contentResolver)
    }

    fun loadRingtone(uri: Uri) {
        stopAnim()
        _ringtone.value = RingtoneUi(message = "reading...")
        viewModelScope.launch(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            try {
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot open file")
                val name = uri.lastPathSegment ?: uri.toString()
                _ringtone.value = RingtoneUi(name = name, info = RingtoneParser.parse(bytes), sizeBytes = bytes.size)
            } catch (t: Throwable) {
                _ringtone.value = RingtoneUi(message = "failed: ${t.message}")
            }
        }
    }

    /** Play the AUTHOR rows at 60 fps on the matrix when each row is a full 625-cell frame. */
    fun playRingtone() {
        val rows = _ringtone.value.info?.rows
        if (rows.isNullOrEmpty()) {
            _ringtone.value = _ringtone.value.copy(message = "no AUTHOR light rows to play")
            return
        }
        val cols = rows.first().size
        if (cols != profile.cellCount) {
            _ringtone.value = _ringtone.value.copy(message = "rows have $cols columns, not ${profile.cellCount}: cannot map to the matrix yet")
            return
        }
        _ringtone.value = _ringtone.value.copy(message = null, playing = true)
        run("ringtone lights") {
            val frameNs = 1_000_000_000L / GlyphAuthor.FPS
            val start = System.nanoTime()
            for ((i, r) in rows.withIndex()) {
                if (!isActive) break
                GlyphDriver.submit(IntArray(profile.cellCount) { r.getOrElse(it) { 0 } })
                val wait = start + (i + 1) * frameNs - System.nanoTime()
                if (wait > 0) delay(wait / 1_000_000)
            }
            GlyphDriver.clear()
            withContext(Dispatchers.Main) { _ringtone.value = _ringtone.value.copy(playing = false) }
        }
    }

    override fun onCleared() { stopAnim() }
}
