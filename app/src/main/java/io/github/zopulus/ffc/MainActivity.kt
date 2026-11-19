package io.github.zopulus.ffc

import io.github.zopulus.ffc.util.ConfigSchema.*
import io.github.zopulus.ffc.util.ConfigSnapshot
import io.github.zopulus.ffc.util.ConfigCodec
import io.github.zopulus.ffc.util.ConfigFile
import io.github.zopulus.ffc.util.ConfigSaveQueue
import io.github.zopulus.ffc.util.ConfigPersistence
import io.github.zopulus.ffc.util.FcmApps
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.zopulus.ffc.ui.AppListRefresh
import io.github.zopulus.ffc.ui.FcmfixInterface
import io.github.zopulus.ffc.ui.InterfaceStyle
import io.github.zopulus.ffc.ui.MainTab
import io.github.zopulus.ffc.util.IceboxUtils
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "fcmfix-ui"
private const val ACTION_UPDATE_CONFIG = "io.github.zopulus.ffc.update.config"

data class FcmfixConfig(
    val allowedPackages: Set<String> = emptySet(),
    val disableAutoCleanNotification: Boolean = false,
    val includeIceBoxDisabledApps: Boolean = false,
    val deepSleepGoogleWhitelist: Boolean = defaultValue(KEY_DEEP_SLEEP_GOOGLE_WHITELIST),
    val dozeGoogleWhitelist: Boolean = defaultValue(KEY_DOZE_GOOGLE_WHITELIST),
    val rootDeepSleepNetworkWhitelist: Boolean = false,
    val autoAllowNewApps: Boolean = defaultValue(KEY_AUTO_ALLOW_NEW_APPS),
    val installEvents: Set<String> = emptySet()
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap
)

data class FcmfixUiState(
    val config: FcmfixConfig = FcmfixConfig(),
    val xposedConnected: Boolean = false,
    val appsLoading: Boolean = true,
    val appsLoadFailed: Boolean = false
)

/** Optimistic configuration survives Activity recreation while the save queue drains. */
private object ConfigMemory : XposedServiceHelper.OnServiceListener {
    var latestRevision = 0L
    var editGeneration = 0L
    var initialized = false
    val connection = MutableStateFlow<XposedService?>(null)
    private var listenerRegistered = false
    val state = androidx.compose.runtime.mutableStateOf(FcmfixUiState())

    fun bindXposedService() {
        if (listenerRegistered) return
        try {
            XposedServiceHelper.registerListener(this)
            listenerRegistered = true
        } catch (error: Throwable) {
            Log.e(TAG, "Could not connect to the Xposed service", error)
        }
    }

    override fun onServiceBind(service: XposedService) {
        connection.value = service
    }

    override fun onServiceDied(service: XposedService) {
        connection.compareAndSet(service, null)
    }
}

class MainActivity : ComponentActivity() {
    private val configRefreshQueued = AtomicBoolean()
    private val configObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { loadConfigFromAvailableSource() }
    }
    private val xposedService: XposedService?
        get() = ConfigMemory.connection.value
    private var latestRevision: Long
        get() = ConfigMemory.latestRevision
        set(value) { ConfigMemory.latestRevision = value }
    private var editGeneration: Long
        get() = ConfigMemory.editGeneration
        set(value) { ConfigMemory.editGeneration = value }
    private val uiState = ConfigMemory.state
    private var installedApps = androidx.compose.runtime.mutableStateOf<List<InstalledApp>>(emptyList())
    // Activity recreation drops locale/theme-dependent presentation; manual refresh also bypasses it.
    private val appCache = mutableMapOf<String, Pair<Long, InstalledApp>>()
    private var appsLoadingJob: Job? = null
    private val appListRefresh = AppListRefresh()
    private var packageChangesRegistered = false
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A replacement also broadcasts removal and addition. Reload after it finishes.
            if ((intent.action == Intent.ACTION_PACKAGE_REMOVED || intent.action == Intent.ACTION_PACKAGE_ADDED)
                && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) return

            appListRefresh.invalidate()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) loadInstalledApps()
        }
    }
    private var launcherIconHidden = androidx.compose.runtime.mutableStateOf(false)
    private val launcherAliasComponent by lazy {
        ComponentName(packageName, "$packageName.LauncherAlias")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherIconHidden.value = packageManager.getComponentEnabledSetting(launcherAliasComponent) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        if (!ConfigMemory.initialized) {
            loadConfigFromLocalFile()
            ConfigMemory.initialized = true
        }
        initializeDefaultConfigIfNeeded()
        contentResolver.registerContentObserver(Uri.parse("content://$packageName.provider/config"), false, configObserver)
        if (uiState.value.config.includeIceBoxDisabledApps) requestIceBoxPermissionIfNeeded()
        ConfigMemory.bindXposedService()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConfigMemory.connection.collect { service ->
                    uiState.value = uiState.value.copy(xposedConnected = service != null)
                    loadConfigFromAvailableSource()
                }
            }
        }

        registerPackageChanges()
        val interfacePreferences = getSharedPreferences("interface", MODE_PRIVATE)
        setContent {
            var style by remember {
                mutableStateOf(InterfaceStyle.fromPreference(interfacePreferences.getString("style", null)))
            }
            var initialTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
            FcmfixInterface(
                style = style,
                initialTab = initialTab,
                onTabChange = { initialTab = it },
                onStyleChange = { selected ->
                    if (selected != style) {
                        interfacePreferences.edit().putString("style", selected.preferenceValue).apply()
                        initialTab = MainTab.SETTINGS
                        style = selected
                    }
                },
                state = uiState.value,
                apps = installedApps.value,
                versionName = BuildConfig.VERSION_NAME,
                launcherIconHidden = launcherIconHidden.value,
                onConfigChange = ::updateConfig,
                onLauncherIconHiddenChange = ::setLauncherIconHidden,
                onOpenDiagnostics = ::openGmsDiagnostics,
                onOpenProject = ::openProjectPage,
                onReloadApps = { loadInstalledApps(forceRefresh = true) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        launcherIconHidden.value = packageManager.getComponentEnabledSetting(launcherAliasComponent) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (!packageChangesRegistered || uiState.value.appsLoadFailed) appListRefresh.invalidate()
        loadInstalledApps()
    }

    private fun registerPackageChanges() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        try {
            registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            packageChangesRegistered = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Package-change monitoring unavailable; refreshing on resume", error)
        }
    }

    override fun onDestroy() {
        if (packageChangesRegistered) unregisterReceiver(packageChangeReceiver)
        contentResolver.unregisterContentObserver(configObserver)
        super.onDestroy()
    }

    private fun setLauncherIconHidden(hidden: Boolean) {
        try {
            packageManager.setComponentEnabledSetting(
                launcherAliasComponent,
                if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            launcherIconHidden.value = hidden
        } catch (error: Throwable) {
            Toast.makeText(this, "无法更改桌面图标状态", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Could not change launcher icon visibility", error)
        }
    }

    private fun requestIceBoxPermissionIfNeeded() {
        try {
            if (ContextCompat.checkSelfPermission(this, IceboxUtils.SDK_PERMISSION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(IceboxUtils.SDK_PERMISSION),
                    IceboxUtils.REQUEST_CODE
                )
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Icebox permission request unavailable", error)
        }
    }

    private fun initializeDefaultConfigIfNeeded() {
        val appContext = applicationContext
        // Queue before service synchronization and user edits. Revision zero allows
        // any newer remote configuration to replace the initial defaults.
        ConfigSaveQueue.submit {
            try {
                if (ConfigFile.initializeIfMissing(appContext, FcmfixConfig().toJson()
                        .put("revision", 0L).put("bootstrapDefaults", true))) {
                    appContext.sendBroadcast(Intent(ACTION_UPDATE_CONFIG))
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not initialize default configuration", error)
                runOnUiThread {
                    Toast.makeText(this, "默认设置保存失败，请重新打开页面重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadConfigFromAvailableSource() {
        if (!configRefreshQueued.compareAndSet(false, true)) return
        val generation = editGeneration
        ConfigSaveQueue.submit {
            // Coalesce waiting reads, but allow a new refresh once this read starts.
            configRefreshQueued.set(false)
            try {
                // Read when this queued task actually executes, after earlier edits finish.
                val persisted = runCatching { ConfigFile.read(applicationContext) }.getOrNull()
                val localRevision = persisted?.optLong("revision", 0) ?: -1L
                val remote = readRemoteSnapshot()
                // Preserve pre-revision remote settings over freshly bootstrapped defaults.
                val useRemote = remote != null && (
                    remote.revision > localRevision ||
                        remote.revision == localRevision && persisted?.optBoolean("bootstrapDefaults", false) == true
                    )
                val snapshot = if (useRemote) remote else persisted?.let(ConfigCodec::fromJson)
                if (snapshot != null) {
                    val config = configFromSnapshot(snapshot)
                    if (useRemote) {
                        runCatching {
                            ConfigFile.write(applicationContext, config.toJson().put("revision", snapshot.revision))
                        }.onFailure { Log.w(TAG, "Local sync unavailable; using remote configuration", it) }
                    } else {
                        saveRemote(snapshot)
                    }
                    runOnUiThread {
                        if (generation == editGeneration) {
                            latestRevision = maxOf(latestRevision, snapshot.revision)
                            uiState.value = uiState.value.copy(config = config)
                        }
                    }
                }
                applicationContext.sendBroadcast(Intent(ACTION_UPDATE_CONFIG))
            } catch (error: Throwable) { Log.w(TAG, "Config synchronization unavailable", error) }
        }
    }

    private fun readRemoteSnapshot(): ConfigSnapshot? = runCatching {
        val values = xposedService?.getRemotePreferences("config")?.all ?: return@runCatching null
        if (values["init"] == true) ConfigSnapshot(values) else null
    }.onFailure { Log.w(TAG, "Remote configuration unavailable", it) }.getOrNull()

    private fun configFromSnapshot(snapshot: ConfigSnapshot) = FcmfixConfig(
        allowedPackages = snapshot.allowList.toSet(),
        disableAutoCleanNotification = snapshot.options.getValue(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION),
        includeIceBoxDisabledApps = snapshot.options.getValue(KEY_INCLUDE_ICEBOX_DISABLED_APP),
        deepSleepGoogleWhitelist = snapshot.options.getValue(KEY_DEEP_SLEEP_GOOGLE_WHITELIST),
        dozeGoogleWhitelist = snapshot.options.getValue(KEY_DOZE_GOOGLE_WHITELIST),
        rootDeepSleepNetworkWhitelist = snapshot.options.getValue(KEY_ROOT_DEEP_SLEEP_NETWORK_WHITELIST),
        autoAllowNewApps = snapshot.options.getValue(KEY_AUTO_ALLOW_NEW_APPS),
        installEvents = snapshot.installEvents.toSet()
    )

    private fun configFromJson(json: JSONObject) = configFromSnapshot(ConfigCodec.fromJson(json))

    private fun loadConfigFromLocalFile() {
        try {
            val json = ConfigFile.read(this)
            val config = configFromJson(json)
            latestRevision = json.optLong("revision", 0)
            uiState.value = uiState.value.copy(config = config)
        } catch (error: Throwable) {
            Log.i(TAG, "Using default configuration", error)
        }
    }

    private fun FcmfixConfig.normalized() = copy(
        rootDeepSleepNetworkWhitelist = deepSleepGoogleWhitelist && rootDeepSleepNetworkWhitelist
    )

    private fun updateConfig(requested: FcmfixConfig) {
        val previous = uiState.value.config
        val config = requested.normalized()
        if (config == previous) return
        if (config.includeIceBoxDisabledApps && !previous.includeIceBoxDisabledApps) requestIceBoxPermissionIfNeeded()
        editGeneration++
        val generation = editGeneration
        latestRevision = maxOf(latestRevision + 1, System.currentTimeMillis())
        val revision = latestRevision
        uiState.value = uiState.value.copy(config = config)
        ConfigSaveQueue.submit {
            val result = runCatching {
                ConfigPersistence.save(
                    object : ConfigPersistence.Store {
                        override fun read(): JSONObject = ConfigFile.read(applicationContext)
                        override fun write(json: JSONObject) { ConfigFile.write(applicationContext, json) }
                    },
                    object : ConfigPersistence.Store {
                        override fun read(): JSONObject? = readRemoteSnapshot()?.let { snapshot ->
                            configFromSnapshot(snapshot).toJson().put("revision", snapshot.revision)
                        }
                        override fun write(json: JSONObject) {
                            if (!saveRemote(ConfigCodec.fromJson(json))) {
                                throw java.io.IOException("Remote configuration save unavailable")
                            }
                        }
                    }, previous.toJson(), config.toJson(), revision, System.currentTimeMillis())
            }.onFailure { Log.e(TAG, "Could not prepare configuration save", it) }.getOrNull()
            result?.localFailure?.let { Log.w(TAG, "Local configuration save failed", it) }
            if (result == null || !result.localSaved && !result.remoteSaved) {
                runOnUiThread {
                    Toast.makeText(this, "设置保存失败，请重试", Toast.LENGTH_SHORT).show()
                    if (generation == editGeneration) {
                        uiState.value = uiState.value.copy(config = previous)
                        loadConfigFromAvailableSource()
                    }
                }
            } else {
                val saved = result.config
                val persistedConfig = configFromJson(saved)
                val persistedRevision = saved.getLong("revision")
                runOnUiThread {
                    latestRevision = maxOf(latestRevision, persistedRevision)
                    if (generation == editGeneration) {
                        uiState.value = uiState.value.copy(config = persistedConfig)
                    }
                }
                applicationContext.sendBroadcast(Intent(ACTION_UPDATE_CONFIG))
            }
        }
    }

    private fun saveRemote(snapshot: ConfigSnapshot): Boolean = try {
        val preferences = xposedService?.getRemotePreferences("config")
        when {
            preferences == null -> false
            snapshot.matchesPreferences(preferences.all) -> true
            else -> {
                val editor = preferences.edit()
                    .putBoolean("init", true)
                    .putLong("revision", snapshot.revision)
                    .putStringSet("allowList", snapshot.allowList.toMutableSet())
                    .putStringSet(KEY_INSTALL_EVENTS, snapshot.installEvents.toMutableSet())
                RETIRED_KEYS.forEach(editor::remove)
                snapshot.options.forEach { (key, enabled) -> editor.putBoolean(key, enabled) }
                editor.commit()
            }
        }
    } catch (error: Throwable) {
        Log.w(TAG, "Remote save unavailable; local configuration is retained", error)
        false
    }

    private fun FcmfixConfig.toJson() = JSONObject()
        .put("allowList", JSONArray(allowedPackages.sorted()))
        .put(KEY_INSTALL_EVENTS, JSONArray(installEvents.toList()))
        .put(KEY_AUTO_ALLOW_NEW_APPS, autoAllowNewApps)
        .put(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION, disableAutoCleanNotification)
        .put(KEY_INCLUDE_ICEBOX_DISABLED_APP, includeIceBoxDisabledApps)
        .put(KEY_DEEP_SLEEP_GOOGLE_WHITELIST, deepSleepGoogleWhitelist)
        .put(KEY_DOZE_GOOGLE_WHITELIST, dozeGoogleWhitelist)
        .put(KEY_ROOT_DEEP_SLEEP_NETWORK_WHITELIST, rootDeepSleepNetworkWhitelist)

    private fun loadInstalledApps(forceRefresh: Boolean = false) {
        if (forceRefresh) appListRefresh.invalidate(force = true)
        if (appsLoadingJob?.isActive == true) return
        val first = appListRefresh.take() ?: return

        uiState.value = uiState.value.copy(appsLoading = true, appsLoadFailed = false)
        appsLoadingJob = lifecycleScope.launch {
            var request: AppListRefresh.Mode? = first
            while (request != null) {
                val clearCache = request == AppListRefresh.Mode.FORCED
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (clearCache) appCache.clear()
                        queryInstalledApps()
                    }
                }
                request = if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    appListRefresh.take()
                } else null

                result.onSuccess { apps -> installedApps.value = apps }
                    .onFailure { error -> Log.e(TAG, "Could not read installed applications", error) }
                uiState.value = uiState.value.copy(
                    appsLoading = request != null,
                    appsLoadFailed = request == null && result.isFailure,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryInstalledApps(): List<InstalledApp> {
        val flags = PackageManager.GET_RECEIVERS or
            PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        val collator = Collator.getInstance(Locale.getDefault())
        return packageManager.getInstalledPackages(flags).mapNotNull { packageInfo: PackageInfo ->
            val receivers = packageInfo.receivers ?: return@mapNotNull null
            if (!receivers.any(::isFcmReceiver)) return@mapNotNull null
            val applicationInfo = packageInfo.applicationInfo ?: return@mapNotNull null
            try {
                val cached = appCache[packageInfo.packageName]
                if (cached != null && cached.first == packageInfo.lastUpdateTime) return@mapNotNull cached.second
                InstalledApp(
                    packageName = packageInfo.packageName,
                    label = applicationInfo.loadLabel(packageManager).toString(),
                    icon = drawableToBitmap(applicationInfo.loadIcon(packageManager))
                ).also { appCache[packageInfo.packageName] = packageInfo.lastUpdateTime to it }
            } catch (error: Throwable) {
                Log.w(TAG, "Skipping ${packageInfo.packageName}", error)
                null
            }
        }.also { apps ->
            appCache.keys.retainAll(apps.mapTo(mutableSetOf(), InstalledApp::packageName))
        }.sortedWith { first, second -> collator.compare(first.label, second.label) }
    }

    private fun isFcmReceiver(receiver: ActivityInfo): Boolean =
        FcmApps.isReceiverName(receiver.name)

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val size = (96 * resources.displayMetrics.density / 2f).toInt().coerceIn(72, 144)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    private fun openGmsDiagnostics() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(
                "com.google.android.gms",
                "com.google.android.gms.gcm.GcmDiagnostics"
            )
            setPackage("com.google.android.gms")
        }
        try {
            startActivity(intent)
        } catch (error: Throwable) {
            Toast.makeText(this, "无法打开 Google Play 服务诊断页面", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "GMS diagnostics activity unavailable", error)
        }
    }

    private fun openProjectPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/zopulus/fcmfix-coloros17")))
        } catch (error: Throwable) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }
}
