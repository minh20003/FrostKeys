// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cloud

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import helium314.keyboard.compat.isUserLocked
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owns encrypted optional-cloud credentials.
 *
 * Cloud preferences are deliberately device-protected because the IME needs its non-secret
 * settings before first unlock. API keys are the exception: they live in credential-protected
 * storage encrypted with an Android Keystore key and are unavailable until the user unlocks.
 * Every request itself is created by [CloudRequestGate], never by this credential store.
 */
object CloudManager {
    private const val TAG = "CloudManager"

    const val PREF_ENABLE_CLOUD_FEATURES = "pref_enable_cloud_features"
    const val PREF_TEST_CONNECTION = "pref_test_connection"

    /** Legacy device-protected preference names. Never write secrets under these names. */
    const val PREF_GEMINI_API_KEY = "pref_gemini_api_key"
    const val PREF_KLIPY_API_KEY = "pref_klipy_api_key"

    const val PREF_CACHED_GEMINI_MODELS = "pref_cached_gemini_models"
    const val PREF_GEMINI_MODELS_LAST_FETCH = "pref_gemini_models_last_fetch"

    /** Non-secret revision used only to refresh the settings UI after a secret changes. */
    const val PREF_CLOUD_SECRETS_REVISION = "pref_cloud_secrets_revision"

    private const val SECURE_PREFS_NAME = "cloud_secrets_v1"
    private const val KEYSTORE_ALIAS_SUFFIX = ".cloud-secrets-v1"
    private const val SECRET_FORMAT_VERSION = "v1"

    private val secretLock = Any()

    /** Initializes secret migration and the request gate; neither operation makes a request. */
    fun init(context: Context) {
        val applicationContext = context.applicationContext
        CloudRequestGate.init(applicationContext)
        migrateLegacySecrets(applicationContext)
    }

    fun getGeminiApiKey(context: Context): String = getSecret(context, PREF_GEMINI_API_KEY)

    fun getKlipyApiKey(context: Context): String = getSecret(context, PREF_KLIPY_API_KEY)

    fun hasAnyApiKey(context: Context): Boolean =
        getGeminiApiKey(context).isNotBlank() || getKlipyApiKey(context).isNotBlank()

    /** Returns false rather than ever falling back to plaintext when secure storage is unavailable. */
    fun setGeminiApiKey(context: Context, value: String): Boolean =
        putSecret(context, PREF_GEMINI_API_KEY, value)

    /** Returns false rather than ever falling back to plaintext when secure storage is unavailable. */
    fun setKlipyApiKey(context: Context, value: String): Boolean =
        putSecret(context, PREF_KLIPY_API_KEY, value)

    fun clearGeminiApiKey(context: Context) = clearSecret(context, PREF_GEMINI_API_KEY)

    fun clearKlipyApiKey(context: Context) = clearSecret(context, PREF_KLIPY_API_KEY)

    /** Preference keys that must never be serialized to a normal settings backup. */
    fun isSensitivePreferenceKey(key: String): Boolean = key in setOf(
        PREF_GEMINI_API_KEY,
        PREF_KLIPY_API_KEY,
        "klipy_customer_id",
    )

    private fun getSecret(context: Context, key: String): String {
        init(context)
        if (!canAccessCredentialStorage(context)) return ""
        migrateLegacySecrets(context.applicationContext)
        val value = runCatching {
            credentialContext(context).getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key, null)
        }.getOrNull() ?: return ""
        return decryptSecret(context, key, value) ?: ""
    }

    private fun putSecret(context: Context, key: String, value: String): Boolean {
        init(context)
        if (!canAccessCredentialStorage(context)) {
            Log.w(TAG, "Refusing to save $key before user unlock")
            return false
        }
        return synchronized(secretLock) {
            val securePrefs = credentialContext(context).getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            val saved = if (value.isBlank()) {
                // The KTX `commit = true` variant preserves the synchronous write required before
                // a legacy plaintext credential can be removed. Verify the resulting state before
                // proceeding, after the synchronous write has completed.
                securePrefs.edit(commit = true) { remove(key) }
                !securePrefs.contains(key)
            } else {
                val encrypted = encryptSecret(context, key, value) ?: return@synchronized false
                securePrefs.edit(commit = true) { putString(key, encrypted) }
                securePrefs.getString(key, null) == encrypted
            }
            if (saved) {
                // Remove plaintext only after its encrypted replacement has been committed.
                // This is intentionally synchronous: reporting success while retaining a
                // plaintext API key after process death would violate the migration guarantee.
                // Older releases wrote plaintext credentials to the credential-protected default
                // preferences. A short-lived migration build also had the same legacy keys in
                // device-protected preferences. Clear both locations only after the encrypted
                // replacement is durable, otherwise a crash could discard the sole key copy.
                val devicePrefs = context.applicationContext.prefs()
                val legacyCleared = removeLegacyPlaintextKeys(context, setOf(key))
                devicePrefs.edit(commit = true) {
                    if (key == PREF_GEMINI_API_KEY) {
                        // Model availability can differ by key/project. Re-discover lazily on the
                        // next explicit AI action rather than reusing a previous key's cache.
                        remove(PREF_CACHED_GEMINI_MODELS)
                        remove(PREF_GEMINI_MODELS_LAST_FETCH)
                    }
                    putLong(PREF_CLOUD_SECRETS_REVISION, System.currentTimeMillis())
                }
                return@synchronized legacyCleared
            }
            saved
        }
    }

    private fun clearSecret(context: Context, key: String) {
        putSecret(context, key, "")
        // A user explicitly clearing a credential should also erase a legacy plaintext value
        // from either storage domain. Before first unlock only device-protected storage is
        // reachable; the credential-protected copy is cleared on the next unlocked migration.
        if (canAccessCredentialStorage(context)) {
            removeLegacyPlaintextKeys(context, setOf(key))
        } else {
            context.applicationContext.prefs().edit(commit = true) { remove(key) }
        }
    }

    private fun migrateLegacySecrets(context: Context) {
        if (!canAccessCredentialStorage(context)) return
        synchronized(secretLock) {
            val devicePrefs = context.applicationContext.prefs()
            val legacyPrefs = legacyPlaintextPreferenceStores(context)
            val securePrefs = credentialContext(context).getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            val result = CloudLegacySecretMigration.migrate(
                keys = listOf(PREF_GEMINI_API_KEY, PREF_KLIPY_API_KEY),
                legacyStores = legacyPrefs,
                secureStore = securePrefs,
                decryptExisting = { key, ciphertext -> decryptSecret(context, key, ciphertext) },
                encrypt = { key, cleartext -> encryptSecret(context, key, cleartext) },
            )
            if (result.migratedKeys.isNotEmpty()) {
                // See putSecret(): plaintext deletion must be durable only after the encrypted
                // replacement has been durably committed.
                devicePrefs.edit(commit = true) {
                    putLong(PREF_CLOUD_SECRETS_REVISION, System.currentTimeMillis())
                }
                if (!result.allLegacyCopiesErased) {
                    // Do not use a credential value in this diagnostic. A later app start will
                    // retry deletion after the encrypted value is already available.
                    Log.w(TAG, "A legacy cloud credential could not be removed")
                }
            }
        }
    }

    /** Every known plaintext legacy store, with the credential-protected source first. */
    private fun legacyPlaintextPreferenceStores(context: Context): List<android.content.SharedPreferences> {
        val applicationContext = context.applicationContext
        val credentialPrefs = credentialContext(applicationContext).protectedPrefs()
        val devicePrefs = applicationContext.prefs()
        return if (credentialPrefs === devicePrefs) listOf(credentialPrefs) else listOf(credentialPrefs, devicePrefs)
    }

    /** Removes [keys] synchronously from every legacy plaintext store after secure migration. */
    private fun removeLegacyPlaintextKeys(context: Context, keys: Set<String>): Boolean {
        if (keys.isEmpty()) return true
        return legacyPlaintextPreferenceStores(context).all { preferences ->
            // Security migration must observe the synchronous durable-write result. The KTX
            // convenience extension deliberately returns Unit, so it cannot preserve this gate.
            @SuppressLint("UseKtx")
            val editor = preferences.edit()
            keys.forEach(editor::remove)
            editor.commit() && keys.none(preferences::contains)
        }
    }

    private fun canAccessCredentialStorage(context: Context): Boolean = runCatching {
        !isUserLocked(context.applicationContext)
    }.getOrDefault(false)

    // Android exposes creation of device-protected contexts, not the inverse. The manifest keeps
    // the application's default context credential-protected; direct-boot callers explicitly use
    // DeviceProtectedUtils for their non-secret state.
    private fun credentialContext(context: Context): Context = context.applicationContext

    private fun encryptSecret(context: Context, preferenceKey: String, cleartext: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(context))
        cipher.updateAAD(preferenceKey.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(cleartext.toByteArray(StandardCharsets.UTF_8))
        listOf(
            SECRET_FORMAT_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        ).joinToString(":")
    }.onFailure { Log.e(TAG, "Unable to encrypt cloud secret", it) }.getOrNull()

    private fun decryptSecret(context: Context, preferenceKey: String, stored: String): String? = runCatching {
        val parts = stored.split(':')
        require(parts.size == 3 && parts[0] == SECRET_FORMAT_VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(context),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)),
        )
        cipher.updateAAD(preferenceKey.toByteArray(StandardCharsets.UTF_8))
        String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.onFailure { Log.w(TAG, "Unable to decrypt cloud secret; treating it as unavailable", it) }.getOrNull()

    private fun getOrCreateKey(context: Context): SecretKey {
        val alias = context.packageName + KEYSTORE_ALIAS_SUFFIX
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                // The credential-protected preference file is already unavailable before the
                // first unlock. Keep the Keystore key under the same policy as defense in depth.
                // minSdk 31 guarantees this API is available on every supported device.
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }
}
