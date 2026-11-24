package com.aurora.tunnel.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Format file config Cocote Tunnel: ekstensi ".cct".
 * Config biasa berisi JSON teks. Config yang memakai penguncian disimpan terenkripsi (awalan "CCT1:")
 * supaya isinya tidak terbaca bila file dibuka sebagai teks. Ini pengacakan, bukan keamanan mutlak.
 */
object CctFile {
    const val EXT = "cct"
    private const val MAGIC = "cocote-tunnel"
    private const val PREFIX = "CCT1:"

    private fun key() = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("cocote-tunnel/cct/v1".toByteArray()), "AES")

    fun encode(list: List<Profile>, protect: Boolean = false): String {
        val json = JSONObject().apply {
            put("app", MAGIC); put("format", EXT); put("version", 1)
            put("profiles", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } })
        }.toString(if (protect) 0 else 2)
        if (!protect) return json
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return PREFIX + Base64.encodeToString(iv + c.doFinal(json.toByteArray()), Base64.NO_WRAP)
    }

    private fun plain(text: String): String? {
        val t = text.trim().trimStart('\uFEFF')
        if (!t.startsWith(PREFIX)) return t
        return runCatching {
            val raw = Base64.decode(t.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw.copyOfRange(12, raw.size)))
        }.getOrNull()
    }

    /** Hasil null bila teks bukan file .cct yang valid. Profil yang dibaca selalu diberi id baru. */
    fun decode(text: String): List<Profile>? {
        val t = plain(text) ?: return null
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
        val base = name.trim().removeSuffix(".$EXT").replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "config" }
        return "$base.$EXT"
    }
}
