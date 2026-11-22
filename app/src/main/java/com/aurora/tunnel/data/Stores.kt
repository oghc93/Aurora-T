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
    GLASS("Aurora Glass")
}

object Settings {
    private lateinit var prefs: SharedPreferences

    var theme by mutableStateOf(ThemeId.NIGHT)
        private set
    var autoReconnect by mutableStateOf(true)
        private set
    var debugLog by mutableStateOf(false)
        private set
    var selectedProfileId by mutableStateOf<String?>(null)
        private set
    var allowedApps by mutableStateOf<Set<String>>(emptySet())
        private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        theme = runCatching { ThemeId.valueOf(prefs.getString("theme", "NIGHT")!!) }
            .getOrDefault(ThemeId.NIGHT)
        autoReconnect = prefs.getBoolean("autoReconnect", true)
        debugLog = prefs.getBoolean("debugLog", false)
        selectedProfileId = prefs.getString("selected", null)
        allowedApps = prefs.getStringSet("allowedApps", emptySet()) ?: emptySet()
    }

    fun applyTheme(t: ThemeId) { theme = t; prefs.edit().putString("theme", t.name).apply() }
    fun applyAutoReconnect(v: Boolean) { autoReconnect = v; prefs.edit().putBoolean("autoReconnect", v).apply() }
    fun applyDebugLog(v: Boolean) { debugLog = v; prefs.edit().putBoolean("debugLog", v).apply() }
    fun select(id: String?) { selectedProfileId = id; prefs.edit().putString("selected", id).apply() }
    fun applyAllowedApps(s: Set<String>) { allowedApps = s; prefs.edit().putStringSet("allowedApps", s).apply() }
}
