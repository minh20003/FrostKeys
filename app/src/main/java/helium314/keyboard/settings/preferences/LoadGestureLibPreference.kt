// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.filePicker

@SuppressLint("ApplySharedPref")
@Composable
fun LoadGestureLibPreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var wasRejected by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val prefs = ctx.protectedPrefs()
    val abi = Build.SUPPORTED_ABIS[0]
    val libFile = JniUtils.getImportedGestureLibraryFile(ctx)

    fun clearLegacyChecksum() {
        // Older versions allowed a user-entered checksum. It must not authorize code now.
        prefs.edit(commit = true) { remove(Settings.PREF_LIBRARY_CHECKSUM) }
    }

    val launcher = filePicker { uri ->
        val installed = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                JniUtils.installTrustedGestureLibrary(ctx, it)
            } == true
        }.getOrDefault(false)
        if (installed) {
            clearLegacyChecksum()
            Runtime.getRuntime().exit(0) // restart so JniUtils validates and loads the fixed file
        } else {
            wasRejected = true
        }
    }

    Preference(
        name = setting.title,
        onClick = { showDialog = true }
    )
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                showDialog = false
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream")
                launcher.launch(intent)
            },
            confirmButtonText = stringResource(R.string.load_gesture_library_button_load),
            title = { Text(stringResource(R.string.load_gesture_library)) },
            content = { Text(stringResource(R.string.load_gesture_library_message, abi)) },
            neutralButtonText = if (libFile.exists()) stringResource(R.string.load_gesture_library_button_delete) else null,
            onNeutral = {
                JniUtils.deleteImportedGestureLibrary(ctx)
                clearLegacyChecksum()
                Runtime.getRuntime().exit(0)
            }
        )
    }
    if (wasRejected) {
        ConfirmationDialog(
            onDismissRequest = { wasRejected = false },
            onConfirmed = { wasRejected = false },
            content = { Text(stringResource(R.string.checksum_mismatch_message, abi)) },
        )
    }
}
