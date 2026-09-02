// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import helium314.keyboard.latin.R
import helium314.keyboard.latin.cloud.CloudFeature
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.cloud.CloudRequestGate
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import dev.chrisbanes.haze.hazeSource
import helium314.keyboard.settings.LocalHazeState
import helium314.keyboard.settings.LocalSearchInnerPadding
import helium314.keyboard.settings.LocalSearchState

@Composable
fun CloudScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.cloud_features),
        settings = emptyList(),
    ) {
        val hazeState = LocalHazeState.current
        val topPadding = LocalSearchInnerPadding.current
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            top = topPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding()
                        )
                ) {
                    val searchState = LocalSearchState.current
                    if (searchState != null) {
                        searchState.searchField()
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.cloud_intro_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, "https://aistudio.google.com/".toUri())
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = stringResource(R.string.gemini_get_key_btn))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, "https://klipy.com/api-overview#overview".toUri())
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Text(text = stringResource(R.string.klipy_get_key_btn))
                            }
                        }
                    }

                    val settingsList = listOf(
                        CloudManager.PREF_ENABLE_CLOUD_FEATURES,
                        CloudManager.PREF_GEMINI_API_KEY,
                        CloudManager.PREF_KLIPY_API_KEY,
                        Settings.PREF_TRANSLATION_QUALITY,
                        Settings.PREF_TRANSLATION_SOURCE_LANGUAGE,
                        Settings.PREF_TRANSLATION_TARGET_LANGUAGE,
                        Settings.PREF_TRANSLATION_ON_DEVICE_FALLBACK,
                        Settings.PREF_AI_VISUAL_EFFECTS,
                        Settings.PREF_SEND_GIFS_AS_STICKERS,
                        CloudManager.PREF_TEST_CONNECTION
                    )
                    settingsList.forEach { key ->
                        SettingsActivity.settingsContainer[key]?.Preference()
                    }
                }
            }
        }
    }
}

fun createCloudSettings(context: Context) = listOf(
    Setting(
        context,
        CloudManager.PREF_ENABLE_CLOUD_FEATURES,
        R.string.cloud_features,
        R.string.cloud_features_summary,
    ) {
        SwitchPreference(it, false)
    },
    Setting(
        context,
        CloudManager.PREF_GEMINI_API_KEY,
        R.string.gemini_api_key,
        R.string.gemini_api_key_summary,
    ) {
        TextInputPreference(
            it,
            "",
            isPassword = true,
            valueProvider = { CloudManager.getGeminiApiKey(context) },
            valueSaver = { value -> CloudManager.setGeminiApiKey(context, value) },
            valueClearer = { CloudManager.clearGeminiApiKey(context) },
        )
    },
    Setting(
        context,
        CloudManager.PREF_KLIPY_API_KEY,
        R.string.klipy_api_key,
        R.string.klipy_api_key_summary,
    ) {
        TextInputPreference(
            it,
            "",
            isPassword = true,
            valueProvider = { CloudManager.getKlipyApiKey(context) },
            valueSaver = { value -> CloudManager.setKlipyApiKey(context, value) },
            valueClearer = { CloudManager.clearKlipyApiKey(context) },
        )
    },
    Setting(
        context,
        Settings.PREF_AI_VISUAL_EFFECTS,
        R.string.ai_visual_effects,
        R.string.ai_visual_effects_summary,
    ) {
        ListPreference(
            it,
            listOf(
                context.getString(R.string.ai_visual_effects_auto) to "auto",
                context.getString(R.string.ai_visual_effects_full) to "full",
                context.getString(R.string.ai_visual_effects_reduced) to "reduced",
                context.getString(R.string.ai_visual_effects_off) to "off",
            ),
            Defaults.PREF_AI_VISUAL_EFFECTS,
        )
    },
    Setting(
        context,
        Settings.PREF_SEND_GIFS_AS_STICKERS,
        R.string.send_gifs_as_stickers,
        R.string.send_gifs_as_stickers_summary,
    ) {
        SwitchPreference(it, Defaults.PREF_SEND_GIFS_AS_STICKERS)
    },
    Setting(
        context,
        Settings.PREF_TRANSLATION_QUALITY,
        R.string.translation_quality_title,
        R.string.translation_quality_summary,
    ) {
        ListPreference(
            it,
            listOf(
                context.getString(R.string.translation_quality_fast) to "fast",
                context.getString(R.string.translation_quality_high) to "high",
            ),
            Defaults.PREF_TRANSLATION_QUALITY,
        )
    },
    Setting(
        context,
        Settings.PREF_TRANSLATION_ON_DEVICE_FALLBACK,
        R.string.translation_on_device_fallback_title,
        R.string.translation_on_device_fallback_summary,
    ) {
        SwitchPreference(it, Defaults.PREF_TRANSLATION_ON_DEVICE_FALLBACK)
    },
    Setting(
        context,
        CloudManager.PREF_TEST_CONNECTION,
        R.string.test_connection,
        R.string.test_connection_summary,
    ) { setting ->
        val scope = rememberCoroutineScope()
        val testingMessage = stringResource(R.string.cloud_connection_testing)
        val successMessage = stringResource(R.string.cloud_connection_success)
        val blockedMessage = stringResource(R.string.cloud_connection_blocked)
        val failureMessage = stringResource(R.string.cloud_connection_failed)
        DisposableEffect(Unit) {
            onDispose { CloudRequestGate.cancelFeature(CloudFeature.TEST_CONNECTION) }
        }
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = {
                Toast.makeText(context, testingMessage, Toast.LENGTH_SHORT).show()
                scope.launch(Dispatchers.IO) {
                    try {
                        val request = Request.Builder()
                            .url("https://httpbin.org/get")
                            .build()

                        val response = CloudRequestGate.execute(
                            context,
                            CloudFeature.TEST_CONNECTION,
                            request
                        )

                        val message = response.use { resp ->
                            if (resp.isSuccessful) {
                                successMessage
                            } else {
                                failureMessage
                            }
                        }
                        withContext(Dispatchers.Main.immediate) {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: SecurityException) {
                        withContext(Dispatchers.Main.immediate) {
                            Toast.makeText(context, blockedMessage, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main.immediate) {
                            Toast.makeText(context, failureMessage, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }
)
