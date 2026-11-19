package io.github.zopulus.ffc.ui

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Home
import io.github.suqi8.coui.kmp.icon.extended.ListView
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.effect.LocalMaterialBackdrop
import io.github.suqi8.coui.kmp.nav.core.NavDisplay
import io.github.suqi8.coui.kmp.nav.core.NavDisplayEffects
import io.github.suqi8.coui.kmp.nav.core.NavKey
import io.github.suqi8.coui.kmp.nav.core.rememberNavBackStack
import io.github.suqi8.coui.kmp.nav.core.rememberNavSystemCornerRadius
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController
import io.github.zopulus.ffc.FcmfixConfig
import io.github.zopulus.ffc.FcmfixUiState
import io.github.zopulus.ffc.InstalledApp
import io.github.zopulus.ffc.R
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Composable
internal fun FcmfixInterface(
    style: InterfaceStyle,
    initialTab: MainTab,
    onStyleChange: (InterfaceStyle) -> Unit,
    onTabChange: (MainTab) -> Unit,
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    versionName: String,
    launcherIconHidden: Boolean,
    onConfigChange: (FcmfixConfig) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProject: () -> Unit,
    onReloadApps: () -> Unit,
) {
    when (style) {
        InterfaceStyle.MD3 -> FcmfixTheme {
            FcmfixApp(
                initialTab = initialTab,
                style = style,
                onTabChange = onTabChange,
                onStyleChange = onStyleChange,
                state = state,
                apps = apps,
                versionName = versionName,
                launcherIconHidden = launcherIconHidden,
                onConfigChange = onConfigChange,
                onLauncherIconHiddenChange = onLauncherIconHiddenChange,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenProject = onOpenProject,
                onReloadApps = onReloadApps,
            )
        }
        InterfaceStyle.COUI -> COUITheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
            CouiFcmfixApp(
                initialTab = initialTab,
                onStyleChange = onStyleChange,
                onTabChange = onTabChange,
                style = style,
                state = state,
                apps = apps,
                versionName = versionName,
                launcherIconHidden = launcherIconHidden,
                onConfigChange = onConfigChange,
                onLauncherIconHiddenChange = onLauncherIconHiddenChange,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenProject = onOpenProject,
                onReloadApps = onReloadApps,
            )
        }
    }
}

@Serializable
private sealed interface CouiRoute : NavKey {
    @Serializable data object Tabs : CouiRoute
    @Serializable data object Apps : CouiRoute
    @Serializable data object Settings : CouiRoute
}

@Composable
private fun CouiFcmfixApp(
    initialTab: MainTab,
    style: InterfaceStyle,
    onStyleChange: (InterfaceStyle) -> Unit,
    onTabChange: (MainTab) -> Unit,
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    versionName: String,
    launcherIconHidden: Boolean,
    onConfigChange: (FcmfixConfig) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProject: () -> Unit,
    onReloadApps: () -> Unit,
) {
    val backStack = rememberNavBackStack<CouiRoute>(CouiRoute.Tabs)
    val goBack: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }
    val openPage: (CouiRoute) -> Unit = { if (backStack.size == 1) backStack.add(it) }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = goBack,
        effects = NavDisplayEffects(
            cornerClipRadius = rememberNavSystemCornerRadius(),
            blockInputDuringTransition = true,
            backdropColor = COUITheme.colorScheme.backgroundWithCard,
        ),
    ) {
        entry<CouiRoute.Tabs> {
            CouiTabs(
                initialTab = initialTab,
                onTabChange = onTabChange,
                home = { padding ->
                    CouiPageScaffold(
                        title = "Fcmfix",
                        subtitle = stringResource(R.string.home_subtitle),
                        scrollBehavior = COUIScrollBehavior(),
                    ) { pagePadding, behavior ->
                        CouiPageList(rememberLazyListState(), pagePadding.withBottomBar(padding), behavior) {
                            couiHomeItems(
                                state, apps, versionName,
                                { openPage(CouiRoute.Apps) }, { openPage(CouiRoute.Settings) },
                                onOpenDiagnostics, onOpenProject,
                            )
                        }
                    }
                },
                apps = { search, visit, padding ->
                    CouiAppsScreen(
                        state, apps, onConfigChange, onReloadApps,
                        search = search, visit = visit, bottomBarPadding = padding,
                    )
                },
                settings = { padding ->
                    CouiSettingsScreen(
                        state.config, launcherIconHidden, style, onStyleChange,
                        onConfigChange, onLauncherIconHiddenChange,
                        bottomBarPadding = padding,
                    )
                },
            )
        }
        entry<CouiRoute.Apps> {
            CouiAppsScreen(state, apps, onConfigChange, onReloadApps, onBack = goBack)
        }
        entry<CouiRoute.Settings> {
            CouiSettingsScreen(
                state.config, launcherIconHidden, style, onStyleChange,
                onConfigChange, onLauncherIconHiddenChange, onBack = goBack,
            )
        }
    }
}

@Composable
private fun CouiTabs(
    initialTab: MainTab,
    onTabChange: (MainTab) -> Unit,
    home: @Composable (PaddingValues) -> Unit,
    apps: @Composable (CouiAppsSearch, Int, PaddingValues) -> Unit,
    settings: @Composable (PaddingValues) -> Unit,
) {
    val pager = rememberPagerState(initialPage = initialTab.ordinal, pageCount = { MainTab.entries.size })
    val scope = rememberCoroutineScope()
    val search = rememberCouiAppsSearch()
    val searchInProgress by remember(search) {
        derivedStateOf { search.expanded || search.motion.progress > 0f || search.motion.isResultsVisible }
    }
    LaunchedEffect(pager.settledPage) { onTabChange(MainTab.entries[pager.settledPage]) }

    val backdrop = rememberPageBackdrop()
    val material = rememberMaterialBackdropProvider(backdrop)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            CompositionLocalProvider(LocalMaterialBackdrop provides material) {
                FloatingNavigationBar(visible = !search.expanded) {
                    MainTab.entries.forEach { tab ->
                        FloatingNavigationBarItem(
                            selected = pager.currentPage == tab.ordinal,
                            enabled = !search.expanded,
                            onClick = {
                                if (!searchInProgress && pager.targetPage != tab.ordinal) {
                                    scope.launch { pager.animateScrollToPage(tab.ordinal) }
                                }
                            },
                            icon = when (tab) {
                                MainTab.HOME -> COUIIcons.Home
                                MainTab.APPS -> COUIIcons.ListView
                                MainTab.SETTINGS -> COUIIcons.Settings
                            },
                            label = stringResource(tab.title),
                        )
                    }
                }
            }
        },
    ) { bottomBarPadding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            userScrollEnabled = !searchInProgress,
        ) { page ->
            when (MainTab.entries[page]) {
                MainTab.HOME -> home(bottomBarPadding)
                MainTab.APPS -> apps(search, pager.settledPage, bottomBarPadding)
                MainTab.SETTINGS -> settings(bottomBarPadding)
            }
        }
    }
}

private fun LazyListScope.couiHomeItems(
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    versionName: String,
    openApps: () -> Unit,
    openSettings: () -> Unit,
    openDiagnostics: () -> Unit,
    openProject: () -> Unit,
) {
    item(key = "status") {
        val colors = COUITheme.colorScheme
        Card(Modifier.couiCardSpacing()) {
            BasicComponent(
                title = stringResource(if (state.xposedConnected) R.string.home_active else R.string.home_inactive),
                summary = stringResource(R.string.home_version, versionName),
                startAction = {
                    if (state.xposedConnected) {
                        CouiRowIcon(R.drawable.ic_status_complete, tint = CompleteStateDefaults.SuccessColor)
                    } else {
                        CouiRowIcon(R.drawable.ic_status_highlight_off, tint = colors.error)
                    }
                },
                endActions = {
                    TagView(
                        text = stringResource(R.string.home_module_api),
                        colors = TagViewDefaults.tagViewColors(
                            containerColor = colors.fillSolid,
                            contentColor = colors.labelPrimary,
                        ),
                    )
                },
            )
        }
    }
    item(key = "system") {
        val unknown = stringResource(R.string.home_unknown)
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val device = if (model.startsWith(manufacturer, ignoreCase = true)) model
            else listOf(manufacturer, model).filter(String::isNotBlank).joinToString(" ")
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: unknown
        val pageSize = remember { runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull() }
        Card(Modifier.couiCardSpacing()) {
            CardListGroup {
                item {
                    BasicComponent(title = stringResource(R.string.home_system), summary =
                        stringResource(R.string.home_android_version, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
                }
                item { BasicComponent(title = stringResource(R.string.home_device), summary = device.ifBlank { unknown }) }
                item {
                    BasicComponent(title = stringResource(R.string.home_architecture), summary =
                        if (pageSize != null && pageSize > 0) stringResource(R.string.home_abi_page_size, abi, pageSize / 1024)
                        else abi)
                }
            }
        }
    }
    item(key = "overview") {
        val allowed = apps.count { it.packageName in state.config.allowedPackages }
        val protectionCount = listOf(state.config.deepSleepGoogleWhitelist, state.config.dozeGoogleWhitelist).count { it }
        Card(Modifier.couiCardSpacing()) {
            CardListGroup {
                item {
                    ArrowPreference(
                        title = stringResource(R.string.home_fcm),
                        summary = when {
                            apps.isEmpty() && state.appsLoading -> stringResource(R.string.apps_loading)
                            apps.isEmpty() && state.appsLoadFailed -> stringResource(R.string.apps_load_failed)
                            else -> stringResource(R.string.apps_count, allowed, apps.size)
                        },
                        startAction = { CouiRowIcon(R.drawable.ic_notifications) },
                        onClick = openApps,
                    )
                }
                item {
                    ArrowPreference(
                        title = stringResource(R.string.home_google),
                        summary = stringResource(R.string.home_google_count, protectionCount),
                        startAction = { CouiRowIcon(R.drawable.ic_shield) },
                        onClick = openSettings,
                    )
                }
            }
        }
    }
    item(key = "links") {
        Card(Modifier.couiCardSpacing()) {
            CardListGroup {
                item {
                    ArrowPreference(title = stringResource(R.string.home_diagnostics),
                        startAction = { CouiRowIcon(R.drawable.ic_troubleshoot) }, onClick = openDiagnostics)
                }
                item {
                    ArrowPreference(title = stringResource(R.string.home_project),
                        startAction = { CouiRowIcon(COUIIcons.Info) }, onClick = openProject)
                }
            }
        }
    }
}
