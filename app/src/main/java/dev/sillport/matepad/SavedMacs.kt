package dev.sillport.matepad

import android.content.Context

/** A Mac this tablet paired with: its pin (a public-key hash, not a secret) by Mac ID. */
data class SavedMac(val macID: String, val name: String, val fingerprint: ByteArray)

class SavedMacs(context: Context) {
    private val prefs = context.getSharedPreferences("saved-macs", Context.MODE_PRIVATE)

    fun all(): List<SavedMac> = prefs.all.mapNotNull { (id, v) ->
        val parts = (v as? String)?.split('|', limit = 2) ?: return@mapNotNull null
        val fp = Pairing.b64uDecode(parts[0]) ?: return@mapNotNull null
        SavedMac(id, parts.getOrElse(1) { "Mac" }, fp)
    }

    fun save(mac: SavedMac) = prefs.edit().putString(mac.macID, Pairing.b64u(mac.fingerprint) + "|" + mac.name).apply()

    fun forget(macID: String) = prefs.edit().remove(macID).apply()

    /** The saved Mac whose name matches a Bonjour name ("Mac mini", "Mac mini (2)"). A hint only: the pin decides. */
    fun byName(name: String): SavedMac? = all().firstOrNull { it.name == name || name.startsWith(it.name + " (") }
}
