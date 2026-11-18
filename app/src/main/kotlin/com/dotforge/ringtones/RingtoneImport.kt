package com.dotforge.ringtones

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.dotforge.composition.Pcm
import com.dotforge.composition.RingtoneConverter
import com.dotforge.composition.RingtoneParser
import com.dotforge.composition.SongWindow
import com.dotforge.composition.Timeline
import com.dotforge.composition.TimelineCodec
import com.dotforge.device.DeviceProfile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the import page was reached. */
enum class ImportMode {
    /** "Import a file" (or Open with / Share): glyph ringtones keep or convert their lights, other audio is composed. */
    FILE,

    /** "From a song": lights are always composed from the music. */
    SONG,

    /** A ringtone already in the library: preview and Set as. */
    SAVED,
}

/** A song (audio without lights): the original file, its waveform and length. */
class SongSource(val file: File, val scan: AudioCodec.Scan, /** Already Ogg Opus/Vorbis: a whole-song window is saved without re-encoding. */ val retaggable: Boolean)

/** A file read and ready to preview. */
class RingtoneSource(
    val name: String,
    val kind: ImportKind,
    /** Glyph files: the Ogg (Opus or Vorbis) saved with new comments. Null for songs (made from the window on save). */
    val ogg: File?,
    /** Songs only. */
    val song: SongSource?,
    /** The file's CUSTOM1, which holds the timeline of a ringtone edited in DotForge. */
    val custom1: String? = null,
) {
    /** What the preview plays. */
    val playFile: File get() = ogg ?: song!!.file
}

object RingtoneImporter {
    /** The longest part of a song a ringtone is made from: a ringtone loops, and the light track grows with every second. */
    const val MAX_SONG_MS = SongWindow.MAX_MS
    private const val MAX_FILE_BYTES = 80L * 1024 * 1024
    private const val FADE_OUT_MS = 1_500
    private const val FADE_IN_MS = 30

    /** Reads [uri] into the work folder [dir] and prepares it. Slow: call off the main thread. */
    /** [progress] gets 0-1 while a song is read through (the long part of finding the beat). */
    fun load(context: Context, uri: Uri, mode: ImportMode, dir: File, progress: ((Float) -> Unit)? = null): RingtoneSource {
        dir.deleteRecursively()
        dir.mkdirs()
        val cr = context.contentResolver
        val name = (
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
            )?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Ringtone"
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

        if (kind !is ImportKind.AudioOnly) {
            val audio = File(dir, "audio.ogg")
            if (retaggable) source.moveTo(audio) else AudioCodec.encodeOggOpus(AudioCodec.decode(source).pcm, audio)
            return RingtoneSource(title, kind, audio, null, info?.custom1)
        }
        val scan = AudioCodec.scan(source, progress = progress)
        if (scan.durationMs <= 0) throw IOException("There's no sound in this file.")
        return RingtoneSource(title, kind, null, SongSource(source, scan, retaggable))
    }

    private fun File.moveTo(target: File) {
        if (!renameTo(target)) copyTo(target, overwrite = true)
    }

    /** The chosen part of a song, decoded, and its analysis. */
    class Part(val window: SongWindow, val pcm: Pcm, val analysis: AudioAnalysis)

    /**
     * The lights moved against the sound by [ms]: later (positive) adds dark rows at the start, earlier drops
     * the first rows. The length stays the same.
     */
    fun shift(rows: List<IntArray>, ms: Int): List<IntArray> {
        val n = (ms.toLong() * GlyphAuthor.FPS / 1000).toInt()
        if (n == 0 || rows.isEmpty()) return rows
        val blank = IntArray(rows[0].size)
        return if (n > 0) (List(minOf(n, rows.size)) { blank } + rows).take(rows.size)
        else rows.drop(minOf(-n, rows.size)) + List(minOf(-n, rows.size)) { blank }
    }

    fun analysePart(song: SongSource, window: SongWindow): Part {
        val pcm = AudioCodec.decode(song.file, window.lengthMs, window.startMs).pcm
        if (pcm.frames == 0) throw IOException("There's no sound in this part.")
        return Part(window, pcm, AudioAnalyzer.analyze(pcm.mono(), pcm.sampleRate))
    }

    /** The Phone (3) rows (0-4095) for the chosen settings. [part] is needed for songs. */
    fun generate(
        src: RingtoneSource,
        part: Part?,
        layout: ConvertLayout,
        style: ComposeStyle,
        intensity: Float,
        speed: Float,
        profile: DeviceProfile = DeviceProfile.Phone3,
    ): List<IntArray> = when (val k = src.kind) {
        is ImportKind.Phone3 -> RingtoneConverter.keep(k.rows, profile, intensity)
        is ImportKind.OtherPhone -> RingtoneConverter.convert(k.rows, layout, profile, intensity)
        ImportKind.AudioOnly -> part?.let { AutoComposer.compose(it.analysis, style, profile, intensity, speed) }.orEmpty()
    }

    /** One row as a 0-255 frame for the preview and the matrix. */
    fun toLevels(row: IntArray): IntArray = IntArray(row.size) { GlyphAuthor.toLevel(row[it]) }

    /**
     * The Ogg the ringtone is saved from: the glyph file itself, or for a song the chosen part (a whole Ogg song
     * as it is; otherwise encoded to Opus, faded out when it stops before the song's end).
     */
    fun oggBytes(src: RingtoneSource, part: Part?, dir: File): ByteArray {
        src.ogg?.let { return it.readBytes() }
        val song = src.song!!
        val p = part ?: throw IOException("The song isn't ready yet.")
        if (song.retaggable && p.window.isWhole) return song.file.readBytes()
        var pcm = p.pcm
        if (p.window.startMs > 0) pcm = pcm.fadedIn(FADE_IN_MS)
        if (p.window.cutsEnd) pcm = pcm.fadedOut(FADE_OUT_MS)
        val out = File(dir, "part.ogg")
        AudioCodec.encodeOggOpus(pcm, out)
        return out.readBytes()
    }
}

/**
 * The state of one import page. Lives outside composition (see [ImportSessions]) so a rotation does not read
 * and analyse the file again.
 */
/** "Lights earlier / later" goes this far either way. */
const val SYNC_MAX_MS = 500

class ImportSession(val key: String, private val context: Context, private val uri: Uri, val mode: ImportMode) {
    sealed interface State {
        data object Loading : State
        data class Failed(val message: String) : State
        data class Ready(val source: RingtoneSource) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generation: Job? = null
    private var analysing: Job? = null
    val dir = File(File(context.cacheDir, "ringtone-import"), System.nanoTime().toString())

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
    /** While loading: 0-1 through the song ("Finding the beat… 40%"), or null before it is known. */
    var progress by mutableStateOf<Float?>(null)
        private set
    /** "Lights earlier / later": the lights move by this many ms against the sound, -500..500. */
    var syncMs by mutableIntStateOf(0)
        private set

    /** Songs: the chosen part, as shown while dragging. */
    var window by mutableStateOf<SongWindow?>(null)
        private set
    /** Songs: the part the lights are made from (catches up with [window] after a drag). */
    var part by mutableStateOf<RingtoneImporter.Part?>(null)
        private set
    val updatingPart: Boolean get() = window != null && part?.window != window

    init {
        scope.launch {
            state = try {
                // reading the song is most of the wait: 0-90%, then the chosen part's beats
                val src = withContext(Dispatchers.IO) { RingtoneImporter.load(context, uri, mode, dir) { f -> scope.launch { progress = f * 0.9f } } }
                progress = 0.9f
                title = src.name
                src.song?.let { song ->
                    val w = SongWindow.loudest(song.scan.durationMs, song.scan.envelope, song.scan.bucketMs)
                    window = w
                    part = withContext(Dispatchers.IO) { RingtoneImporter.analysePart(song, w) }
                }
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

    val source: RingtoneSource? get() = (state as? State.Ready)?.source

    fun choose(layout: ConvertLayout) { this.layout = layout; changed() }
    fun choose(style: ComposeStyle) { this.style = style; changed() }
    fun changeIntensity(v: Float) { intensity = v; changed() }
    fun changeSpeed(v: Float) { speed = v; changed() }
    fun changeSync(ms: Int) { syncMs = ms.coerceIn(-SYNC_MAX_MS, SYNC_MAX_MS); changed() }

    /** Moves or resizes the song window; the part is decoded and analysed again once dragging pauses. */
    fun changeWindow(w: SongWindow) {
        if (w == window) return
        window = w
        savedUri = null
        val song = source?.song ?: return
        analysing?.cancel()
        analysing = scope.launch {
            delay(350)
            try {
                part = withContext(Dispatchers.IO) { RingtoneImporter.analysePart(song, w) }
                source?.let { regenerate(it, now = true) }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                error = t.message ?: "Couldn't read that part."
            }
        }
    }

    private fun changed() {
        savedUri = if (mode == ImportMode.SAVED) savedUri else null
        source?.let { regenerate(it, now = false) }
    }

    private fun regenerate(src: RingtoneSource, now: Boolean) {
        generation?.cancel()
        generation = scope.launch {
            if (!now) delay(120)
            val l = layout; val s = style; val i = intensity; val sp = speed; val p = part; val sync = syncMs
            rows = withContext(Dispatchers.Default) { RingtoneImporter.shift(RingtoneImporter.generate(src, p, l, s, i, sp), sync) }
        }
    }

    private var editorState: TimelineEditorState? = null
    private var editorBase: List<IntArray>? = null

    /**
     * The timeline editor for the current lights: the timeline stored in the file's CUSTOM1 when it has one,
     * else the lights as a single imported lane. Kept while the lights it started from are unchanged.
     */
    fun editor(): TimelineEditorState {
        val base = rows
        editorState?.let { if (editorBase === base) return it }
        val stored = TimelineCodec.decode(source?.custom1)?.takeIf { editorState == null }
        val frames = maxOf(base.size, stored?.frames ?: 0, GlyphAuthor.FPS)
        val timeline = stored?.copy(frames = frames) ?: Timeline.fromImported(base, frames)
        val imported = if (stored != null) TimelineCodec.importedRows(stored) else base
        editorBase = base
        return TimelineEditorState(timeline, imported).also { editorState = it }
    }

    /** After the editor saved: the page previews (and saves again) the edited lights. */
    fun applyEdit(edited: List<IntArray>) {
        rows = edited
        editorBase = edited
    }

    /**
     * Saves the lights as a ringtone: [rows], or the editor's [override] rows with its timeline in [custom1].
     * Replaces [replace] (a ringtone of ours) when given, else adds a new one.
     */
    fun save(
        library: RingtoneLibrary,
        override: List<IntArray>? = null,
        custom1: String = GlyphAuthor.EMPTY_ZLIB,
        replace: Uri? = null,
        onSaved: (Uri) -> Unit,
    ) {
        val src = source ?: return
        if (saving) return
        saving = true
        scope.launch {
            analysing?.join()
            generation?.join()
            val name = title.trim().ifEmpty { src.name }
            val lights = override ?: rows
            val p = part
            try {
                val uri = withContext(Dispatchers.IO) {
                    val ogg = GlyphRingtone.write(RingtoneImporter.oggBytes(src, p, dir), name, lights, custom1)
                    if (replace != null) library.replace(replace, name, ogg) else library.save(name, ogg)
                }
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

/** At most one import page at a time; a rotation (or the editor) finds its session again by key. */
object ImportSessions {
    var current: ImportSession? = null
        private set

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
