// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.AppLocaleManager
import helium314.keyboard.settings.SearchScreen

private data class AppLocaleOption(
    val tag: String,
    @param:StringRes val labelRes: Int,
)

private val appLocaleOptions = listOf(
    AppLocaleOption(AppLocaleManager.VIETNAMESE, R.string.app_language_vietnamese),
    AppLocaleOption(AppLocaleManager.ENGLISH, R.string.app_language_english),
    AppLocaleOption(AppLocaleManager.JAPANESE, R.string.app_language_japanese),
    AppLocaleOption(AppLocaleManager.CHINESE_SIMPLIFIED, R.string.app_language_chinese_simplified),
    AppLocaleOption(AppLocaleManager.CHINESE_TRADITIONAL, R.string.app_language_chinese_traditional),
    AppLocaleOption(AppLocaleManager.KOREAN, R.string.app_language_korean),
    AppLocaleOption(AppLocaleManager.THAI, R.string.app_language_thai),
)

@Composable
fun AppLanguageScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    var selectedTag by remember { mutableStateOf(AppLocaleManager.currentTag(context)) }
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.app_language_title)) },
        hideTopSearchBar = true,
        filteredItems = { appLocaleOptions },
        itemContent = { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedTag = option.tag
                        AppLocaleManager.setLocale(context, option.tag)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                RadioButton(
                    selected = selectedTag == option.tag,
                    onClick = {
                        selectedTag = option.tag
                        AppLocaleManager.setLocale(context, option.tag)
                    },
                )
                Text(
                    text = stringResource(option.labelRes),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        },
    )
}

@Composable
fun appLocaleDisplayName(tag: String): String = stringResource(
    appLocaleOptions.firstOrNull { it.tag == tag }?.labelRes ?: R.string.app_language_vietnamese,
)
