package com.dotforge.visualiser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.SystemClock
import com.dotforge.composition.LiveLevels
import com.dotforge.composition.SpectrumBands
import com.dotforge.composition.VisualStyle
import com.dotforge.composition.VisualiserRenderer
import com.dotforge.device.DeviceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Delivers magnitude spectra (n/2 bins) and their sample rate from some capture. */
private interface SpectrumSource {
    /** Starts capturing; false when this source can't run here. */
    fun start(onSpectrum: (FloatArray, Int) -> Unit): Boolean
    fun stop()
}

/** What is playing on the phone: [Visualizer] on the output mix (session 0). Needs RECORD_AUDIO. */
private class OutputMixSource : SpectrumSource {
    private var visualizer: Visualizer? = null

    override fun start(onSpectrum: (FloatArray, Int) -> Unit): Boolean = runCatching {
        val v = Visualizer(0)
        v.enabled = false
        v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
        val rate = (Visualizer.getMaxCaptureRate()).coerceAtMost(30_000) // mHz: up to 30 per second
        v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit
            override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                if (fft != null) onSpectrum(SpectrumBands.fromVisualizerFft(fft), samplingRate / 1000)
            }
        }, rate, false, true)
        v.enabled = true
        visualizer = v
        true
    }.getOrDefault(false)

    override fun stop() {
        visualizer?.let { runCatching { it.enabled = false }; it.release() }
        visualizer = null
    }
}

/** The microphone, analysed with our own FFT. Needs RECORD_AUDIO. */
private class MicSource : SpectrumSource {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // checked by the engine before starting
    override fun start(onSpectrum: (FloatArray, Int) -> Unit): Boolean {
        val rate = 44_100
        val n = 1024
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, n * 4))
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        running = true
        thread = Thread({
            val buf = ShortArray(n)
            runCatching { rec.startRecording() }
            while (running) {
                var got = 0
                while (got < n && running) {
                    val r = rec.read(buf, got, n - got)
                    if (r <= 0) break
                    got += r
                }
                if (got == n) onSpectrum(SpectrumBands.fromPcm(buf), rate)
            }
            runCatching { rec.stop() }
            rec.release()
        }, "DotForgeMic").apply { isDaemon = true; start() }
        return true
    }

    override fun stop() {
        running = false
        thread = null
    }
}

/**
 * Listens to what is playing and draws it at about 30 fps. Tries [Visualizer] on the output mix first; if that
 * can't start, or gives only silence for [FALLBACK_MS] while music is playing, it switches to the microphone
 * ([usingMic]). Frames (0-255 per cell) go to [onFrame] from a background thread.
 */
class VisualiserEngine(
    private val context: Context,
    private val profile: DeviceProfile,
    private val style: () -> VisualStyle,
    private val onFrame: (IntArray) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var source: SpectrumSource? = null
    private var loop: Job? = null
    private val levels = LiveLevels()
    @Volatile private var latest: FloatArray? = null
    @Volatile private var latestRate = 44_100
    @Volatile private var lastSound = 0L

    private val _usingMic = MutableStateFlow(false)
    val usingMic: StateFlow<Boolean> = _usingMic.asStateFlow()

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** True when music (or any media) is playing on the phone. */
    fun musicActive(): Boolean = context.getSystemService(AudioManager::class.java)?.isMusicActive == true

    /** Starts listening; false without RECORD_AUDIO or when no capture works. */
    fun start(): Boolean {
        if (!hasPermission()) return false
        if (loop != null) return true
        val started = useSource(OutputMixSource(), mic = false) || useSource(MicSource(), mic = true)
        if (!started) return false
        val startedAt = SystemClock.uptimeMillis()
        loop = scope.launch {
            var last = SystemClock.uptimeMillis()
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                latest?.let { mags ->
                    levels.update(SpectrumBands.bandDb(mags, latestRate), ((now - last) / 1000f).coerceIn(0.01f, 0.2f))
                }
                last = now
                // Visualizer that stays silent while music plays: some phones block it; use the microphone instead
                if (!_usingMic.value && musicActive() && now - startedAt > FALLBACK_MS && now - lastSound > FALLBACK_MS) {
                    source?.stop()
                    useSource(MicSource(), mic = true)
                }
                onFrame(VisualiserRenderer.frame(levels.levels, style(), profile))
                delay(FRAME_MS)
            }
        }
        return true
    }

    private fun useSource(s: SpectrumSource, mic: Boolean): Boolean {
        val ok = s.start { mags, rate ->
            latest = mags
            latestRate = rate
            if (mags.any { it > 1f }) lastSound = SystemClock.uptimeMillis()
        }
        if (ok) {
            source = s
            _usingMic.value = mic
        }
        return ok
    }

    fun stop() {
        loop?.cancel()
        loop = null
        source?.stop()
        source = null
        latest = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    companion object {
        /** About 30 fps. */
        const val FRAME_MS = 33L
        /** How long the output mix may stay silent during music before the microphone takes over. */
        const val FALLBACK_MS = 3_000L
    }
}
