package io.github.zopulus.ffc.ui

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import io.github.zopulus.ffc.R
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import io.github.zopulus.ffc.FcmfixConfig
import io.github.zopulus.ffc.FcmfixUiState
import io.github.zopulus.ffc.InstalledApp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FcmfixApp(
    initialTab: MainTab,
    style: InterfaceStyle,
    onTabChange: (MainTab) -> Unit,
    onStyleChange: (InterfaceStyle) -> Unit,
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    versionName: String,
    launcherIconHidden: Boolean,
    onConfigChange: (FcmfixConfig) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProject: () -> Unit,
    onReloadApps: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialTab.ordinal, pageCount = { MainTab.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = MainTab.entries[pagerState.currentPage]
    LaunchedEffect(pagerState.settledPage) { onTabChange(MainTab.entries[pagerState.settledPage]) }
    var appFilter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    val appListSession = remember(pagerState.settledPage) { AppListSession() }
    val onAppConfigChange: (FcmfixConfig) -> Unit = {
        appListSession.onUserEdit()
        onConfigChange(it)
    }
    val haptics = LocalHapticFeedback.current

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal
        ),
        bottomBar = {
            ShortNavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            ) {
                MainTab.entries.forEach { tab ->
                    val selectedIcon = when (tab) {
                        MainTab.HOME -> Icons.Filled.Home
                        MainTab.APPS -> Icons.AutoMirrored.Filled.List
                        MainTab.SETTINGS -> Icons.Filled.Settings
                    }
                    val unselectedIcon = when (tab) {
                        MainTab.HOME -> Icons.Outlined.Home
                        MainTab.APPS -> Icons.AutoMirrored.Outlined.List
                        MainTab.SETTINGS -> Icons.Outlined.Settings
                    }
                    ShortNavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            if (selectedTab != tab) {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                coroutineScope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                            }
                        },
                        icon = {
                            Icon(
                                if (selectedTab == tab) selectedIcon else unselectedIcon,
                                contentDescription = stringResource(tab.title)
                            )
                        },
                        label = { Text(stringResource(tab.title)) }
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
        ) { page ->
            val pageScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
            Column(Modifier.fillMaxSize().nestedScroll(pageScrollBehavior.nestedScrollConnection)) {
                TwoRowsTopAppBar(
                    expandedHeight = TopAppBarDefaults.LargeFlexibleAppBarWithoutSubtitleExpandedHeight,
                    title = { expanded ->
                        val titleStyle = if (expanded) MaterialTheme.typography.displaySmall
                            else MaterialTheme.typography.titleLarge
                        if (MainTab.entries[page] == MainTab.HOME && expanded) {
                            Row(
                                modifier = Modifier.padding(start = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Fcmfix", modifier = Modifier.alignByBaseline(), style = titleStyle)
                                Text(
                                    stringResource(R.string.home_subtitle),
                                    modifier = Modifier.alignByBaseline(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        } else {
                            Text(
                                if (MainTab.entries[page] == MainTab.HOME) "Fcmfix"
                                else stringResource(MainTab.entries[page].title),
                                modifier = Modifier.padding(start = 4.dp),
                                style = titleStyle
                            )
                        }
                    },
                    subtitle = if (MainTab.entries[page] == MainTab.HOME) {
                        { expanded ->
                            if (!expanded) {
                                Text(
                                    stringResource(R.string.home_subtitle),
                                    modifier = Modifier.padding(start = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else null,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    scrollBehavior = pageScrollBehavior,
                    actions = {
                        if (MainTab.entries[page] == MainTab.APPS) {
                            AppListActions(
                                state = state,
                                apps = apps,
                                selectedFilter = appFilter,
                                onFilterChange = { appFilter = it },
                                onConfigChange = onAppConfigChange
                            )
                        }
                    }
                )
                val pageModifier = Modifier.weight(1f).fillMaxWidth()
                when (MainTab.entries[page]) {
                MainTab.HOME -> HomeScreen(
                    modifier = pageModifier,
                    state = state,
                    apps = apps,
                    versionName = versionName,
                    onOpenApps = { coroutineScope.launch { pagerState.animateScrollToPage(MainTab.APPS.ordinal) } },
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenProject = onOpenProject
                )
                MainTab.APPS -> AppsScreen(
                    modifier = pageModifier,
                    session = appListSession,
                    selectedFilter = appFilter,
                    state = state,
                    apps = apps,
                    onConfigChange = onAppConfigChange,
                    onReloadApps = onReloadApps
                )
                MainTab.SETTINGS -> SettingsScreen(
                    modifier = pageModifier,
                    style = style,
                    onStyleChange = onStyleChange,
                    state = state,
                    launcherIconHidden = launcherIconHidden,
                    onConfigChange = onConfigChange,
                    onLauncherIconHiddenChange = onLauncherIconHiddenChange
                )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    versionName: String,
    onOpenApps: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProject: () -> Unit
) {
    val allowedFcmCount = apps.count { it.packageName in state.config.allowedPackages }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HomeStatusCard(connected = state.xposedConnected, versionName = versionName)
        SystemInfoCard()
        SegmentedInfoList(
            items = listOf(
                HomeInfoItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_notifications),
                    title = stringResource(R.string.home_fcm),
                    summary = when {
                        state.appsLoading && apps.isEmpty() -> stringResource(R.string.apps_loading)
                        state.appsLoadFailed && apps.isEmpty() -> stringResource(R.string.apps_load_failed)
                        else -> stringResource(R.string.apps_count, allowedFcmCount, apps.size)
                    },
                    onClick = onOpenApps
                )
            )
        )
        SegmentedInfoList(
            items = listOf(
                HomeInfoItem(
                    icon = ImageVector.vectorResource(R.drawable.ic_troubleshoot),
                    title = stringResource(R.string.home_diagnostics),
                    onClick = onOpenDiagnostics
                ),
                HomeInfoItem(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.home_project),
                    onClick = onOpenProject
                )
            )
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HomeStatusCard(connected: Boolean, versionName: String) {
    val containerColor = if (connected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (connected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = if (connected) Icons.Rounded.CheckCircle else Icons.Outlined.Warning,
                    contentDescription = null
                )
            },
            trailingContent = {
                Surface(
                    modifier = Modifier.padding(end = 4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        stringResource(R.string.home_module_api),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmallEmphasized
                    )
                }
            },
            supportingContent = {
                Text(stringResource(R.string.home_version, versionName), style = MaterialTheme.typography.bodyMedium)
            },
            verticalAlignment = Alignment.CenterVertically,
            colors = ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = contentColor,
                leadingContentColor = contentColor,
                trailingContentColor = contentColor,
                supportingContentColor = contentColor.copy(alpha = 0.7f)
            ),
            elevation = ListItemDefaults.elevation(),
            content = {
                Text(
                    stringResource(if (connected) R.string.home_active else R.string.home_inactive),
                    style = MaterialTheme.typography.titleMediumEmphasized
                )
            }
        )
    }
}

@Composable
private fun SystemInfoCard() {
    val unknown = stringResource(R.string.home_unknown)
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    val device = if (model.startsWith(manufacturer, ignoreCase = true)) model
        else listOf(manufacturer, model).filter(String::isNotBlank).joinToString(" ")
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: unknown
    val pageSize = remember { runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull() }
    val architecture = if (pageSize != null && pageSize > 0) {
        stringResource(R.string.home_abi_page_size, abi, pageSize / 1024)
    } else abi
    val systemInfo = listOf(
        stringResource(R.string.home_system) to stringResource(R.string.home_android_version, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
        stringResource(R.string.home_device) to device.ifBlank { unknown },
        stringResource(R.string.home_architecture) to architecture
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceBright,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            systemInfo.forEach { (title, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private data class HomeInfoItem(
    val icon: ImageVector,
    val title: String,
    val summary: String? = null,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun expressiveSegmentedColors(): ListItemColors = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceBright,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceBright,
    supportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SegmentedInfoList(items: List<HomeInfoItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            key(item.title) {
                val interactionSource = remember { MutableInteractionSource() }
                val haptics = LocalHapticFeedback.current
                SegmentedListItem(
                    verticalAlignment = Alignment.CenterVertically,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        item.onClick()
                    },
                    shapes = ListItemDefaults.segmentedShapes(index, items.size),
                    interactionSource = interactionSource,
                    colors = expressiveSegmentedColors(),
                    leadingContent = {
                        Icon(item.icon, contentDescription = null)
                    },
                    supportingContent = item.summary?.let { summary ->
                        { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    },
                    content = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

@Composable
private fun AppListActions(
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    selectedFilter: AppFilter,
    onFilterChange: (AppFilter) -> Unit,
    onConfigChange: (FcmfixConfig) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val allAllowed = apps.isNotEmpty() && apps.all { it.packageName in state.config.allowedPackages }
    Box {
        IconButton(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            expanded = true
        }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.apps_options))
        }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(index = 0, count = 2),
                modifier = Modifier.width(200.dp)
            ) {
                AppFilter.entries.forEachIndexed { index, filter ->
                    SelectableDropdownMenuItem(
                        selected = selectedFilter == filter,
                        shapes = MenuDefaults.itemShape(index, AppFilter.entries.size),
                        text = { Text(appFilterLabel(filter)) },
                        selectedLeadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onFilterChange(filter)
                            expanded = false
                        }
                    )
                }
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(index = 1, count = 2),
                modifier = Modifier.width(200.dp)
            ) {
                SelectableDropdownMenuItem(
                    shapes = MenuDefaults.itemShape(index = 0, count = 1),
                    selected = allAllowed,
                    text = { Text(stringResource(R.string.apps_allow_all)) },
                    enabled = !state.appsLoading && !state.appsLoadFailed && apps.isNotEmpty() && !allAllowed,
                    selectedLeadingIcon = {
                        Icon(Icons.Filled.Check, contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize))
                    },
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onConfigChange(state.config.allowApps(apps))
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AppsScreen(
    modifier: Modifier,
    session: AppListSession,
    selectedFilter: AppFilter,
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    onConfigChange: (FcmfixConfig) -> Unit,
    onReloadApps: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val allowed = state.config.allowedPackages
    val allowedFcmCount = apps.count { it.packageName in allowed }
    val filtered = visibleApps(apps, session, allowed, selectedFilter)
    val visibleApps = remember(filtered, query) { filtered.searchApps(query) }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.apps_search)) },
                shape = MaterialTheme.shapes.extraLarge
            )

            Text(
                stringResource(
                    if (state.appsLoading) R.string.apps_count_refresh else R.string.apps_count,
                    allowedFcmCount,
                    apps.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

        }

        if (state.appsLoadFailed && apps.isNotEmpty()) {
            TextButton(onClick = onReloadApps) { Text(stringResource(R.string.apps_retry)) }
        }

        when {
            state.appsLoading && apps.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            visibleApps.isEmpty() -> {
                val empty = emptyAppsMessage(
                    loadFailed = state.appsLoadFailed && apps.isEmpty(),
                    hasQuery = query.isNotBlank(),
                    filter = selectedFilter,
                )
                EmptyAppsState(
                    title = stringResource(empty.title),
                    message = stringResource(empty.hint),
                    onReload = onReloadApps,
                    showButton = empty.canReload,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(visibleApps, key = { _, app -> app.packageName }) { index, app ->
                    AppRow(
                        app = app,
                        allowed = app.packageName in allowed,
                        index = index,
                        count = visibleApps.size,
                        onAllowedChange = { enabled ->
                            onConfigChange(state.config.setAppAllowed(app.packageName, enabled))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAppsState(
    title: String,
    message: String,
    onReload: () -> Unit,
    showButton: Boolean = true
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showButton) {
            TextButton(onClick = onReload) { Text(stringResource(R.string.apps_reload)) }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    allowed: Boolean,
    index: Int,
    count: Int,
    onAllowedChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onAllowedChange(!allowed)
        },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        interactionSource = interactionSource,
        colors = expressiveSegmentedColors(),
        leadingContent = {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
            )
        },
        supportingContent = {
            Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Switch(
                checked = allowed,
                onCheckedChange = null,
                interactionSource = interactionSource,
                thumbContent = {
                    Icon(
                        imageVector = if (allowed) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            )
        },
        content = {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    )
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    style: InterfaceStyle,
    state: FcmfixUiState,
    launcherIconHidden: Boolean,
    onStyleChange: (InterfaceStyle) -> Unit,
    onConfigChange: (FcmfixConfig) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit
) {
    var confirmation by rememberSaveable { mutableStateOf<SettingConfirmation?>(null) }
    confirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(request.title)) },
            text = { Text(stringResource(request.message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    request.confirm(onLauncherIconHiddenChange)
                }) { Text(stringResource(request.action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    val groups = settingsGroups(state.config, launcherIconHidden, onConfigChange, onLauncherIconHiddenChange) {
        confirmation = it
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        items(groups) { group ->
            SettingsSection(stringResource(group.title)) {
                val styleOffset = if (group.includeInterfaceStyle) 1 else 0
                val rowCount = group.items.size + styleOffset
                if (group.includeInterfaceStyle) {
                    MaterialStylePreference(style, onStyleChange, index = 0, count = rowCount)
                }
                group.items.forEachIndexed { index, item ->
                    key(item.title) { SettingRow(item, index + styleOffset, rowCount) }
                }
            }
        }
    }
}

// Adapted from the interface mode row in KernelSU's Material settings.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialStylePreference(
    style: InterfaceStyle,
    onStyleChange: (InterfaceStyle) -> Unit,
    index: Int,
    count: Int,
) {
    var expanded by remember { mutableStateOf(false) }
    var pressPosition by remember { mutableStateOf<IntOffset?>(null) }
    var menuAnchor by remember { mutableStateOf<IntOffset?>(null) }
    val haptics = LocalHapticFeedback.current
    Box(Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            pressPosition = down.position.round()
            waitForUpOrCancellation()
            pressPosition = null
        }
    }) {
        SegmentedListItem(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                menuAnchor = pressPosition
                expanded = true
            },
            verticalAlignment = Alignment.CenterVertically,
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = expressiveSegmentedColors(),
            leadingContent = { Icon(ImageVector.vectorResource(R.drawable.ic_display_settings), null) },
            supportingContent = { Text(stringResource(R.string.settings_style_hint)) },
            trailingContent = {
                Text(
                    stringResource(style.title),
                    modifier = Modifier.fillMaxWidth(0.3f),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End
                )
            }
        ) { Text(stringResource(R.string.settings_style)) }
        // Popup anchors to its immediate layout parent. Keep that parent at the press point.
        val anchor = menuAnchor
        val anchorModifier = if (anchor == null) Modifier.align(Alignment.BottomEnd)
        else Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { anchor }
        Box(anchorModifier) {
            DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(index = 0, count = 1)) {
                    InterfaceStyle.entries.forEachIndexed { index, option ->
                        SelectableDropdownMenuItem(
                            selected = option == style,
                            shapes = MenuDefaults.itemShape(index, InterfaceStyle.entries.size),
                            text = { Text(stringResource(option.title)) },
                            selectedLeadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                expanded = false
                                if (option != style) onStyleChange(option)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingRow(item: SettingEntry, index: Int, count: Int, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
        onClick = {
            if (item.enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                item.onCheckedChange(!item.checked)
            }
        },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        interactionSource = interactionSource,
        colors = expressiveSegmentedColors(),
        leadingContent = {
            Icon(
                ImageVector.vectorResource(item.icon),
                contentDescription = null,
                tint = if (item.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        },
        supportingContent = { Text(stringResource(item.summary), color = if (item.enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
        trailingContent = {
            Switch(
                checked = item.checked,
                enabled = item.enabled,
                onCheckedChange = null,
                interactionSource = interactionSource,
                thumbContent = {
                    Icon(
                        imageVector = if (item.checked) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            )
        },
        content = { Text(stringResource(item.title), color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) }
    )
}
