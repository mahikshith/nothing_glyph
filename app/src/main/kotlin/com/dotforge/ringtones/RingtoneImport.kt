package com.dotforge.ringtones

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dotforge.composition.AudioAnalysis
import com.dotforge.composition.AudioAnalyzer
import com.dotforge.composition.AutoComposer
import com.dotforge.composition.ComposeStyle
import com.dotforge.composition.ConvertLayout
import com.dotforge.composition.GlyphAuthor
import com.dotforge.composition.GlyphRingtone
import com.dotforge.composition.ImportKind
import com.dotforge.composition.OggRepager
import com.dotforge.composition.RingtoneConverter
import com.dotforge.composition.RingtoneParser
import com.dotforge.device.DeviceProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** How the import page was reached. */
enum class ImportMode {
    /** "Import a file" (or Open with / Share): glyph ringtones keep or convert their lights, other audio is composed. */
    FILE,

    /** "From a song": lights are always composed from the music. */
    SONG,

    /** A ringtone already in the library: preview and Set as. */
    SAVED,
}

/** A file read and ready to preview: its kind, the audio that will be saved, and what lights are made from. */
class RingtoneSource(
    val name: String,
    val kind: ImportKind,
    /** Ogg (Opus or Vorbis) whose comments get replaced on save; also what the preview plays. */
    val audio: File,
    val analysis: AudioAnalysis?,
    /** The song was longer than [RingtoneImporter.MAX_SONG_MS] and was cut (with a fade-out). */
    val trimmed: Boolean,
)

object RingtoneImporter {
    /** Songs are cut to this length: a ringtone loops, and the light track grows with every second. */
    const val MAX_SONG_MS = 40_000L
    private const val MAX_FILE_BYTES = 80L * 1024 * 1024

    /** Reads [uri] into a fresh work folder and prepares it. Slow: call off the main thread. */
    fun load(context: Context, uri: Uri, mode: ImportMode, dir: File): RingtoneSource {
        dir.deleteRecursively()
        dir.mkdirs()
        val cr = context.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Ringtone"
        val source = File(dir, "source")
        val input = cr.openInputStream(uri) ?: throw IOException("Couldn't open this file.")
        input.use { inp ->
            source.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE_BYTES) throw IOException("This file is too big for a ringtone.")
                    out.write(buf, 0, n)
                }
            }
        }
        val bytes = source.readBytes()
        val isOgg = bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "OggS"
        val info = if (isOgg) runCatching { RingtoneParser.parse(bytes) }.getOrNull() else null
        val kind = if (mode == ImportMode.SONG) ImportKind.AudioOnly else ImportKind.of(info)
        val title = info?.tags?.first("TITLE")?.takeIf { it.isNotBlank() && mode == ImportMode.SAVED } ?: name
        val retaggable = isOgg && OggRepager.canRetag(bytes)
        val audio = File(dir, "audio.ogg")

        if (kind !is ImportKind.AudioOnly) {
            if (retaggable) source.moveTo(audio) else AudioCodec.encodeOggOpus(AudioCodec.decode(source).pcm, audio)
            return RingtoneSource(title, kind, audio, null, trimmed = false)
        }
        val decoded = AudioCodec.decode(source, MAX_SONG_MS)
        if (decoded.pcm.frames == 0) throw IOException("There's no sound in this file.")
        val analysis = AudioAnalyzer.analyze(decoded.pcm.mono(), decoded.pcm.sampleRate)
        when {
            retaggable && !decoded.cut -> source.moveTo(audio)
            decoded.cut -> AudioCodec.encodeOggOpus(decoded.pcm.fadedOut(1_500), audio)
            else -> AudioCodec.encodeOggOpus(decoded.pcm, audio)
        }
        source.delete()
        return RingtoneSource(title, kind, audio, analysis, decoded.cut)
    }

    private fun File.moveTo(target: File) {
        if (!renameTo(target)) copyTo(target, overwrite = true)
    }

    /** The Phone (3) rows (0-4095) for the chosen settings. */
    fun generate(
        src: RingtoneSource,
        layout: ConvertLayout,
        style: ComposeStyle,
        intensity: Float,
        speed: Float,
        profile: DeviceProfile = DeviceProfile.Phone3,
    ): List<IntArray> = when (val k = src.kind) {
        is ImportKind.Phone3 -> RingtoneConverter.keep(k.rows, profile, intensity)
        is ImportKind.OtherPhone -> RingtoneConverter.convert(k.rows, layout, profile, intensity)
        ImportKind.AudioOnly -> AutoComposer.compose(src.analysis!!, style, profile, intensity, speed)
    }

    /** One row as a 0-255 frame for the preview and the matrix. */
    fun toLevels(row: IntArray): IntArray = IntArray(row.size) { GlyphAuthor.toLevel(row[it]) }

    /** The saved file: [src]'s audio with our glyph comments. */
    fun write(src: RingtoneSource, title: String, rows: List<IntArray>): ByteArray =
        GlyphRingtone.write(src.audio.readBytes(), title, rows)
}

/**
 * The state of one import page. Lives outside composition (see [ImportSessions]) so a rotation does not read
 * and analyse the file again.
 */
class ImportSession(val key: String, private val context: Context, private val uri: Uri, val mode: ImportMode) {
    sealed interface State {
        data object Loading : State
        data class Failed(val message: String) : State
        data class Ready(val source: RingtoneSource) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generation: Job? = null
    private val dir = File(File(context.cacheDir, "ringtone-import"), System.nanoTime().toString())

    var state by mutableStateOf<State>(State.Loading)
        private set
    var layout by mutableStateOf(ConvertLayout.RINGS)
        private set
    var style by mutableStateOf(ComposeStyle.PULSE)
        private set
    var intensity by mutableFloatStateOf(1f)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var rows by mutableStateOf<List<IntArray>>(emptyList())
        private set
    var title by mutableStateOf("")
    var saving by mutableStateOf(false)
        private set
    /** Set once saved (or from the start for a library ringtone). */
    var savedUri by mutableStateOf(if (mode == ImportMode.SAVED) uri else null)
        private set
    var error by mutableStateOf<String?>(null)

    init {
        scope.launch {
            state = try {
                val src = withContext(Dispatchers.IO) { RingtoneImporter.load(context, uri, mode, dir) }
                title = src.name
                State.Ready(src).also { regenerate(it.source, now = true) }
            } catch (t: CancellationException) {
                throw t
            } catch (t: IOException) {
                State.Failed(t.message?.takeIf { it.isNotBlank() } ?: "This file can't be read.")
            } catch (t: SecurityException) {
                State.Failed("This file isn't available any more. Pick it again.")
            } catch (t: Exception) {
                State.Failed("This file can't be read.")
            }
        }
    }

    private val source get() = (state as? State.Ready)?.source

    fun choose(layout: ConvertLayout) { this.layout = layout; changed() }
    fun choose(style: ComposeStyle) { this.style = style; changed() }
    fun changeIntensity(v: Float) { intensity = v; changed() }
    fun changeSpeed(v: Float) { speed = v; changed() }

    private fun changed() {
        savedUri = if (mode == ImportMode.SAVED) savedUri else null
        source?.let { regenerate(it, now = false) }
    }

    private fun regenerate(src: RingtoneSource, now: Boolean) {
        generation?.cancel()
        generation = scope.launch {
            if (!now) delay(120)
            val l = layout; val s = style; val i = intensity; val sp = speed
            rows = withContext(Dispatchers.Default) { RingtoneImporter.generate(src, l, s, i, sp) }
        }
    }

    fun save(library: RingtoneLibrary, onSaved: (Uri) -> Unit) {
        val src = source ?: return
        if (saving) return
        saving = true
        scope.launch {
            generation?.join()
            val name = title.trim().ifEmpty { src.name }
            try {
                val uri = withContext(Dispatchers.IO) { library.save(name, RingtoneImporter.write(src, name, rows)) }
                savedUri = uri
                onSaved(uri)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                error = t.message ?: "Couldn't save."
            } finally {
                saving = false
            }
        }
    }

    fun close() {
        scope.cancel()
        Thread { dir.deleteRecursively() }.start()
    }
}

/** At most one import page at a time; a rotation finds its session again by key. */
object ImportSessions {
    private var current: ImportSession? = null

    fun open(context: Context, uri: Uri, mode: ImportMode): ImportSession {
        val key = "$mode|$uri"
        current?.let { if (it.key == key) return it else it.close() }
        return ImportSession(key, context.applicationContext, uri, mode).also { current = it }
    }

    fun close(session: ImportSession) {
        if (current === session) current = null
        session.close()
    }
}
