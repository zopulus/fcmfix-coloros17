@file:OptIn(ExperimentalScrollBarApi::class)

package io.github.zopulus.ffc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.blur.TopAppBarGlow
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.effect.LocalPopupMaterialBlur
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.interfaces.ExperimentalScrollBarApi
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic
import io.github.zopulus.ffc.R

// Reserve space at the end of the list while allowing its contents to scroll behind the bar.
internal fun PaddingValues.withBottomBar(bottomBar: PaddingValues): PaddingValues =
    object : PaddingValues by this {
        override fun calculateBottomPadding() =
            maxOf(this@withBottomBar.calculateBottomPadding(), bottomBar.calculateBottomPadding())
    }

// Adapted from the COUI page scaffold: only page content is recorded into the backdrop.
@Composable
internal fun CouiPageScaffold(
    title: String,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    searchState: TopAppBarSearchState? = null,
    searchBar: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    onBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val backdrop = rememberPageBackdrop()
    val popup = rememberPopupMaterialBlur(backdrop)
    val colors = COUITheme.colorScheme
    val barColor = if (backdrop == null) colors.backgroundWithCard else Color.Transparent
    val hasSearch = searchState != null
    val glow: (@Composable BoxScope.() -> Unit)? = backdrop?.let { layer ->
        {
            TopAppBarGlow(
                backdrop = layer,
                scrollOffsetPx = scrollBehavior.glowScrollOffsetPx(),
                isDark = colors.isDark,
                baseColor = colors.backgroundWithCard,
                enabled = true,
            )
        }
    }
    val size = LocalWindowInfo.current.containerSize
    val wide = with(LocalDensity.current) {
        size.width.toDp() >= 840.dp || (size.width.toDp() >= 600.dp && size.height < size.width)
    }
    val navigationIcon: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(COUIIcons.Back, contentDescription = stringResource(R.string.action_back))
            }
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CompositionLocalProvider(LocalPopupMaterialBlur provides popup) {
                if (wide && !hasSearch) {
                    SmallTopAppBar(
                        title = title,
                        subtitle = subtitle,
                        color = barColor,
                        glow = glow,
                        navigationIcon = navigationIcon,
                        actions = actions,
                    )
                } else {
                    TopAppBar(
                        title = title,
                        subtitle = subtitle,
                        color = barColor,
                        scrollBehavior = scrollBehavior,
                        expandedHeight = if (hasSearch) 116.dp else TopAppBarDefaults.ExpandedHeight,
                        largeTitleFontSize = if (hasSearch) 40.sp else TopAppBarDefaults.largeTitleFontSize(),
                        titlePadding = if (hasSearch) 24.dp else Dp.Unspecified,
                        largeTitleBottomMargin = if (hasSearch) 18.dp else Dp.Unspecified,
                        glow = glow,
                        actions = actions,
                        navigationIcon = navigationIcon,
                        searchState = searchState,
                        bottomContent = searchBar,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
            CompositionLocalProvider(LocalPageBackdrop provides backdrop, LocalPopupMaterialBlur provides popup) {
                content(padding, scrollBehavior)
            }
        }
    }
}

@Composable
internal fun CouiPageList(
    state: LazyListState,
    padding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = padding,
        ) {
            item(key = "top_inset") { Spacer(Modifier.height(12.dp)) }
            content()
            item(key = "bottom_inset") { Spacer(Modifier.height(24.dp)) }
        }
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(state),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            trackPadding = padding,
        )
    }
}
