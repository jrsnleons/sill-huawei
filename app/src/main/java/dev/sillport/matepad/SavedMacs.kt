package dev.sillport.matepad

import android.content.Context

/** A Mac this tablet paired with: its pin (a public-key hash, not a secret) by Mac ID. */
data class SavedMac(val macID: String, val name: String, val fingerprint: ByteArray)

/** How to reach a Mac from afar, from its signed kind 18 (Remote.swift's MacInfo). */
data class RemoteInfo(val issuedAt: Double, val remoteAccess: Boolean, val port: Int, val addresses: List<Triple<String, Int?, String>>)

class SavedMacs(context: Context) {
    private val prefs = context.getSharedPreferences("saved-macs", Context.MODE_PRIVATE)
    private val remote = context.getSharedPreferences("saved-macs-remote", Context.MODE_PRIVATE)

    /** Keeps the Mac's verified info, only if it is newer than the one saved. */
    fun saveRemote(macID: String, infoJson: String) {
        val newer = runCatching { org.json.JSONObject(infoJson).getDouble("issuedAt") }.getOrNull() ?: return
        if ((remote(macID)?.issuedAt ?: 0.0) >= newer) return
        remote.edit().putString(macID, infoJson).apply()
    }

    fun remote(macID: String): RemoteInfo? = remote.getString(macID, null)?.let { s ->
        runCatching {
            val j = org.json.JSONObject(s)
            val arr = j.optJSONArray("addresses") ?: org.json.JSONArray()
            RemoteInfo(j.getDouble("issuedAt"), j.optBoolean("remoteAccess"), j.optInt("remotePort", 7455),
                (0 until arr.length()).map { val a = arr.getJSONObject(it)
                    Triple(a.getString("host"), if (a.has("port")) a.getInt("port") else null, a.optString("via")) })
        }.getOrNull()
    }

    fun all(): List<SavedMac> = prefs.all.mapNotNull { (id, v) ->
        val parts = (v as? String)?.split('|', limit = 2) ?: return@mapNotNull null
        val fp = Pairing.b64uDecode(parts[0]) ?: return@mapNotNull null
        SavedMac(id, parts.getOrElse(1) { "Mac" }, fp)
    }

    fun save(mac: SavedMac) = prefs.edit().putString(mac.macID, Pairing.b64u(mac.fingerprint) + "|" + mac.name).apply()

    fun forget(macID: String) { prefs.edit().remove(macID).apply(); remote.edit().remove(macID).apply() }

    /** The saved Mac whose name matches a Bonjour name ("Mac mini", "Mac mini (2)"). A hint only: the pin decides. */
    fun byName(name: String): SavedMac? = all().firstOrNull { it.name == name || name.startsWith(it.name + " (") }
}
