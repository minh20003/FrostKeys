// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.TextInputDialog
import androidx.core.content.edit
import helium314.keyboard.latin.R

@Composable
fun TextInputPreference(
    setting: Setting,
    default: String,
    info: String? = null,
    isPassword: Boolean = false,
    valueProvider: (() -> String)? = null,
    valueSaver: ((String) -> Unit)? = null,
    valueClearer: (() -> Unit)? = null,
    // Keep this last so existing trailing validation lambdas remain source-compatible.
    checkTextValid: (String) -> Boolean = { true },
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = context.prefs()
    val value = valueProvider?.invoke() ?: prefs.getString(setting.key, default).orEmpty()
    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = if (isPassword && value.isNotEmpty()) {
            "••••••••"
        } else {
            value.takeIf { it.isNotEmpty() }
        }
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                if (valueSaver != null) valueSaver(it) else prefs.edit { putString(setting.key, it) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            isPassword = isPassword,
            initialText = value,
            title = { Text(setting.title) },
            description = if (info == null) null else { { Text(info) } },
            checkTextValid = checkTextValid,
            onNeutral = {
                if (valueClearer != null) valueClearer() else prefs.edit { remove(setting.key) }
                showDialog = false
            },
            neutralButtonText = stringResource(R.string.button_default)
        )
    }
}
