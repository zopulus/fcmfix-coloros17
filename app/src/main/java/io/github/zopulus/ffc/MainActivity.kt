package io.github.zopulus.ffc

import io.github.zopulus.ffc.util.ConfigSchema.*
import io.github.zopulus.ffc.util.ConfigSnapshot
import io.github.zopulus.ffc.util.ConfigCodec
import io.github.zopulus.ffc.util.ConfigFile
import io.github.zopulus.ffc.util.ConfigSaveQueue
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.zopulus.ffc.ui.FcmfixApp
import io.github.zopulus.ffc.ui.FcmfixTheme
import io.github.zopulus.ffc.util.IceboxUtils
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.Collator
import java.util.Locale

private const val TAG = "fcmfix-ui"
private const val ACTION_UPDATE_CONFIG = "io.github.zopulus.ffc.update.config"

data class FcmfixConfig(
    val allowedPackages: Set<String> = emptySet(),
    val disableAutoCleanNotification: Boolean = false,
    val includeIceBoxDisabledApps: Boolean = false,
    val deepSleepGoogleWhitelist: Boolean = defaultValue(KEY_DEEP_SLEEP_GOOGLE_WHITELIST),
    val dozeGoogleWhitelist: Boolean = defaultValue(KEY_DOZE_GOOGLE_WHITELIST),
    val rootDeepSleepNetworkWhitelist: Boolean = false
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap,
    val hasFcmReceiver: Boolean
)

enum class ConfigSource {
    REMOTE,
    LOCAL,
    DEFAULT
}

data class FcmfixUiState(
    val config: FcmfixConfig = FcmfixConfig(),
    val configSource: ConfigSource = ConfigSource.DEFAULT,
    val xposedConnected: Boolean = false,
    val appsLoading: Boolean = true,
    val appsLoadFailed: Boolean = false
)

/** Optimistic configuration survives Activity recreation while the save queue drains. */
private object ConfigMemory {
    var latestRevision = 0L
    var editGeneration = 0L
    var initialized = false
    @Volatile var xposedService: XposedService? = null
    val state = androidx.compose.runtime.mutableStateOf(FcmfixUiState())
}

class MainActivity : ComponentActivity() {
    private var xposedService: XposedService?
        get() = ConfigMemory.xposedService
        set(value) { ConfigMemory.xposedService = value }
    private var latestRevision: Long
        get() = ConfigMemory.latestRevision
        set(value) { ConfigMemory.latestRevision = value }
    private var editGeneration: Long
        get() = ConfigMemory.editGeneration
        set(value) { ConfigMemory.editGeneration = value }
    private val uiState = ConfigMemory.state
    private var installedApps = androidx.compose.runtime.mutableStateOf<List<InstalledApp>>(emptyList())
    private var launcherIconHidden = androidx.compose.runtime.mutableStateOf(false)
    private val launcherAliasComponent by lazy {
        ComponentName(packageName, "$packageName.LauncherAlias")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherIconHidden.value = packageManager.getComponentEnabledSetting(launcherAliasComponent) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val darkMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ) { darkMode },
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ) { darkMode }
        )
        window.isNavigationBarContrastEnforced = false
        if (!ConfigMemory.initialized) {
            loadConfigFromLocalFile()
            ConfigMemory.initialized = true
        }
        initializeDefaultConfigIfNeeded()
        if (uiState.value.config.includeIceBoxDisabledApps) requestIceBoxPermissionIfNeeded()
        bindXposedService()
        loadInstalledApps()

        setContent {
            FcmfixTheme {
                FcmfixApp(
                    state = uiState.value,
                    apps = installedApps.value,
                    versionName = BuildConfig.VERSION_NAME,
                    launcherIconHidden = launcherIconHidden.value,
                    onConfigChange = ::updateConfig,
                    onLauncherIconHiddenChange = ::setLauncherIconHidden,
                    onOpenDiagnostics = ::openGmsDiagnostics,
                    onOpenProject = ::openProjectPage,
                    onReloadApps = ::loadInstalledApps
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        launcherIconHidden.value = packageManager.getComponentEnabledSetting(launcherAliasComponent) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        uiState.value = uiState.value.copy(xposedConnected = xposedService != null)
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

    private fun bindXposedService() {
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    xposedService = service
                    runOnUiThread {
                        uiState.value = uiState.value.copy(xposedConnected = xposedService != null)
                        loadConfigFromAvailableSource()
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    if (xposedService === service) {
                        xposedService = null
                        runOnUiThread {
                            uiState.value = uiState.value.copy(xposedConnected = xposedService != null)
                        }
                    }
                }
            })
        } catch (error: Throwable) {
            Log.e(TAG, "Could not connect to the Xposed service", error)
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
                    runOnUiThread {
                        if (latestRevision == 0L && uiState.value.configSource == ConfigSource.DEFAULT) {
                            uiState.value = uiState.value.copy(configSource = ConfigSource.LOCAL)
                        }
                    }
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
        val generation = editGeneration
        ConfigSaveQueue.submit {
            try {
                // Read when this queued task actually executes, after earlier edits finish.
                val persisted = runCatching { ConfigFile.read(applicationContext) }.getOrNull()
                val localRevision = persisted?.optLong("revision", 0) ?: -1L
                val preferences = xposedService?.getRemotePreferences("config")
                if (preferences != null && preferences.getBoolean("init", false)
                    && (preferences.getLong("revision", 0) > localRevision
                        // Older releases did not write a revision. Preserve those
                        // remote settings over a freshly bootstrapped default file.
                        || persisted?.optBoolean("bootstrapDefaults", false) == true
                            && preferences.getLong("revision", 0) == localRevision)) {
                    val remote = configFromPreferences(preferences).normalized()
                    val revision = preferences.getLong("revision", 0)
                    ConfigFile.write(applicationContext, remote.toJson().put("revision", revision))
                    runOnUiThread {
                        if (generation == editGeneration) {
                            latestRevision = maxOf(latestRevision, revision)
                            uiState.value = uiState.value.copy(config = remote, configSource = ConfigSource.LOCAL)
                        }
                    }
                } else if (persisted != null) {
                    saveRemote(configFromJson(persisted).normalized(), localRevision)
                }
                applicationContext.sendBroadcast(Intent(ACTION_UPDATE_CONFIG))
            } catch (error: Throwable) { Log.w(TAG, "Config synchronization unavailable", error) }
        }
    }

    private fun configFromSnapshot(snapshot: ConfigSnapshot) = FcmfixConfig(
        allowedPackages = snapshot.allowList.toSet(),
        disableAutoCleanNotification = snapshot.options.getValue(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION),
        includeIceBoxDisabledApps = snapshot.options.getValue(KEY_INCLUDE_ICEBOX_DISABLED_APP),
        deepSleepGoogleWhitelist = snapshot.options.getValue(KEY_DEEP_SLEEP_GOOGLE_WHITELIST),
        dozeGoogleWhitelist = snapshot.options.getValue(KEY_DOZE_GOOGLE_WHITELIST),
        rootDeepSleepNetworkWhitelist = snapshot.options.getValue(KEY_ROOT_DEEP_SLEEP_NETWORK_WHITELIST)
    )

    private fun configFromJson(json: JSONObject) = configFromSnapshot(ConfigCodec.fromJson(json))

    private fun configFromPreferences(preferences: SharedPreferences) =
        configFromSnapshot(ConfigSnapshot(preferences.all))

    private fun loadConfigFromLocalFile() {
        try {
            val json = ConfigFile.read(this)
            val config = configFromJson(json)
            latestRevision = json.optLong("revision", 0)
            uiState.value = uiState.value.copy(config = config, configSource = ConfigSource.LOCAL)
        } catch (error: Throwable) {
            uiState.value = uiState.value.copy(configSource = ConfigSource.DEFAULT)
            Log.i(TAG, "Using default configuration", error)
        }
    }

    private fun FcmfixConfig.normalized() = copy(
        rootDeepSleepNetworkWhitelist = deepSleepGoogleWhitelist && rootDeepSleepNetworkWhitelist
    )

    private fun updateConfig(requested: FcmfixConfig) {
        val config = requested.normalized()
        if (config.includeIceBoxDisabledApps && !uiState.value.config.includeIceBoxDisabledApps) requestIceBoxPermissionIfNeeded()
        editGeneration++
        latestRevision = maxOf(latestRevision + 1, System.currentTimeMillis())
        val revision = latestRevision
        uiState.value = uiState.value.copy(config = config)
        ConfigSaveQueue.submit {
            val localSaved = runCatching {
                ConfigFile.write(applicationContext, config.toJson().put("revision", revision))
            }.onFailure { Log.e(TAG, "Could not save local configuration", it) }.isSuccess
            val remoteSaved = saveRemote(config, revision)
            runOnUiThread {
                if (!localSaved && !remoteSaved) {
                    Toast.makeText(this, "设置保存失败，请重试", Toast.LENGTH_SHORT).show()
                    if (revision == latestRevision) loadConfigFromLocalFile()
                } else if (revision == latestRevision) {
                    uiState.value = uiState.value.copy(configSource = if (localSaved) ConfigSource.LOCAL else ConfigSource.REMOTE)
                }
            }
            if (localSaved || remoteSaved) {
                applicationContext.sendBroadcast(Intent(ACTION_UPDATE_CONFIG))
            }
        }
    }

    private fun saveRemote(config: FcmfixConfig, revision: Long): Boolean = try {
        xposedService?.getRemotePreferences("config")?.edit()
            ?.remove("disableGoogleNetworkControl")
            ?.putBoolean("init", true)
            ?.putLong("revision", revision)
            ?.putStringSet("allowList", config.allowedPackages.toMutableSet())
            ?.putBoolean(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION, config.disableAutoCleanNotification)
            ?.putBoolean(KEY_INCLUDE_ICEBOX_DISABLED_APP, config.includeIceBoxDisabledApps)
            ?.putBoolean(KEY_DEEP_SLEEP_GOOGLE_WHITELIST, config.deepSleepGoogleWhitelist)
            ?.putBoolean(KEY_DOZE_GOOGLE_WHITELIST, config.dozeGoogleWhitelist)
            ?.putBoolean(KEY_ROOT_DEEP_SLEEP_NETWORK_WHITELIST, config.rootDeepSleepNetworkWhitelist)
            ?.commit() ?: false
    } catch (error: Throwable) {
        Log.w(TAG, "Remote save unavailable; local configuration is retained", error)
        false
    }

    private fun FcmfixConfig.toJson() = JSONObject()
        .put("allowList", JSONArray(allowedPackages.sorted()))
        .put(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION, disableAutoCleanNotification)
        .put(KEY_INCLUDE_ICEBOX_DISABLED_APP, includeIceBoxDisabledApps)
        .put(KEY_DEEP_SLEEP_GOOGLE_WHITELIST, deepSleepGoogleWhitelist)
        .put(KEY_DOZE_GOOGLE_WHITELIST, dozeGoogleWhitelist)
        .put(KEY_ROOT_DEEP_SLEEP_NETWORK_WHITELIST, rootDeepSleepNetworkWhitelist)

    private fun loadInstalledApps() {
        uiState.value = uiState.value.copy(appsLoading = true, appsLoadFailed = false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { queryInstalledApps() }
            }
            result.onSuccess { apps ->
                installedApps.value = apps
                uiState.value = uiState.value.copy(appsLoading = false, appsLoadFailed = false)
            }.onFailure { error ->
                installedApps.value = emptyList()
                uiState.value = uiState.value.copy(appsLoading = false, appsLoadFailed = true)
                Log.e(TAG, "Could not read installed applications", error)
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
                InstalledApp(
                    packageName = packageInfo.packageName,
                    label = applicationInfo.loadLabel(packageManager).toString(),
                    icon = drawableToBitmap(applicationInfo.loadIcon(packageManager)),
                    hasFcmReceiver = true
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Skipping ${packageInfo.packageName}", error)
                null
            }
        }.sortedWith { first, second -> collator.compare(first.label, second.label) }
    }

    private fun isFcmReceiver(receiver: ActivityInfo): Boolean =
        receiver.name == "com.google.firebase.iid.FirebaseInstanceIdReceiver" ||
            receiver.name == "com.google.android.gms.measurement.AppMeasurementReceiver"

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
