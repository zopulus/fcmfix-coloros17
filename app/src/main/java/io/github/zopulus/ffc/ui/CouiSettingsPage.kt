package io.github.zopulus.ffc.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Theme
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.layout.DialogDefaults
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.popup.OverlayDropdownPopup
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.zopulus.ffc.FcmfixConfig
import io.github.zopulus.ffc.R

@Composable
internal fun CouiSettingsScreen(
    config: FcmfixConfig,
    launcherIconHidden: Boolean,
    style: InterfaceStyle,
    onStyleChange: (InterfaceStyle) -> Unit,
    onConfigChange: (FcmfixConfig) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    onBack: (() -> Unit)? = null,
    bottomBarPadding: PaddingValues = PaddingValues(0.dp),
) {
    val listState = rememberLazyListState()
    CouiPageScaffold(
        title = stringResource(MainTab.SETTINGS.title),
        scrollBehavior = COUIScrollBehavior(),
        onBack = onBack,
    ) { padding, behavior ->
        CouiSettingsPage(
            config, launcherIconHidden, listState, padding.withBottomBar(bottomBarPadding), behavior,
            style, onStyleChange, onConfigChange, onLauncherIconHiddenChange,
        )
    }
}

@Composable
private fun CouiSettingsPage(
    config: FcmfixConfig,
    launcherIconHidden: Boolean,
    listState: LazyListState,
    padding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    style: InterfaceStyle,
    onStyleChange: (InterfaceStyle) -> Unit,
    onConfigChange: (FcmfixConfig) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmation by remember { mutableStateOf<SettingConfirmation?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }
    val colors = COUITheme.colorScheme
    val groups = settingsGroups(config, launcherIconHidden, onConfigChange, onLauncherIconHiddenChange) {
        confirmation = it
        showConfirmation = true
    }
    CouiPageList(listState, padding, scrollBehavior, modifier) {
        groups.forEach { group ->
            item(key = group.title) {
                PreferenceCategory(title = stringResource(group.title))
                Card(Modifier.couiCardSpacing()) {
                    CardListGroup {
                        if (group.includeInterfaceStyle) {
                            item(key = "interface_style") {
                                CouiStylePreference(style, onStyleChange)
                            }
                        }
                        group.items.forEach { setting ->
                            item(key = setting.title) {
                                SwitchPreference(
                                    title = stringResource(setting.title),
                                    summary = stringResource(setting.summary),
                                    startAction = {
                                        CouiRowIcon(setting.icon, if (setting.enabled) colors.primary else colors.disabledNeutral)
                                    },
                                    checked = setting.checked,
                                    enabled = setting.enabled,
                                    onCheckedChange = setting.onCheckedChange,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // Keep the request through the close animation, outside the lazy list.
    val request = confirmation
    if (request != null) {
        val backdrop = dialogBackdrop()
        OverlayDialog(
            show = showConfirmation,
            title = stringResource(request.title),
            summary = stringResource(request.message),
            onDismissRequest = { showConfirmation = false },
            onDismissFinished = { confirmation = null },
            backgroundModifier = backdrop ?: Modifier,
            backgroundColor = if (backdrop == null) DialogDefaults.backgroundColor() else Color.Transparent,
            materialStroke = backdrop != null,
        ) {
            DialogButtonBar(
                negative = DialogButtonBarAction(stringResource(R.string.action_cancel)) { showConfirmation = false },
                positive = DialogButtonBarAction(stringResource(request.action)) {
                    showConfirmation = false
                    request.confirm(onLauncherIconHiddenChange)
                },
            )
        }
    }
}

@Composable
private fun CouiStylePreference(style: InterfaceStyle, onStyleChange: (InterfaceStyle) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var pendingStyle by remember { mutableStateOf<InterfaceStyle?>(null) }
    val anchor = rememberPreciseClickState()
    val colors = COUITheme.colorScheme

    BasicComponent(
        modifier = Modifier.preciseClickAnchor(anchor),
        title = stringResource(R.string.settings_style),
        summary = stringResource(R.string.settings_style_hint),
        startAction = { CouiRowIcon(COUIIcons.Theme) },
        role = Role.DropdownList,
        holdDownState = held,
        onClick = {
            if (!held) {
                held = true
                expanded = true
            }
        },
        endActions = {
            Text(
                text = stringResource(style.title),
                modifier = Modifier.padding(end = 4.dp).align(Alignment.CenterVertically)
                    .weight(1f, fill = false),
                style = COUITheme.textStyles.bodyS,
                color = colors.labelSecondary,
                textAlign = TextAlign.End,
                maxLines = 2,
            )
            DropdownArrowEndAction(actionColor = colors.labelTertiary)
            OverlayDropdownPopup(
                show = expanded,
                entry = DropdownEntry(InterfaceStyle.entries.map { option ->
                    DropdownItem(
                        text = stringResource(option.title),
                        selected = style == option,
                        onClick = { if (expanded) pendingStyle = option.takeUnless { it == style } },
                    )
                }),
                preciseClickState = anchor,
                maxHeight = null,
                dropdownColors = DropdownDefaults.dropdownColors(),
                renderInRootScaffold = true,
                onDismiss = { expanded = false },
                onDismissFinished = {
                    held = false
                    val selected = pendingStyle
                    pendingStyle = null
                    selected?.let(onStyleChange)
                },
            )
        },
    )
}

internal fun Modifier.couiCardSpacing(): Modifier = padding(horizontal = 16.dp).padding(bottom = 16.dp)

@Composable
internal fun CouiRowIcon(@DrawableRes drawable: Int, tint: Color = COUITheme.colorScheme.primary) =
    CouiRowIcon(ImageVector.vectorResource(drawable), tint)

@Composable
internal fun CouiRowIcon(icon: ImageVector, tint: Color = COUITheme.colorScheme.primary) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
}
