// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
package helium314.keyboard.settings.screens
import helium314.keyboard.settings.LocalSearchState
import helium314.keyboard.settings.StaticSearchField
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.TextFieldDefaults
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.compositeOver
import dev.chrisbanes.haze.hazeSource
import helium314.keyboard.settings.LocalHazeState
import helium314.keyboard.settings.LocalSearchInnerPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.AppLocaleManager
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

val materialSymbols = FontFamily(Font(R.font.material_symbols_rounded, variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 1f))))

/** Material Symbols ligatures are glyph identifiers, not user-visible strings. */
private object MaterialSymbolGlyph {
    const val CHECK = "check"
    const val CHECK_CIRCLE = "check_circle"
    const val EXPAND_MORE = "expand_more"
    const val CHEVRON_RIGHT = "chevron_right"
    const val CHEVRON_LEFT = "chevron_left"
    const val CLOUD = "cloud"
    const val GESTURE = "gesture"
    const val DICTIONARY = "dictionary"
}

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickAppLanguage: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickCloud: () -> Unit,
    onClickWelcomeWizard: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
        hideTopSearchBar = true,
        showBackButton = false,
    ) {
        val ctx = LocalContext.current
        val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()

        val hazeState = LocalHazeState.current
        val enabledSubtypes = remember(b?.value) { SubtypeSettings.getEnabledSubtypes(true) }
        val enabledSubtypeNames = remember(enabledSubtypes) {
            enabledSubtypes.joinToString(", ") { it.displayName() }
        }
        val showDataGathering = remember {
            JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS
        }
        val telegramJoined = remember(b?.value) {
            ctx.prefs().getBoolean("pref_telegram_joined", false)
        }
        val isDark = isSystemInDarkTheme()
        val scaffoldBg = if (isDark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainer
        Scaffold(
            containerColor = scaffoldBg,
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        ) { innerPadding ->
            val topPadding = LocalSearchInnerPadding.current
            
            val isGestureComplete = remember(b?.value) {
                val gesturePrefs = listOf("pref_gesture_input", "pref_gesture_space_after")
                gesturePrefs.any { ctx.prefs().getBoolean(it, true) }
            }
            val isDictionaryComplete = remember(b?.value) {
                ctx.prefs().getBoolean("pref_enable_next_word_suggestions", true)
            }
            val isCloudComplete = remember(b?.value) {
                CloudManager.hasAnyApiKey(ctx)
            }
            val allStepsComplete = isGestureComplete && isDictionaryComplete && isCloudComplete
            val isDismissed = remember(b?.value) {
                ctx.prefs().getBoolean("pref_quick_setup_dismissed", false)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
            ) {
                LazyColumn(contentPadding = PaddingValues(top = topPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())) {
                    item("search_bar") {
                        val searchState = LocalSearchState.current
                        if (searchState != null) {
                            searchState.searchField()
                            }
                        }
                    if (!telegramJoined) {
                        item("telegram_invite") {
                            TelegramInviteCard(
                                onJoinClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, "https://t.me/FrostKeys".toUri())
                                    ctx.startActivity(intent)
                                    SettingsActivity.clickedTelegramJoin = true
                                }
                            )
                        }
                    }

                    if (!isDismissed || !allStepsComplete) {
                        item("quick_setup") {
                            QuickSetupCard(
                                onClickGestureTyping = onClickGestureTyping,
                                onClickDictionaries = onClickDictionaries,
                                onClickCloud = onClickCloud,
                                onDismiss = {
                                    ctx.prefs().edit {
                                        putBoolean("pref_quick_setup_dismissed", true)
                                    }
                                },
                                isGestureComplete = isGestureComplete,
                                isDictionaryComplete = isDictionaryComplete,
                                isCloudComplete = isCloudComplete,
                                allStepsComplete = allStepsComplete
                            )
                        }
                    }

                    item("md3e_general") {
                        Md3ePreferenceGroup(stringResource(R.string.settings_group_general)) {
                            Md3ePreference(
                                icon = "translate",
                                title = stringResource(R.string.app_language_title),
                                description = appLocaleDisplayName(AppLocaleManager.currentTag(ctx)),
                                onClick = onClickAppLanguage,
                                isFirst = true
                            )
                            Md3ePreference(
                                icon = "language",
                                title = stringResource(R.string.language_and_layouts_title),
                                description = enabledSubtypeNames,
                                onClick = onClickLanguage
                            )
                            Md3ePreference(
                                icon = "tune",
                                title = stringResource(R.string.settings_screen_preferences),
                                description = stringResource(R.string.settings_main_preferences_summary),
                                onClick = onClickPreferences
                            )
                            Md3ePreference(
                                icon = "palette",
                                title = stringResource(R.string.settings_screen_appearance),
                                description = stringResource(R.string.settings_main_appearance_summary),
                                onClick = onClickAppearance
                            )
                            Md3ePreference(
                                icon = R.drawable.ic_access_point_grid,
                                title = stringResource(R.string.settings_screen_toolbar),
                                description = stringResource(R.string.settings_main_toolbar_summary),
                                onClick = onClickToolbar
                            )
                            Md3ePreference(
                                icon = MaterialSymbolGlyph.CLOUD,
                                title = stringResource(R.string.cloud_features),
                                description = stringResource(R.string.settings_main_cloud_summary),
                                onClick = onClickCloud,
                                isLast = true
                            )
                        }
                    }
                    
                    item("md3e_typing") {
                        Md3ePreferenceGroup(stringResource(R.string.settings_group_typing_input)) {
                            Md3ePreference(
                                icon = MaterialSymbolGlyph.GESTURE,
                                title = stringResource(R.string.settings_screen_gesture),
                                description = if (JniUtils.sHaveGestureLib) stringResource(R.string.settings_main_gesture_summary) else stringResource(R.string.gesture_not_loaded_summary),
                                onClick = onClickGestureTyping,
                                isFirst = true
                            )
                            if (showDataGathering) {
                                Md3ePreference(
                                    icon = MaterialSymbolGlyph.GESTURE,
                                    title = stringResource(R.string.gesture_data_screen),
                                      description = stringResource(R.string.settings_main_gesture_data_summary),
                                    onClick = onClickDataGathering
                                )
                            }
                            Md3ePreference(
                                icon = "spellcheck",
                                title = stringResource(R.string.settings_screen_correction),
                                description = stringResource(R.string.settings_main_correction_summary),
                                onClick = onClickTextCorrection
                            )
                            Md3ePreference(
                                icon = "keyboard",
                                title = stringResource(R.string.settings_screen_secondary_layouts),
                                description = stringResource(R.string.settings_main_layouts_summary),
                                onClick = onClickLayouts
                            )
                            Md3ePreference(
                                icon = MaterialSymbolGlyph.DICTIONARY,
                                title = stringResource(R.string.dictionary_settings_category),
                                description = stringResource(R.string.settings_main_dictionary_summary),
                                onClick = onClickDictionaries,
                                isLast = true
                            )
                        }
                    }
                    
                    item("md3e_more") {
                        Md3ePreferenceGroup(stringResource(R.string.settings_group_more)) {
                            Md3ePreference(
                                icon = "discover_tune",
                                title = stringResource(R.string.settings_screen_advanced),
                                description = stringResource(R.string.settings_main_advanced_summary),
                                onClick = onClickAdvanced,
                                isFirst = true
                            )
                            Md3ePreference(
                                icon = "flag",
                                title = stringResource(R.string.settings_screen_setup_wizard),
                                description = stringResource(R.string.settings_screen_setup_wizard_summary),
                                onClick = onClickWelcomeWizard
                            )
                            Md3ePreference(
                                icon = "info",
                                title = stringResource(R.string.settings_screen_about),
                                description = stringResource(R.string.settings_main_about_summary),
                                onClick = onClickAbout,
                                isLast = true
                            )
                        }
                    }
                    
                    item("spacer") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun Md3ePreferenceGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 18.dp, bottom = 8.dp)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            content()
        }
    }
}

@Composable
fun Md3ePreference(
    icon: Any,
    title: String,
    description: String? = null,
    onClick: () -> Unit,
    isFirst: Boolean = false,
    isLast: Boolean = false
) {
    val shape = RoundedCornerShape(
        topStart = if (isFirst) 24.dp else 4.dp,
        topEnd = if (isFirst) 24.dp else 4.dp,
        bottomStart = if (isLast) 24.dp else 4.dp,
        bottomEnd = if (isLast) 24.dp else 4.dp,
    )
    
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp))
        ) {
            if (icon is String) {
                Text(
                    text = icon,
                    fontFamily = materialSymbols,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            } else if (icon is Int) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuickSetupCard(
    onClickGestureTyping: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickCloud: () -> Unit,
    onDismiss: () -> Unit,
    isGestureComplete: Boolean,
    isDictionaryComplete: Boolean,
    isCloudComplete: Boolean,
    allStepsComplete: Boolean,
) {
    val ctx = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val accentColor = if (allStepsComplete) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
    val baseCardColor = MaterialTheme.colorScheme.surfaceVariant
    val opaqueContainerColor = if (allStepsComplete) Color(0xFF4CAF50).copy(alpha = 0.15f).compositeOver(baseCardColor) else baseCardColor

    val offsetX = remember { Animatable(0f) }
    var cardWidth by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = cardWidth * 0.4f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .onSizeChanged { cardWidth = it.width.toFloat() }
    ) {
        val absoluteOffset = abs(offsetX.value)
        val isSwiping = absoluteOffset > 0f

        if (isSwiping) {
            val isSwipingLeft = offsetX.value < 0
            val alignment = if (isSwipingLeft) Alignment.CenterEnd else Alignment.CenterStart
            val revealWidth = with(LocalDensity.current) { absoluteOffset.toDp() }
            // To ensure no gap between the cards, we can stretch the reveal card slightly wider
            val extraOverlap = 20.dp
            
            // Dynamic corner radius: starts as pill (height/2), then blends to match card corners (12dp) on the inner edge
            // Material Card default corner radius is typically 12dp.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(alignment)
                        .width(revealWidth + extraOverlap)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(
                            topStart = if (isSwipingLeft) 50.dp else 12.dp,
                            bottomStart = if (isSwipingLeft) 50.dp else 12.dp,
                            topEnd = if (!isSwipingLeft) 50.dp else 12.dp,
                            bottomEnd = if (!isSwipingLeft) 50.dp else 12.dp
                        ))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = alignment
                ) {
                    val iconPadding = 24.dp
                    Box(modifier = Modifier.padding(horizontal = iconPadding)) {
                        Text(
                            text = MaterialSymbolGlyph.CHECK,
                            fontFamily = materialSymbols,
                            fontSize = 28.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .scale(minOf(1f, absoluteOffset / 150f))
                                .clearAndSetSemantics {}
                        )
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = opaqueContainerColor
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .clip(CardDefaults.shape)
                .clickable { isExpanded = !isExpanded }
                .pointerInput(allStepsComplete) {
                    if (!allStepsComplete) return@pointerInput
                    val velocityTracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity().x
                            coroutineScope.launch {
                                if (abs(offsetX.value) > dismissThreshold || abs(velocity) > 800f) {
                                    val target = if (offsetX.value > 0) cardWidth else -cardWidth
                                    offsetX.animateTo(target, spring(stiffness = 200f))
                                    onDismiss()
                                } else {
                                    offsetX.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f))
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            coroutineScope.launch {
                                offsetX.snapTo(offsetX.value + dragAmount)
                            }
                        }
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        text = MaterialSymbolGlyph.CHECK_CIRCLE,
                        fontFamily = materialSymbols,
                        fontSize = 24.sp,
                        color = accentColor,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (allStepsComplete) stringResource(R.string.quick_setup_complete_title) else stringResource(R.string.quick_setup_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = MaterialSymbolGlyph.EXPAND_MORE,
                        fontFamily = materialSymbols,
                        fontSize = 24.sp,
                        color = accentColor,
                        modifier = Modifier
                            .rotate(if (isExpanded) 180f else 0f)
                            .clearAndSetSemantics {}
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (allStepsComplete) stringResource(R.string.quick_setup_complete_summary) else stringResource(R.string.quick_setup_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Spacer(modifier = Modifier.height(12.dp))

                        QuickSetupStep(
                            icon = MaterialSymbolGlyph.GESTURE,
                            title = stringResource(R.string.quick_setup_gesture_title),
                            description = stringResource(R.string.quick_setup_gesture_desc),
                            onClick = onClickGestureTyping,
                            isComplete = isGestureComplete,
                            isFirst = true
                        )
                        QuickSetupStep(
                            icon = MaterialSymbolGlyph.DICTIONARY,
                            title = stringResource(R.string.quick_setup_dict_title),
                            description = stringResource(R.string.quick_setup_dict_desc),
                            onClick = onClickDictionaries,
                            isComplete = isDictionaryComplete,
                        )
                        QuickSetupStep(
                            icon = MaterialSymbolGlyph.CLOUD,
                            title = stringResource(R.string.quick_setup_cloud_title),
                            description = stringResource(R.string.quick_setup_cloud_desc),
                            onClick = onClickCloud,
                            isComplete = isCloudComplete,
                            isLast = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSetupStep(
    icon: Any,
    title: String,
    description: String,
    onClick: () -> Unit,
    isComplete: Boolean = false,
    isFirst: Boolean = false,
    isLast: Boolean = false,
) {
    val shape = RoundedCornerShape(
        topStart = if (isFirst) 16.dp else 4.dp,
        topEnd = if (isFirst) 16.dp else 4.dp,
        bottomStart = if (isLast) 16.dp else 4.dp,
        bottomEnd = if (isLast) 16.dp else 4.dp,
    )
    val isDark = isSystemInDarkTheme()
    val bgColor = Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isComplete) Color(0xFF4CAF50).copy(alpha = 0.15f) else bgColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (icon is String) {
            Text(
                text = icon,
                fontFamily = materialSymbols,
                fontSize = 20.sp,
                color = if (isComplete) Color(0xFF388E3C) else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.clearAndSetSemantics {}
            )
        } else if (icon is Int) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (isComplete) Color(0xFF388E3C) else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isComplete) Color(0xFF388E3C) else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (isComplete) Color(0xFF388E3C).copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isComplete) {
            Text(
                text = MaterialSymbolGlyph.CHECK_CIRCLE,
                fontFamily = materialSymbols,
                fontSize = 20.sp,
                color = Color(0xFF388E3C),
                modifier = Modifier.clearAndSetSemantics {}
            )
        } else {
            Text(
                text = MaterialSymbolGlyph.CHEVRON_RIGHT,
                fontFamily = materialSymbols,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}
@Composable
private fun TelegramInviteCard(
    onJoinClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF229ED9)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onJoinClick() }
                .padding(16.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_telegram_white),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.telegram_invite_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.telegram_invite_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = MaterialSymbolGlyph.CHEVRON_LEFT,
                fontFamily = materialSymbols,
                fontSize = 24.sp,
                color = Color.White.copy(alpha = 0.8f),
                modifier = (if (LocalLayoutDirection.current == LayoutDirection.Ltr) Modifier.scale(-1f, 1f) else Modifier)
                    .clearAndSetSemantics {}
            )
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
