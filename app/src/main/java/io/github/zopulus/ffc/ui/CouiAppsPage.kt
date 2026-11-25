package io.github.zopulus.ffc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ListView
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.menu.OverlayIconDropdownMenu
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.squircle.squircleSurface
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.zopulus.ffc.FcmfixConfig
import io.github.zopulus.ffc.FcmfixUiState
import io.github.zopulus.ffc.InstalledApp
import io.github.zopulus.ffc.R
import kotlinx.coroutines.flow.first

@Stable
internal class CouiAppsSearch(
    private val expandedState: MutableState<Boolean>,
    private val queryState: MutableState<String>,
    val motion: TopAppBarSearchState,
) {
    var expanded by expandedState
    var query by queryState
}

@Composable
internal fun rememberCouiAppsSearch(): CouiAppsSearch {
    val expanded = rememberSaveable { mutableStateOf(false) }
    val query = rememberSaveable { mutableStateOf("") }
    val motion = rememberTopAppBarSearchState(expanded.value)
    LaunchedEffect(expanded.value, motion) {
        if (!expanded.value) {
            // The result list keeps rendering while it moves out.
            snapshotFlow { motion.isResultsVisible }.first { !it }
            query.value = ""
        }
    }
    return remember(expanded, query, motion) { CouiAppsSearch(expanded, query, motion) }
}

@Composable
internal fun CouiAppsScreen(
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    onConfigChange: (FcmfixConfig) -> Unit,
    onReload: () -> Unit,
    onBack: (() -> Unit)? = null,
    search: CouiAppsSearch = rememberCouiAppsSearch(),
    visit: Int = 0,
    bottomBarPadding: PaddingValues = PaddingValues(0.dp),
) {
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    val listState = rememberLazyListState()
    val session = remember(visit) { AppListSession() }
    val editApps: (FcmfixConfig) -> Unit = {
        session.onUserEdit()
        onConfigChange(it)
    }
    CouiPageScaffold(
        title = stringResource(MainTab.APPS.title),
        scrollBehavior = COUIScrollBehavior(),
        searchState = search.motion,
        searchBar = {
            TopAppBarSearchBar(
                query = search.query,
                onQueryChange = { search.query = it },
                expanded = search.expanded,
                onExpandedChange = { search.expanded = it },
                cancelText = stringResource(R.string.action_cancel),
                label = stringResource(R.string.apps_search),
            )
        },
        actions = {
            if (!search.expanded) {
                CouiAppListActions(state, apps, filter, { filter = it }, editApps)
            }
        },
        onBack = onBack?.let { goBack ->
            { if (search.expanded) search.expanded = false else goBack() }
        },
    ) { padding, behavior ->
        CouiAppsPage(
            state, apps, session, filter, search.query, search.motion, listState, padding,
            behavior, editApps, onReload, bottomBarPadding,
        )
    }
}

@Composable
private fun CouiAppListActions(
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    filter: AppFilter,
    onFilterChange: (AppFilter) -> Unit,
    onConfigChange: (FcmfixConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allAllowed = apps.isNotEmpty() && apps.all { it.packageName in state.config.allowedPackages }
    OverlayIconDropdownMenu(
        modifier = modifier,
        entries = listOf(
            DropdownEntry(AppFilter.entries.map { option ->
                DropdownItem(
                    text = appFilterLabel(option),
                    selected = option == filter,
                    onClick = { onFilterChange(option) },
                )
            }),
            DropdownEntry(listOf(
                DropdownItem(
                    text = stringResource(R.string.apps_allow_all),
                    enabled = !state.appsLoading && !state.appsLoadFailed && apps.isNotEmpty() && !allAllowed,
                    selected = allAllowed,
                    onClick = { onConfigChange(state.config.allowApps(apps)) },
                ),
            )),
        ),
        collapseOnSelection = true,
    ) {
        Icon(COUIIcons.More, contentDescription = stringResource(R.string.apps_options))
    }
}

@Composable
private fun CouiAppsPage(
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    session: AppListSession,
    selectedFilter: AppFilter,
    query: String,
    searchState: TopAppBarSearchState,
    listState: LazyListState,
    padding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onConfigChange: (FcmfixConfig) -> Unit,
    onReload: () -> Unit,
    bottomBarPadding: PaddingValues,
) {
    val filtered = visibleApps(apps, session, state.config.allowedPackages, selectedFilter)
    val searchVisible = searchState.isResultsVisible
    Box(Modifier.fillMaxSize()) {
        if (searchState.isContentVisible) CouiPageList(
            state = listState,
            padding = padding.withBottomBar(bottomBarPadding),
            scrollBehavior = scrollBehavior,
            modifier = Modifier.topAppBarSearchContent(searchState).then(
                if (searchVisible) Modifier.semantics { hideFromAccessibility() } else Modifier
            ),
        ) {
            item(key = "app_count") {
                val enabled = apps.count { it.packageName in state.config.allowedPackages }
                val countLabel = if (state.appsLoading) R.string.apps_count_refresh else R.string.apps_count
                PreferenceCategory(title = stringResource(countLabel, enabled, apps.size))
            }
            if (state.appsLoadFailed && apps.isNotEmpty()) {
                item(key = "retry") {
                    TopTips(
                        text = stringResource(R.string.apps_refresh_failed),
                        actionText = stringResource(R.string.action_retry),
                        onAction = onReload,
                        modifier = Modifier.couiCardSpacing(),
                    )
                }
            }
            couiAppItems(state, apps, filtered, selectedFilter, false, onConfigChange, onReload)
        }
        if (searchVisible) {
            val results = remember(filtered, query) { filtered.searchApps(query) }
            CouiPageList(
                state = rememberLazyListState(),
                padding = padding,
                scrollBehavior = scrollBehavior,
                modifier = Modifier
                    .consumeWindowInsets(PaddingValues(bottom = padding.calculateBottomPadding()))
                    .imePadding()
                    .topAppBarSearchResults(searchState),
            ) {
                couiAppItems(state, apps, results, selectedFilter, query.isNotBlank(), onConfigChange, onReload)
            }
        }
    }
}

private fun LazyListScope.couiAppItems(
    state: FcmfixUiState,
    apps: List<InstalledApp>,
    visibleApps: List<InstalledApp>,
    selectedFilter: AppFilter,
    isQuery: Boolean,
    onConfigChange: (FcmfixConfig) -> Unit,
    onReload: () -> Unit,
) {
    when {
        state.appsLoading && apps.isEmpty() -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        visibleApps.isEmpty() -> item(key = "empty") {
            val empty = emptyAppsMessage(state.appsLoadFailed && apps.isEmpty(), isQuery, selectedFilter)
            EmptyState(
                title = stringResource(empty.title),
                subtitle = stringResource(empty.hint),
                actionText = if (empty.canReload) stringResource(R.string.apps_reload) else null,
                onActionClick = if (empty.canReload) onReload else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                illustration = { CouiRowIcon(COUIIcons.ListView) },
            )
        }
        else -> itemsIndexed(visibleApps, key = { _, app -> app.packageName }) { index, app ->
            val position = cardListPositionOf(index, visibleApps.size)
            SwitchPreference(
                checked = app.packageName in state.config.allowedPackages,
                onCheckedChange = { onConfigChange(state.config.setAppAllowed(app.packageName, it)) },
                title = app.label,
                summary = app.packageName,
                modifier = Modifier.padding(horizontal = 16.dp).appListCardSurface(position),
                cardListPosition = position,
                startAction = {
                    Image(
                        bitmap = app.icon,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                        contentDescription = null,
                    )
                },
            )
        }
    }
}

/** Lazy rows draw just their part of the card so the list stays virtualized. */
@Composable
private fun Modifier.appListCardSurface(position: CardListPosition): Modifier {
    val color = COUITheme.colorScheme.cardBackground
    val radius = CardDefaults.CornerRadius
    val top = if (position == CardListPosition.Head || position == CardListPosition.Full) radius else 0.dp
    val bottom = if (position == CardListPosition.Tail || position == CardListPosition.Full) radius else 0.dp
    return if (top == 0.dp && bottom == 0.dp) {
        background(color)
    } else {
        squircleSurface(color, top, top, bottom, bottom, weight = COUIRoundCorner.Normal.Weight)
    }
}
