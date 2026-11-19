package io.github.zopulus.ffc.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.zopulus.ffc.FcmfixConfig
import io.github.zopulus.ffc.InstalledApp
import io.github.zopulus.ffc.R
import androidx.compose.ui.res.stringResource

internal enum class InterfaceStyle(val preferenceValue: String, val title: Int) {
    COUI("coui", R.string.settings_style_coui),
    MD3("md3", R.string.settings_style_md3);

    companion object {
        fun fromPreference(value: String?): InterfaceStyle =
            entries.firstOrNull { it.preferenceValue == value } ?: COUI
    }
}

internal enum class MainTab(val title: Int) {
    HOME(R.string.nav_home),
    APPS(R.string.nav_apps),
    SETTINGS(R.string.nav_settings)
}

internal data class SettingGroup(
    val title: Int,
    val items: List<SettingEntry>,
    val includeInterfaceStyle: Boolean = false,
)

internal data class SettingEntry(
    @DrawableRes val icon: Int,
    val title: Int,
    val summary: Int,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    val enabled: Boolean = true
)

internal enum class SettingConfirmation(
    val title: Int,
    val message: Int,
    val action: Int
) {
    ROOT(R.string.settings_root_confirm, R.string.settings_root_confirm_hint, R.string.action_enable),
    HIDE_ICON(R.string.settings_hide_confirm, R.string.settings_hide_confirm_hint, R.string.action_hide);

    fun confirm(
        config: FcmfixConfig,
        onConfigChange: (FcmfixConfig) -> Unit,
        onLauncherIconHiddenChange: (Boolean) -> Unit
    ) {
        when (this) {
            ROOT -> if (config.deepSleepGoogleWhitelist) {
                onConfigChange(config.copy(rootDeepSleepNetworkWhitelist = true))
            }
            HIDE_ICON -> onLauncherIconHiddenChange(true)
        }
    }
}

internal fun settingsGroups(
    config: FcmfixConfig,
    launcherIconHidden: Boolean,
    onConfigChange: (FcmfixConfig) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    onConfirm: (SettingConfirmation) -> Unit
): List<SettingGroup> = listOf(
    SettingGroup(R.string.settings_network, listOf(
        SettingEntry(
            R.drawable.ic_bedtime, R.string.settings_sleep, R.string.settings_sleep_hint,
            checked = config.deepSleepGoogleWhitelist,
            onCheckedChange = { enabled ->
                onConfigChange(config.copy(
                    deepSleepGoogleWhitelist = enabled,
                    rootDeepSleepNetworkWhitelist = enabled && config.rootDeepSleepNetworkWhitelist
                ))
            }
        ),
        SettingEntry(
            R.drawable.ic_terminal, R.string.settings_root,
            if (config.deepSleepGoogleWhitelist) R.string.settings_root_hint else R.string.settings_root_disabled,
            checked = config.deepSleepGoogleWhitelist && config.rootDeepSleepNetworkWhitelist,
            enabled = config.deepSleepGoogleWhitelist,
            onCheckedChange = { enabled ->
                if (enabled) onConfirm(SettingConfirmation.ROOT)
                else onConfigChange(config.copy(rootDeepSleepNetworkWhitelist = false))
            }
        ),
        SettingEntry(
            R.drawable.ic_hourglass, R.string.settings_doze, R.string.settings_doze_hint,
            checked = config.dozeGoogleWhitelist,
            onCheckedChange = { onConfigChange(config.copy(dozeGoogleWhitelist = it)) }
        )
    )),
    SettingGroup(R.string.settings_notifications, listOf(
        SettingEntry(
            R.drawable.ic_shield, R.string.settings_new_apps, R.string.settings_new_apps_hint,
            checked = config.autoAllowNewApps,
            onCheckedChange = { onConfigChange(config.copy(autoAllowNewApps = it)) }
        ),
        SettingEntry(
            R.drawable.ic_notifications_active, R.string.settings_keep_notifications,
            R.string.settings_keep_notifications_hint,
            checked = config.disableAutoCleanNotification,
            onCheckedChange = { onConfigChange(config.copy(disableAutoCleanNotification = it)) }
        ),
        SettingEntry(
            R.drawable.ic_ac_unit, R.string.settings_frozen, R.string.settings_frozen_hint,
            checked = config.includeIceBoxDisabledApps,
            onCheckedChange = { onConfigChange(config.copy(includeIceBoxDisabledApps = it)) }
        )
    )),
    SettingGroup(
        title = R.string.settings_other,
        items = listOf(
            SettingEntry(
                R.drawable.ic_visibility_off, R.string.settings_hide_launcher, R.string.settings_hide_launcher_hint,
                checked = launcherIconHidden,
                onCheckedChange = { hidden ->
                    if (hidden) onConfirm(SettingConfirmation.HIDE_ICON) else onLauncherIconHiddenChange(false)
                }
            )
        ),
        includeInterfaceStyle = true,
    )
)

@Composable
internal fun visibleApps(
    apps: List<InstalledApp>,
    session: AppListSession,
    allowed: Set<String>,
    selectedFilter: AppFilter
): List<InstalledApp> {
    val (order, filter) = session.snapshots(allowed, selectedFilter)
    return remember(apps, order, filter, selectedFilter) {
        order.grouped(apps, InstalledApp::packageName).filter { filter.includes(it.packageName, selectedFilter) }
    }
}

internal fun List<InstalledApp>.searchApps(query: String): List<InstalledApp> =
    if (query.isBlank()) this else filter {
        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }

internal fun FcmfixConfig.allowApps(apps: List<InstalledApp>): FcmfixConfig =
    copy(allowedPackages = allowedPackages + apps.map(InstalledApp::packageName))

internal fun FcmfixConfig.setAppAllowed(packageName: String, allowed: Boolean): FcmfixConfig =
    copy(allowedPackages = if (allowed) allowedPackages + packageName else allowedPackages - packageName)

internal data class EmptyAppsMessage(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    val canReload: Boolean = false,
)

internal fun emptyAppsMessage(loadFailed: Boolean, hasQuery: Boolean, filter: AppFilter): EmptyAppsMessage =
    when {
        loadFailed -> EmptyAppsMessage(R.string.apps_load_failed, R.string.apps_load_failed_hint, canReload = true)
        hasQuery -> EmptyAppsMessage(R.string.apps_search_empty, R.string.apps_search_hint)
        filter == AppFilter.ALLOWED -> EmptyAppsMessage(R.string.apps_allowed_empty, R.string.apps_allowed_hint)
        filter == AppFilter.DISABLED -> EmptyAppsMessage(R.string.apps_disabled_empty, R.string.apps_disabled_hint)
        else -> EmptyAppsMessage(R.string.apps_all_empty, R.string.apps_all_hint, canReload = true)
    }

@Composable
internal fun appFilterLabel(filter: AppFilter): String = stringResource(when (filter) {
    AppFilter.ALL -> R.string.apps_all
    AppFilter.ALLOWED -> R.string.apps_allowed
    AppFilter.DISABLED -> R.string.apps_disabled
})
