package com.aurora.tunnel.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

object ProfileStore {
    private lateinit var prefs: SharedPreferences
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("profiles", Context.MODE_PRIVATE)
        val raw = prefs.getString("list", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        _profiles.value = (0 until arr.length()).mapNotNull {
            runCatching { Profile.fromJson(arr.getJSONObject(it)) }.getOrNull()
        }
    }

    fun get(id: String?): Profile? = _profiles.value.firstOrNull { it.id == id }

    fun upsert(p: Profile) {
        val cur = _profiles.value.toMutableList()
        val i = cur.indexOfFirst { it.id == p.id }
        if (i >= 0) cur[i] = p else cur.add(p)
        persist(cur)
    }

    fun delete(id: String) = persist(_profiles.value.filterNot { it.id == id })

    private fun persist(list: List<Profile>) {
        _profiles.value = list
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}

enum class ThemeId(val label: String) {
    NIGHT("Aurora Night"),
    GLASS("Aurora Glass"),
    DARK("Gelap"),
    STANDARD("Standar")
}

class BoolPref(private val key: String, private val def: Boolean) {
    var v by mutableStateOf(def)
        private set
    fun load(p: SharedPreferences) { v = p.getBoolean(key, def) }
    fun set(x: Boolean) { v = x; Settings.prefs.edit().putBoolean(key, x).apply() }
}

class IntPref(private val key: String, private val def: Int) {
    var v by mutableStateOf(def)
        private set
    fun load(p: SharedPreferences) { v = p.getInt(key, def) }
    fun set(x: Int) { v = x; Settings.prefs.edit().putInt(key, x).apply() }
}

class StrPref(private val key: String, private val def: String) {
    var v by mutableStateOf(def)
        private set
    fun load(p: SharedPreferences) { v = p.getString(key, def) ?: def }
    fun set(x: String) { v = x; Settings.prefs.edit().putString(key, x).apply() }
}

object Settings {
    lateinit var prefs: SharedPreferences

    val vpnMode = BoolPref("vpnMode", true)              // true = VPN TUN, false = proxy SOCKS5 lokal
    val proxyPort = IntPref("proxyPort", 10808)
    val dns = StrPref("dns", "")                         // kosong = otomatis (lewat terowongan)
    val forceTls = StrPref("forceTls", "auto")           // auto | 1.2 | 1.3
    val mtu = IntPref("mtu", 1400)
    val bypassLan = BoolPref("bypassLan", false)
    val autoReconnect = BoolPref("autoReconnect", true)
    val pingTimeouts = IntPref("pingTimeouts", 3)
    val autoPing = BoolPref("autoPing", true)
    val autoPingSec = IntPref("autoPingSec", 15)
    val autoPingHost = StrPref("autoPingHost", "www.google.com")
    val reconnectDelaySec = IntPref("reconnectDelaySec", 3)
    val compression = BoolPref("compression", false)
    val debugLog = BoolPref("debugLog", false)
    val htmlBanner = BoolPref("htmlBanner", true)
    val bufferKb = IntPref("bufferKb", 0)
    val keepAliveSec = IntPref("keepAliveSec", 15)
    val showProfilesHome = BoolPref("showProfilesHome", true)
    val powerSaving = BoolPref("powerSaving", false)
    val loadBalance = BoolPref("loadBalance", false)

    var theme by mutableStateOf(ThemeId.NIGHT)
        private set
    var selectedProfileId by mutableStateOf<String?>(null)
        private set
    var allowedApps by mutableStateOf<Set<String>>(emptySet())
        private set

    private fun all() = listOf<Any>(vpnMode, proxyPort, dns, forceTls, mtu, bypassLan, autoReconnect, pingTimeouts,
        autoPing, autoPingSec, autoPingHost, reconnectDelaySec, compression, debugLog, htmlBanner, bufferKb,
        keepAliveSec, showProfilesHome, powerSaving, loadBalance)

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        reload()
    }

    private fun reload() {
        all().forEach {
            when (it) { is BoolPref -> it.load(prefs); is IntPref -> it.load(prefs); is StrPref -> it.load(prefs) }
        }
        theme = runCatching { ThemeId.valueOf(prefs.getString("theme", "NIGHT")!!) }.getOrDefault(ThemeId.NIGHT)
        selectedProfileId = prefs.getString("selected", null)
        allowedApps = prefs.getStringSet("allowedApps", emptySet()) ?: emptySet()
        com.aurora.tunnel.log.AppLog.debugEnabled = debugLog.v
    }

    /** Kembalikan semua pengaturan ke bawaan (profil tidak dihapus). */
    fun resetAll() {
        val sel = selectedProfileId
        prefs.edit().clear().commit()
        reload()
        select(sel)
    }

    fun applyTheme(t: ThemeId) { theme = t; prefs.edit().putString("theme", t.name).apply() }
    fun select(id: String?) { selectedProfileId = id; prefs.edit().putString("selected", id).apply() }
    fun applyAllowedApps(s: Set<String>) { allowedApps = s; prefs.edit().putStringSet("allowedApps", s).apply() }
    fun setDebug(v: Boolean) { debugLog.set(v); com.aurora.tunnel.log.AppLog.debugEnabled = v }
}
