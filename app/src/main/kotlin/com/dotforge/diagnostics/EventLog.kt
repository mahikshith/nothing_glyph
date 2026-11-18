package com.dotforge.diagnostics

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ToyEvent(val timeMs: Long, val kind: String, val detail: String = "") {
    fun format(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timeMs)) + "  " + kind +
        if (detail.isEmpty()) "" else "  $detail"
}

/** Last [MAX] events received by our toys, kept in a small tab-separated file so they survive process death. */
object EventLog {
    const val MAX = 50
    private const val FILE = "toy_events.tsv"

    private val _events = MutableStateFlow<List<ToyEvent>>(emptyList())
    val events: StateFlow<List<ToyEvent>> = _events.asStateFlow()

    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE)
        file = f
        _events.value = try {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t', limit = 3)
                p[0].toLongOrNull()?.let { ToyEvent(it, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }) }
            }.takeLast(MAX)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun record(context: Context, kind: String, detail: String = "") {
        init(context)
        val list = (_events.value + ToyEvent(System.currentTimeMillis(), kind, detail)).takeLast(MAX)
        _events.value = list
        try {
            file?.writeText(list.joinToString("\n") { "${it.timeMs}\t${it.kind}\t${it.detail.replace('\n', ' ').replace('\t', ' ')}" } + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear(context: Context) {
        init(context)
        _events.value = emptyList()
        try { file?.delete() } catch (_: Exception) {}
    }
}
