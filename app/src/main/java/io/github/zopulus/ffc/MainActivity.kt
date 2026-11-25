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
import android.content.pm.ApplicationInfo
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "fcmfix-ui"

data class FcmfixConfig(
    val allowedPackages: Set<String> = emptySet(),
    val disableAutoCleanNotification: Boolean = false,
    val includeIceBoxDisabledApps: Boolean = false,
    val adaptiveWifiHeartbeat: Boolean = defaultValue(KEY_ADAPTIVE_WIFI_HEARTBEAT),
    val autoAllowNewApps: Boolean = defaultValue(KEY_AUTO_ALLOW_NEW_APPS),
    val installEvents: Set<String> = emptySet()
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

data class FcmfixUiState(
    val config: FcmfixConfig = FcmfixConfig(),
    val xposedConnected: Boolean = false,
    val appsLoading: Boolean = true,
    val appsLoadFailed: Boolean = false
)

/** Optimistic configuration and scanned app list survive Activity recreation. */
private object ConfigMemory {
    var latestRevision = 0L
    var editGeneration = 0L
    var initialized = false
    // Accessed only by the serial configuration writer.
    var lastReloadRevision = -1L
    var lastReloadService: XposedService? = null
    val configRefreshQueued = AtomicBoolean()
    val mainHandler = Handler(Looper.getMainLooper())
    val state = androidx.compose.runtime.mutableStateOf(FcmfixUiState())
    val installedApps = androidx.compose.runtime.mutableStateOf<List<InstalledApp>>(emptyList())
    val appListRefresh = AppListRefresh()
    var lastChangedSequence = -1
    var lastLocale: Locale? = null
}

class MainActivity : ComponentActivity() {
    private val configObserver = object : ContentObserver(ConfigMemory.mainHandler) {
        override fun onChange(selfChange: Boolean) { loadConfigFromAvailableSource(applicationContext) }
    }
    private var latestRevision: Long
        get() = ConfigMemory.latestRevision
        set(value) { ConfigMemory.latestRevision = value }
    private var editGeneration: Long
        get() = ConfigMemory.editGeneration
        set(value) { ConfigMemory.editGeneration = value }
    private val uiState = ConfigMemory.state
    private val installedApps = ConfigMemory.installedApps
    private var appsLoadingJob: Job? = null
    private val appListRefresh = ConfigMemory.appListRefresh
    private var packageChangesRegistered = false
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A replacement also broadcasts removal and addition. Reload after it finishes.
            if ((intent.action == Intent.ACTION_PACKAGE_REMOVED || intent.action == Intent.ACTION_PACKAGE_ADDED)
                && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) return

            appListRefresh.invalidate(intent.data?.schemeSpecificPart)
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
        ModuleService.bindXposedService(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModuleService.connection.collect { service ->
                    uiState.value = uiState.value.copy(xposedConnected = service != null)
                    loadConfigFromAvailableSource(applicationContext)
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
        syncMissedPackageChanges()
        loadInstalledApps()
    }

    private fun syncMissedPackageChanges() {
        if (ConfigMemory.lastLocale != null && ConfigMemory.lastLocale != Locale.getDefault()) {
            appListRefresh.invalidate()
            return
        }
        val lastSeq = ConfigMemory.lastChangedSequence
        if (lastSeq < 0) return
        if (!packageChangesRegistered) {
            appListRefresh.invalidate()
            return
        }
        runCatching {
            val delta = packageManager.getChangedPackages(lastSeq)
            if (delta != null) {
                ConfigMemory.lastChangedSequence = delta.sequenceNumber
                delta.packageNames.forEach(appListRefresh::invalidate)
            }
        }.onFailure { appListRefresh.invalidate() }
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
                    appContext.sendBroadcast(
                        Intent(ACTION_UPDATE_CONFIG).addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not initialize default configuration", error)
                ConfigMemory.mainHandler.post {
                    Toast.makeText(appContext, "默认设置保存失败，请重新打开页面重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateConfig(config: FcmfixConfig) {
        val previous = uiState.value.config
        if (config == previous) return
        if (config.includeIceBoxDisabledApps && !previous.includeIceBoxDisabledApps) requestIceBoxPermissionIfNeeded()
        editGeneration++
        val generation = editGeneration
        latestRevision = maxOf(latestRevision + 1, System.currentTimeMillis())
        val revision = latestRevision
        uiState.value = uiState.value.copy(config = config)
        val appContext = applicationContext
        val local = localStore(appContext)
        ConfigSaveQueue.submit {
            val result = runCatching {
                ConfigPersistence.save(
                    local, RemoteConfigStore(ModuleService.connection.value), previous.toJson(), config.toJson(),
                    revision, System.currentTimeMillis(), ModuleService.remoteOnlyEdit?.let(ConfigCodec::toJson)
                )
            }.onFailure { Log.e(TAG, "Could not prepare configuration save", it) }.getOrNull()
            result?.localFailure?.let { Log.w(TAG, "Local configuration save failed", it) }
            if (result == null || !result.localSaved && !result.remoteSaved) {
                ConfigMemory.mainHandler.post {
                    Toast.makeText(appContext, "设置保存失败，请重试", Toast.LENGTH_SHORT).show()
                    if (generation == ConfigMemory.editGeneration) {
                        ConfigMemory.state.value = ConfigMemory.state.value.copy(config = previous)
                        loadConfigFromAvailableSource(appContext)
                    }
                }
            } else {
                val saved = result.config
                val snapshot = ConfigCodec.fromJson(saved)
                ModuleService.remoteOnlyEdit = if (result.localSaved) null else snapshot
                val persistedConfig = configFromSnapshot(snapshot)
                val persistedRevision = saved.getLong("revision")
                ConfigMemory.mainHandler.post {
                    ConfigMemory.latestRevision = maxOf(ConfigMemory.latestRevision, persistedRevision)
                    if (generation == ConfigMemory.editGeneration) {
                        ConfigMemory.state.value = ConfigMemory.state.value.copy(config = persistedConfig)
                    }
                }
                appContext.sendBroadcast(
                    Intent(ACTION_UPDATE_CONFIG).addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                )
                ConfigMemory.lastReloadRevision = persistedRevision
            }
        }
    }

    companion object {
        private fun localStore(appContext: Context) = object : ConfigPersistence.Store {
            override fun read(): JSONObject = ConfigFile.read(appContext)
            override fun write(json: JSONObject) { ConfigFile.write(appContext, json) }
        }

        private fun configFromSnapshot(snapshot: ConfigSnapshot) = FcmfixConfig(
            allowedPackages = snapshot.allowList.toSet(),
            disableAutoCleanNotification = snapshot.options.getValue(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION),
            includeIceBoxDisabledApps = snapshot.options.getValue(KEY_INCLUDE_ICEBOX_DISABLED_APP),
            adaptiveWifiHeartbeat = snapshot.options.getValue(KEY_ADAPTIVE_WIFI_HEARTBEAT),
            autoAllowNewApps = snapshot.options.getValue(KEY_AUTO_ALLOW_NEW_APPS),
            installEvents = snapshot.installEvents.toSet()
        )

        private fun loadConfigFromAvailableSource(appContext: Context) {
            if (!ConfigMemory.configRefreshQueued.compareAndSet(false, true)) return
            val generation = ConfigMemory.editGeneration
            val local = localStore(appContext)
            ConfigSaveQueue.submit {
                // Coalesce waiting reads, but allow a new refresh once this read starts.
                ConfigMemory.configRefreshQueued.set(false)
                try {
                    // Read when this queued task actually executes, after earlier edits finish.
                    val service = ModuleService.connection.value
                    val remote = RemoteConfigStore(service)
                    val memory = ModuleService.remoteOnlyEdit?.let(ConfigCodec::toJson)
                    val saved = ConfigPersistence.synchronize(local, remote, memory, System.currentTimeMillis())
                    if (saved != null) {
                        val snapshot = ConfigCodec.fromJson(saved)
                        val config = configFromSnapshot(snapshot)
                        if (snapshot.revision != ConfigMemory.lastReloadRevision
                            || service !== ConfigMemory.lastReloadService || remote.changed
                        ) {
                            appContext.sendBroadcast(
                                Intent(ACTION_UPDATE_CONFIG).addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                            )
                            ConfigMemory.lastReloadRevision = snapshot.revision
                            ConfigMemory.lastReloadService = service
                        }
                        ConfigMemory.mainHandler.post {
                            if (generation == ConfigMemory.editGeneration) {
                                ConfigMemory.latestRevision = maxOf(ConfigMemory.latestRevision, snapshot.revision)
                                ConfigMemory.state.value = ConfigMemory.state.value.copy(config = config)
                            }
                        }
                    }
                } catch (error: Throwable) { Log.w(TAG, "Config synchronization unavailable", error) }
            }
        }
    }

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

    private fun loadInstalledApps(forceRefresh: Boolean = false) {
        if (forceRefresh || uiState.value.appsLoadFailed) appListRefresh.invalidate()
        if (appsLoadingJob?.isActive == true) return
        val first = appListRefresh.take() ?: return

        uiState.value = uiState.value.copy(appsLoading = true, appsLoadFailed = false)
        appsLoadingJob = lifecycleScope.launch {
            var request: AppListRefresh.Request? = first
            while (request != null) {
                val scan = request
                val previous = installedApps.value
                val result = withContext(Dispatchers.IO) {
                    runCatching { queryInstalledApps(scan, previous) }
                }
                request = if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    appListRefresh.take()?.let { if (result.isFailure) AppListRefresh.Request.All else it }
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

    private fun FcmfixConfig.toJson() = JSONObject()
        .put("allowList", JSONArray(allowedPackages.sorted()))
        .put(KEY_INSTALL_EVENTS, JSONArray(installEvents.toList()))
        .put(KEY_AUTO_ALLOW_NEW_APPS, autoAllowNewApps)
        .put(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION, disableAutoCleanNotification)
        .put(KEY_INCLUDE_ICEBOX_DISABLED_APP, includeIceBoxDisabledApps)
        .put(KEY_ADAPTIVE_WIFI_HEARTBEAT, adaptiveWifiHeartbeat)

    @Suppress("DEPRECATION")
    private fun queryInstalledApps(request: AppListRefresh.Request, previous: List<InstalledApp>): List<InstalledApp> {
        val currentLocale = Locale.getDefault()
        val apps = when (request) {
            AppListRefresh.Request.All -> {
                val seq = runCatching { packageManager.getChangedPackages(0)?.sequenceNumber ?: 0 }.getOrDefault(-1)
                val scanned = FcmApps.queryInstalledFcmApps(packageManager).values.mapNotNull(::readApp)
                ConfigMemory.lastChangedSequence = seq
                ConfigMemory.lastLocale = currentLocale
                scanned
            }
            is AppListRefresh.Request.Changed -> {
                val updated = previous.filterNot { it.packageName in request.packages }.toMutableList()
                for (name in request.packages) {
                    FcmApps.findInstalledFcmApp(packageManager, name)?.let(::readApp)?.let(updated::add)
                }
                runCatching {
                    val lastSeq = ConfigMemory.lastChangedSequence
                    if (lastSeq >= 0) {
                        packageManager.getChangedPackages(lastSeq)?.let { ConfigMemory.lastChangedSequence = it.sequenceNumber }
                    }
                }
                updated
            }
        }
        val collator = Collator.getInstance(currentLocale)
        return apps.sortedWith(compareBy(collator, InstalledApp::label).thenBy(InstalledApp::packageName))
    }

    private fun readApp(app: ApplicationInfo): InstalledApp? {
        if (!FcmApps.isEligiblePackage(app.packageName, app.flags)) return null
        return try {
            InstalledApp(
                app.packageName, app.loadLabel(packageManager).toString(),
                drawableToBitmap(app.loadIcon(packageManager)).asImageBitmap()
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "Skipping ${app.packageName}", error)
            null
        }
    }

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
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
