// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import helium314.keyboard.settings.filePicker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun BackupRestorePreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    var error: String? by rememberSaveable { mutableStateOf(null) }
    var operationInFlight by remember { mutableStateOf(false) }
    // Passwords are intentionally not saveable: Android must not write them to saved instance
    // state. The archive API takes ownership and clears each CharArray after use.
    var pendingBackupPassword by remember { mutableStateOf<CharArray?>(null) }
    var pendingRestorePassword by remember { mutableStateOf<CharArray?>(null) }
    var passwordAction by remember { mutableStateOf<BackupPasswordAction?>(null) }
    fun clearPendingPasswords() {
        pendingBackupPassword?.fill('\u0000')
        pendingRestorePassword?.fill('\u0000')
        pendingBackupPassword = null
        pendingRestorePassword = null
    }
    val backupLauncher = backupLauncher(
        takePassword = {
            pendingBackupPassword.also { pendingBackupPassword = null }
        },
        onError = { error = it },
        onFinished = { operationInFlight = false },
    )
    val restoreLauncher = restoreLauncher(
        takePassword = {
            pendingRestorePassword.also { pendingRestorePassword = null }
        },
        onError = { error = it },
        onFinished = { operationInFlight = false },
    )
    Preference(name = setting.title, onClick = {
        if (!operationInFlight && passwordAction == null) showDialog = true
    })
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            content = { Text(stringResource(R.string.backup_restore_message)) },
            confirmButtonText = stringResource(R.string.button_backup),
            neutralButtonText = stringResource(R.string.button_restore),
            onNeutral = {
                if (!operationInFlight) {
                    showDialog = false
                    passwordAction = BackupPasswordAction.RESTORE
                }
            },
            onConfirmed = {
                if (!operationInFlight) passwordAction = BackupPasswordAction.BACKUP
            }
        )
    }
    passwordAction?.let { action ->
        BackupPasswordDialog(
            action = action,
            onDismissRequest = { passwordAction = null },
            onContinue = { password ->
                if (operationInFlight) {
                    password?.fill('\u0000')
                } else {
                    passwordAction = null
                    when (action) {
                        BackupPasswordAction.BACKUP -> {
                            pendingBackupPassword?.fill('\u0000')
                            pendingBackupPassword = password
                            operationInFlight = true
                            try {
                                backupLauncher.launch(createBackupIntent(ctx))
                            } catch (t: Throwable) {
                                clearPendingPasswords()
                                operationInFlight = false
                                error = "b" + (t.message ?: t.javaClass.simpleName)
                            }
                        }
                        BackupPasswordAction.RESTORE -> {
                            pendingRestorePassword?.fill('\u0000')
                            pendingRestorePassword = password
                            operationInFlight = true
                            try {
                                restoreLauncher.launch(createRestoreIntent())
                            } catch (t: Throwable) {
                                clearPendingPasswords()
                                operationInFlight = false
                                error = "r" + (t.message ?: t.javaClass.simpleName)
                            }
                        }
                    }
                }
            },
        )
    }
    if (error != null) {
        InfoDialog(
            if (error!!.startsWith("b"))
                stringResource(R.string.backup_error, error!!.drop(1))
            else stringResource(R.string.restore_error, error!!.drop(1))
        ) { error = null }
    }
}

@Composable
private fun backupLauncher(
    takePassword: () -> CharArray?,
    onError: (String) -> Unit,
    onFinished: () -> Unit,
): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    return filePicker(onUri = { uri ->
        val password = takePassword()
        val task = Runnable {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { BackupArchiveV2.write(ctx, it, password) }
                    ?: throw IllegalStateException("Unable to open backup destination")
            } catch (t: Throwable) {
                mainHandler.post { onError("b" + (t.message ?: t.javaClass.simpleName)) }
                Log.w("BackupRestorePreference", "error during backup", t)
            } finally {
                password?.fill('\u0000')
                mainHandler.post(onFinished)
            }
        }
        try {
            ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute(task)
        } catch (t: Throwable) {
            password?.fill('\u0000')
            mainHandler.post {
                onError("b" + (t.message ?: t.javaClass.simpleName))
                onFinished()
            }
        }
    }, onCancelled = {
        takePassword()?.fill('\u0000')
        onFinished()
    })
}

@Composable
private fun restoreLauncher(
    takePassword: () -> CharArray?,
    onError: (String) -> Unit,
    onFinished: () -> Unit,
): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    return filePicker(onUri = { uri ->
        val password = takePassword()
        val task = Runnable {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { BackupArchiveV2.restore(ctx, it, password) }
                    ?: throw IllegalStateException("Unable to open backup source")
                BackupArchiveV2.refreshAfterRestore(ctx)
                mainHandler.post {
                    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
                    Toast.makeText(ctx, resources.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                mainHandler.post { onError("r" + (t.message ?: t.javaClass.simpleName)) }
                Log.w("BackupRestorePreference", "error during restore", t)
            } finally {
                password?.fill('\u0000')
                mainHandler.post(onFinished)
            }
        }
        try {
            ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute(task)
        } catch (t: Throwable) {
            password?.fill('\u0000')
            mainHandler.post {
                onError("r" + (t.message ?: t.javaClass.simpleName))
                onFinished()
            }
        }
    }, onCancelled = {
        takePassword()?.fill('\u0000')
        onFinished()
    })
}

private enum class BackupPasswordAction { BACKUP, RESTORE }

@Composable
private fun BackupPasswordDialog(
    action: BackupPasswordAction,
    onDismissRequest: () -> Unit,
    onContinue: (CharArray?) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val isBackup = action == BackupPasswordAction.BACKUP
    val matches = !isBackup || password == confirmation
    fun dismissAndClear() {
        password = ""
        confirmation = ""
        onDismissRequest()
    }
    ThreeButtonAlertDialog(
        onDismissRequest = ::dismissAndClear,
        onConfirmed = {
            val chars = password.toCharArray()
            password = ""
            confirmation = ""
            onContinue(chars)
        },
        title = {
            Text(stringResource(if (isBackup) R.string.backup_learning_password_title else R.string.restore_learning_password_title))
        },
        content = {
            Column {
                Text(stringResource(if (isBackup) R.string.backup_learning_password_message else R.string.restore_learning_password_message))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (isBackup) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.backup_password_confirm_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = confirmation.isNotEmpty() && !matches,
                    )
                    if (confirmation.isNotEmpty() && !matches) {
                        Text(stringResource(R.string.backup_password_mismatch))
                    }
                }
            }
        },
        confirmButtonText = stringResource(R.string.backup_password_continue),
        neutralButtonText = stringResource(
            if (isBackup) R.string.backup_without_learning else R.string.restore_without_password,
        ),
        onNeutral = {
            password = ""
            confirmation = ""
            onContinue(null)
            onDismissRequest()
        },
        checkOk = { password.isNotEmpty() && matches },
    )
}

private fun createBackupIntent(context: Context): Intent {
    val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
    return Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .putExtra(
            Intent.EXTRA_TITLE,
            context.getString(R.string.english_ime_name)
                .replace(" ", "_") + "_backup_$currentDate.zip",
        )
        .setType("application/zip")
}

private fun createRestoreIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE)
    .setType("application/zip")

fun restoreSilently(ctx: Context, inputStream: InputStream): Boolean {
    return try {
        BackupArchiveV2.restore(ctx, inputStream)
        BackupArchiveV2.refreshAfterRestore(ctx)
        true
    } catch (t: Throwable) {
        Log.w("BackupRestorePreference", "error during silent restore", t)
        return false
    }
}
