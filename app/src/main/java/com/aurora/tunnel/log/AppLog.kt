package com.aurora.tunnel.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Level(val short: String, val label: String) {
    DEBUG("D", "Debug"), INFO("I", "Info"), WARN("W", "Peringatan"), ERROR("E", "Error")
}

data class LogEntry(
    val id: Long,
    val time: Long,
    val level: Level,
    val tag: String,
    val msg: String,
    val repeat: Int = 1
)

/**
 * Log terpusat: ring buffer, baris identik berurutan digabung (x3),
 * dan baris mentah dari proses eksternal (xray/dnstt) dibersihkan
 * (kode warna ANSI, timestamp ganda, level dideteksi otomatis).
 */
object AppLog {
    private const val MAX = 1500
    private val lock = Any()
    private val buf = ArrayDeque<LogEntry>()
    private var seq = 0L
    @Volatile var debugEnabled = false

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val tsPrefix = Regex("^\\s*(\\d{4}[/-]\\d{2}[/-]\\d{2}[ T])?\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?\\s*")
    private val lvlBracket = Regex("\\[(Debug|Info|Warning|Warn|Error|Fatal)]", RegexOption.IGNORE_CASE)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String) { if (debugEnabled) add(Level.DEBUG, tag, msg) }
    fun i(tag: String, msg: String) = add(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = add(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = add(Level.ERROR, tag, msg)

    /** Baris mentah dari proses eksternal. */
    fun ingest(tag: String, raw: String) {
        var s = ansi.replace(raw, "").trim()
        if (s.isEmpty()) return
        s = tsPrefix.replace(s, "")
        var level = Level.INFO
        val m = lvlBracket.find(s)
        if (m != null) {
            level = when (m.groupValues[1].lowercase()) {
                "debug" -> Level.DEBUG
                "warning", "warn" -> Level.WARN
                "error", "fatal" -> Level.ERROR
                else -> Level.INFO
            }
            s = s.removeRange(m.range).trim()
        } else {
            val l = s.lowercase()
            if (l.startsWith("err") || l.contains("fatal") || l.contains("panic")) level = Level.ERROR
            else if (l.startsWith("warn")) level = Level.WARN
            else if (l.startsWith("begin ") || l.startsWith("end ") || l.startsWith("stream ")) level = Level.DEBUG
        }
        if (level == Level.DEBUG && !debugEnabled) return
        add(level, tag, s)
    }

    private fun add(level: Level, tag: String, msg: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val last = buf.lastOrNull()
            if (last != null && last.level == level && last.tag == tag && last.msg == msg) {
                buf[buf.size - 1] = last.copy(time = now, repeat = last.repeat + 1)
            } else {
                buf.addLast(LogEntry(++seq, now, level, tag, msg))
                while (buf.size > MAX) buf.removeFirst()
            }
            _entries.value = buf.toList()
        }
    }

    fun clear() = synchronized(lock) { buf.clear(); _entries.value = emptyList() }

    fun time(e: LogEntry): String = synchronized(fmt) { fmt.format(Date(e.time)) }

    fun line(e: LogEntry): String {
        val rep = if (e.repeat > 1) "  (x${e.repeat})" else ""
        return "${time(e)} ${e.level.short} ${e.tag.padEnd(5)} ${e.msg}$rep"
    }

    fun export(list: List<LogEntry> = _entries.value): String =
        list.joinToString("\n") { line(it) }
}
