package com.aurora.tunnel.engine

import java.util.concurrent.atomic.AtomicInteger

/** Mendukung pola [rotate=a;b;c] pada alamat/host/SNI: memilih bergantian tiap koneksi. */
object Rotate {
    private val counter = AtomicInteger(0)
    private val re = Regex("\\[rotate=([^\\]]*)]")

    fun pick(s: String): String = re.replace(s) {
        val opts = it.groupValues[1].split(';').map { o -> o.trim() }.filter { o -> o.isNotEmpty() }
        if (opts.isEmpty()) "" else opts[counter.getAndIncrement() % opts.size]
    }.trim()

    /** Pilihan pertama, untuk tampilan/ping tanpa menggeser counter. */
    fun first(s: String): String = re.replace(s) {
        it.groupValues[1].split(';').map { o -> o.trim() }.firstOrNull { o -> o.isNotEmpty() } ?: ""
    }.trim()
}
