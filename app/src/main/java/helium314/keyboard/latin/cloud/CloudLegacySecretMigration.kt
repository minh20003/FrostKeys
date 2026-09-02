// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cloud

import android.annotation.SuppressLint
import android.content.SharedPreferences

/**
 * Atomic-at-the-key-level migration of historical plaintext cloud credentials.
 *
 * The original app preference file was credential-protected. One short-lived migration build
 * also used device-protected preferences, so both stores must be considered and erased. The
 * encrypted destination is committed and read back before any plaintext source is modified.
 * This component has no Android Keystore dependency so its ordering and deletion guarantees can
 * be regression-tested with ordinary in-memory/shared preferences.
 */
internal object CloudLegacySecretMigration {
    data class Result(
        val migratedKeys: Set<String>,
        /** False only when an encrypted replacement was durable but one plaintext store remained. */
        val allLegacyCopiesErased: Boolean,
    )

    fun migrate(
        keys: Collection<String>,
        legacyStores: Collection<SharedPreferences>,
        secureStore: SharedPreferences,
        decryptExisting: (preferenceKey: String, ciphertext: String) -> String?,
        encrypt: (preferenceKey: String, cleartext: String) -> String?,
    ): Result {
        val stores = legacyStores.distinctBy { System.identityHashCode(it) }
        val migrated = linkedSetOf<String>()
        for (key in keys) {
            val plaintext = stores.asSequence()
                .mapNotNull { store -> store.getString(key, null)?.takeIf(String::isNotBlank) }
                .firstOrNull()
                ?: continue

            val existing = secureStore.getString(key, null)
                ?.let { decryptExisting(key, it) }
            if (existing.isNullOrBlank()) {
                val ciphertext = encrypt(key, plaintext) ?: continue
                val committed = secureStore.edit().putString(key, ciphertext).commit()
                if (!committed || secureStore.getString(key, null) != ciphertext) continue
            }
            migrated += key
        }

        val erased = stores.all { store ->
            if (migrated.isEmpty()) return@all true
            // We need `commit()`'s Boolean to prove that plaintext removal reached durable
            // storage before reporting migration success; KTX's edit(commit = true) discards it.
            @SuppressLint("UseKtx")
            val editor = store.edit()
            migrated.forEach(editor::remove)
            editor.commit() && migrated.none(store::contains)
        }
        return Result(migrated, erased)
    }
}
