package io.github.zopulus.ffc

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.zopulus.ffc.util.ConfigCodec
import io.github.zopulus.ffc.util.ConfigFile
import io.github.zopulus.ffc.util.ConfigPersistence
import io.github.zopulus.ffc.util.ConfigSaveQueue
import io.github.zopulus.ffc.util.ConfigSchema
import io.github.zopulus.ffc.util.ConfigSnapshot
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal object ModuleService : XposedServiceHelper.OnServiceListener {
    // Published by the configuration writer and consulted by provider requests.
    @Volatile
    var remoteOnlyEdit: ConfigSnapshot? = null
    val connection = MutableStateFlow<XposedService?>(null)
    private var appContext: Context? = null
    private var listenerRegistered = false
    private val syncQueued = AtomicBoolean()

    @Synchronized fun bindXposedService(context: Context) {
        appContext = context.applicationContext
        if (listenerRegistered) return
        try {
            XposedServiceHelper.registerListener(this)
            listenerRegistered = true
        } catch (error: Throwable) {
            Log.e("fcmfix", "Could not connect to the Xposed service", error)
        }
    }

    fun scheduleBackgroundSync() {
        val context = appContext ?: return
        if (connection.value == null || !syncQueued.compareAndSet(false, true)) return
        ConfigSaveQueue.submit {
            syncQueued.set(false)
            val service = connection.value ?: return@submit
            try {
                val localStore = object : ConfigPersistence.Store {
                    override fun read(): JSONObject = ConfigFile.read(context)
                    override fun write(json: JSONObject) { ConfigFile.write(context, json) }
                }
                val remoteStore = RemoteConfigStore(service)
                val memory = remoteOnlyEdit?.let(ConfigCodec::toJson)
                ConfigPersistence.synchronize(localStore, remoteStore, memory, System.currentTimeMillis())
                if (remoteStore.changed) {
                    context.sendBroadcast(
                        Intent(ConfigSchema.ACTION_UPDATE_CONFIG)
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    )
                }
            } catch (error: Throwable) {
                Log.w("fcmfix", "Background configuration sync unavailable", error)
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        if (connection.value === service) return
        connection.value = service
        scheduleBackgroundSync()
        appContext?.sendBroadcast(
            Intent(ConfigSchema.ACTION_UPDATE_CONFIG)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .putExtra(ConfigSchema.EXTRA_SERVICE_CONNECTED, true)
        )
    }

    override fun onServiceDied(service: XposedService) {
        connection.compareAndSet(service, null)
    }
}
