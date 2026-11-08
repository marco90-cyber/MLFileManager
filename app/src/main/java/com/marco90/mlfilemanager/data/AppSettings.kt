package com.marco90.mlfilemanager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Impostazioni dell'app: si salvano da sole e aggiornano l'interfaccia (sono stati Compose). */
class AppSettings private constructor(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var showHidden by bool("show_hidden", false)
    var foldersFirst by bool("folders_first", true)
    var thumbnails by bool("thumbnails", true)
    var useTrash by bool("use_trash", true)
    var confirmDelete by bool("confirm_delete", true)
    var showSystem by bool("show_system", true)
    var binaryUnits by bool("binary_units", false)
    var dateFormat by str("date_format", "d MMM yyyy")
    var sortBy by str("sort_by", "name")          // name | date | size | type
    var sortDesc by bool("sort_desc", false)
    var gridView by bool("grid_view", false)

    // Accesso da rete
    var netPort by int("net_port", 6235)
    var netPassword by str("net_password", "0000")
    var netRandomPassword by bool("net_random_password", false)
    var netShowHidden by bool("net_show_hidden", false)
    var netReadOnly by bool("net_read_only", false)
    var netFtp by bool("net_ftp", true)
    var netHttp by bool("net_http", true)

    private fun bool(key: String, def: Boolean) = Pref(p.getBoolean(key, def)) { p.edit().putBoolean(key, it).apply() }
    private fun str(key: String, def: String) = Pref(p.getString(key, def) ?: def) { p.edit().putString(key, it).apply() }
    private fun int(key: String, def: Int) = Pref(p.getInt(key, def)) { p.edit().putInt(key, it).apply() }

    class Pref<T>(initial: T, private val save: (T) -> Unit) {
        private var state by mutableStateOf(initial)
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = state
        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            state = value
            save(value)
        }
    }

    companion object {
        @Volatile private var instance: AppSettings? = null
        fun get(ctx: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(ctx.applicationContext).also { instance = it }
            }
    }
}
