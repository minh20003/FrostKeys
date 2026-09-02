// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * Verifies the frozen Vietnamese main dictionary before it can power suggestions or spell
 * checking. The APK is already signature-protected, but this also detects an incomplete asset
 * merge and prevents an obsolete/corrupt extracted copy from silently becoming the main model.
 */
object VietnameseDictionaryAssets {
    private const val VIETNAMESE_LANGUAGE = "vi"
    private const val MAIN_DICTIONARY_ASSET = "dicts/main_vi.dict"
    private const val MANIFEST_ASSET = "manifests/dictionary_vi.json"

    private data class VerificationResult(val manifest: DictionaryManifest?)

    // Assets are immutable for the lifetime of an installed APK. Cache both success and failure
    // per AssetManager so the spell checker does not re-hash the 128 KiB asset for every word.
    private val verificationCache = Collections.synchronizedMap(
        WeakHashMap<AssetManager, VerificationResult>(),
    )

    /** Returns true for non-Vietnamese locales and for a verified bundled Vietnamese asset. */
    @JvmStatic
    fun isBundledMainDictionaryValid(context: Context, locale: Locale): Boolean {
        if (!requiresVerification(locale)) return true
        return verifiedManifest(context) != null
    }

    /**
     * Returns true when [file] is the exact, verified copy of the bundled Vietnamese main
     * dictionary. It is deliberately only used for the internal `main.dict` cache; a user-added
     * `main_user.dict` remains a separately validated, explicit user choice.
     */
    @JvmStatic
    fun isExtractedMainDictionaryValid(context: Context, locale: Locale, file: File): Boolean {
        if (!requiresVerification(locale)) return true
        val manifest = verifiedManifest(context) ?: return false
        return runCatching {
            file.isFile && file.inputStream().use { VietnameseDictionaryVerifier.matches(manifest, it) }
        }.getOrDefault(false)
    }

    internal fun requiresVerification(locale: Locale): Boolean =
        locale.language.equals(VIETNAMESE_LANGUAGE, ignoreCase = true)

    private fun verifiedManifest(context: Context): DictionaryManifest? {
        val assets = context.assets
        verificationCache[assets]?.let { return it.manifest }
        synchronized(verificationCache) {
            verificationCache[assets]?.let { return it.manifest }
            val manifest = runCatching {
                val parsedManifest = assets.open(MANIFEST_ASSET).bufferedReader().use {
                    DictionaryManifest.parse(it.readText())
                }
                assets.open(MAIN_DICTIONARY_ASSET).use { dictionary ->
                    parsedManifest.takeIf { VietnameseDictionaryVerifier.matches(it, dictionary) }
                }
            }.getOrNull()
            verificationCache[assets] = VerificationResult(manifest)
            return manifest
        }
    }
}

/** JVM-only verification core, kept separate from Android assets for deterministic unit tests. */
internal object VietnameseDictionaryVerifier {
    private const val SUPPORTED_FORMAT_VERSION = 1

    fun matches(manifest: DictionaryManifest, dictionary: InputStream): Boolean {
        if (manifest.locale != "vi" || manifest.formatVersion != SUPPORTED_FORMAT_VERSION) return false

        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = dictionary.read(buffer)
            if (count < 0) break
            byteCount += count
            // Do not keep reading a malformed unexpectedly large asset. The expected size is
            // part of the signed manifest, so this also keeps verification bounded.
            if (byteCount > manifest.byteCount) return false
            digest.update(buffer, 0, count)
        }
        return byteCount == manifest.byteCount && MessageDigest.isEqual(
            digest.digest(),
            manifest.sha256.hexToBytes(),
        )
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
