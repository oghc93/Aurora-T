package com.aurora.tunnel.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Format file config Cocote Tunnel: ekstensi ".cct" (isinya JSON teks).
 * Satu file bisa berisi satu atau banyak profil.
 */
object CctFile {
    const val EXT = "cct"
    private const val MAGIC = "cocote-tunnel"

    fun encode(list: List<Profile>): String = JSONObject().apply {
        put("app", MAGIC); put("format", EXT); put("version", 1)
        put("profiles", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } })
    }.toString(2)

    /** Hasil null bila teks bukan file .cct yang valid. Profil yang dibaca selalu diberi id baru. */
    fun decode(text: String): List<Profile>? {
        val t = text.trim().trimStart('﻿')
        if (!t.startsWith("{")) return null
        return runCatching {
            val j = JSONObject(t)
            if (j.optString("app") != MAGIC) return null
            val arr = j.optJSONArray("profiles") ?: return null
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Profile.fromJson(it).copy(id = UUID.randomUUID().toString()) }
            }
        }.getOrNull()
    }

    fun fileName(name: String): String {
        val base = name.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "config" }
        return "$base.$EXT"
    }
}
