// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cloud

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CloudManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.prefs().edit {
            putBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, false)
            remove(CloudManager.PREF_GEMINI_API_KEY)
            remove(CloudManager.PREF_KLIPY_API_KEY)
        }
        context.protectedPrefs().edit {
            remove(CloudManager.PREF_GEMINI_API_KEY)
            remove(CloudManager.PREF_KLIPY_API_KEY)
        }
        context.getSharedPreferences("cloud_secrets_v1", Context.MODE_PRIVATE).edit {
            remove(CloudManager.PREF_GEMINI_API_KEY)
            remove(CloudManager.PREF_KLIPY_API_KEY)
        }
        CloudRequestGate.cancelAll()
    }

    @After
    fun tearDown() {
        context.prefs().edit { putBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, false) }
        CloudRequestGate.cancelAll()
    }

    @Test
    fun cloudIsOffByDefault() {
        assertFalse(CloudRequestGate.isFeatureAllowed(context, CloudFeature.AI_WRITING_TOOLS))
    }

    @Test
    fun disabledCloudBlocksRequestBeforeNetwork() {
        val request = Request.Builder().url("https://example.com/").build()

        assertFailsWith<SecurityException> {
            CloudRequestGate.execute(context, CloudFeature.KLIPY_MEDIA, request)
        }
    }

    @Test
    fun nonHttpsCloudRequestIsRejectedEvenWhenCloudIsEnabled() {
        context.prefs().edit { putBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, true) }
        val request = Request.Builder().url("http://example.com/").build()

        assertFailsWith<SecurityException> {
            CloudRequestGate.execute(context, CloudFeature.TEST_CONNECTION, request)
        }
    }

    @Test
    fun legacySecretsAndKlipyIdentifierAreExcludedFromBackups() {
        assertTrue(CloudManager.isSensitivePreferenceKey(CloudManager.PREF_GEMINI_API_KEY))
        assertTrue(CloudManager.isSensitivePreferenceKey(CloudManager.PREF_KLIPY_API_KEY))
        assertTrue(CloudManager.isSensitivePreferenceKey("klipy_customer_id"))
        assertFalse(CloudManager.isSensitivePreferenceKey(CloudManager.PREF_ENABLE_CLOUD_FEATURES))
    }

}
