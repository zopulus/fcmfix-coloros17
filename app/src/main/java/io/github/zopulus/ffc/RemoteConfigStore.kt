package io.github.zopulus.ffc

import io.github.libxposed.service.XposedService
import io.github.zopulus.ffc.util.ConfigCodec
import io.github.zopulus.ffc.util.ConfigPersistence
import io.github.zopulus.ffc.util.ConfigSchema
import org.json.JSONObject
import java.io.IOException

internal class RemoteConfigStore(private val service: XposedService?) : ConfigPersistence.Store {
    private var observed: Map<String, *>? = null
    var changed = false
        private set

    override fun read(): JSONObject? {
        observed = null
        val values = service?.getRemotePreferences("config")?.all ?: throw IOException("Remote configuration unavailable")
        val snapshot = ConfigCodec.fromPreferences(values)
        observed = values
        return snapshot?.let(ConfigCodec::toJson)
    }

    override fun write(json: JSONObject) {
        val preferences = service?.getRemotePreferences("config") ?: throw IOException("Remote configuration unavailable")
        val expected = observed ?: throw IOException("Remote configuration has not been read")
        val snapshot = ConfigCodec.fromJson(json)
        if (snapshot.matchesPreferences(expected)) return
        val current = preferences.all
        if (current != expected) throw ConfigPersistence.RemoteChanged()
        val editor = preferences.edit().putBoolean("init", true).putLong("revision", snapshot.revision)
            .putStringSet("allowList", snapshot.allowList.toMutableSet())
            .putStringSet(ConfigSchema.KEY_INSTALL_EVENTS, snapshot.installEvents.toMutableSet())
        ConfigSchema.RETIRED_KEYS.forEach(editor::remove)
        snapshot.options.forEach { (key, enabled) -> editor.putBoolean(key, enabled) }
        if (!editor.commit()) throw IOException("Remote configuration write failed")
        changed = true
    }
}
