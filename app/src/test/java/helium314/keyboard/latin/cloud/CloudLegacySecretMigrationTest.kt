// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cloud

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudLegacySecretMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun credentialStoreWinsAndAllPlaintextCopiesAreErasedAfterVerifiedEncryption() {
        val credential = preferences("credential")
        val device = preferences("device")
        val secure = preferences("secure")
        credential.edit { putString(KEY, "credential-value") }
        device.edit { putString(KEY, "device-value") }

        val result = CloudLegacySecretMigration.migrate(
            keys = listOf(KEY),
            legacyStores = listOf(credential, device),
            secureStore = secure,
            decryptExisting = ::decryptFixture,
            encrypt = ::encryptFixture,
        )

        assertEquals(setOf(KEY), result.migratedKeys)
        assertTrue(result.allLegacyCopiesErased)
        assertEquals("credential-value", decryptFixture(KEY, secure.getString(KEY, null)!!))
        assertFalse(credential.contains(KEY))
        assertFalse(device.contains(KEY))
    }

    @Test
    fun encryptionFailureLeavesTheOnlyPlaintextCopyUntouched() {
        val credential = preferences("failed-credential")
        val secure = preferences("failed-secure")
        credential.edit { putString(KEY, "still-available") }

        val result = CloudLegacySecretMigration.migrate(
            keys = listOf(KEY),
            legacyStores = listOf(credential),
            secureStore = secure,
            decryptExisting = ::decryptFixture,
            encrypt = { _, _ -> null },
        )

        assertTrue(result.migratedKeys.isEmpty())
        assertTrue(result.allLegacyCopiesErased)
        assertEquals("still-available", credential.getString(KEY, null))
        assertFalse(secure.contains(KEY))
    }

    @Test
    fun existingVerifiedCiphertextDeletesStalePlaintextWithoutReplacingIt() {
        val credential = preferences("existing-credential")
        val device = preferences("existing-device")
        val secure = preferences("existing-secure")
        credential.edit { putString(KEY, "old-credential") }
        device.edit { putString(KEY, "old-device") }
        secure.edit { putString(KEY, encryptFixture(KEY, "already-encrypted")) }

        val result = CloudLegacySecretMigration.migrate(
            keys = listOf(KEY),
            legacyStores = listOf(credential, device),
            secureStore = secure,
            decryptExisting = ::decryptFixture,
            encrypt = ::encryptFixture,
        )

        assertEquals(setOf(KEY), result.migratedKeys)
        assertTrue(result.allLegacyCopiesErased)
        assertEquals("already-encrypted", decryptFixture(KEY, secure.getString(KEY, null)!!))
        assertFalse(credential.contains(KEY))
        assertFalse(device.contains(KEY))
    }

    private fun preferences(suffix: String) = context.getSharedPreferences(
        "CloudLegacySecretMigrationTest.$suffix",
        Context.MODE_PRIVATE,
    ).also { it.edit().clear().commit() }

    private fun encryptFixture(key: String, cleartext: String): String = "$key::$cleartext"

    private fun decryptFixture(key: String, ciphertext: String): String? =
        ciphertext.removePrefix("$key::").takeIf { ciphertext.startsWith("$key::") }

    private companion object {
        const val KEY = "pref_gemini_api_key"
    }
}
