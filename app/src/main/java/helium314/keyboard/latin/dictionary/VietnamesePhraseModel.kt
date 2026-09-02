// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import android.content.Context
import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Immutable metadata for FrostKeys' bounded, offline Vietnamese phrase model. */
data class VietnamesePhraseModelManifest(
    val artifact: String,
    val locale: String,
    val version: String,
    val source: String,
    val license: String,
    val sha256: String,
    val byteCount: Long,
    val formatVersion: Int,
    val entryCount: Int,
    val maxEntries: Int,
    val maxCandidatesPerContext: Int,
    val sourceSha256: String,
    val sourceByteCount: Long,
    val sourceLicense: String,
) {
    init {
        require(artifact == PHRASE_MODEL_ASSET_PATH) { "Vietnamese phrase model artifact path is invalid" }
        require(locale == "vi") { "Vietnamese phrase model locale must be vi" }
        require(version.isNotBlank() && source.isNotBlank() && license.isNotBlank()) {
            "Vietnamese phrase model provenance is incomplete"
        }
        require(sha256.isSha256() && sourceSha256.isSha256()) {
            "Vietnamese phrase model has an invalid SHA-256"
        }
        require(byteCount in 1..MAX_ARTIFACT_BYTES) { "Vietnamese phrase model size is invalid" }
        require(sourceByteCount in 1..MAX_SOURCE_BYTES) { "Vietnamese phrase model source size is invalid" }
        require(formatVersion == SUPPORTED_FORMAT_VERSION) { "Unsupported Vietnamese phrase model format" }
        require(entryCount in 1..MAX_ENTRY_LIMIT && maxEntries in entryCount..MAX_ENTRY_LIMIT) {
            "Vietnamese phrase model entry limit is invalid"
        }
        require(maxCandidatesPerContext in 1..MAX_CANDIDATES_PER_CONTEXT_LIMIT) {
            "Vietnamese phrase model candidate limit is invalid"
        }
        require(sourceLicense.isNotBlank()) { "Vietnamese phrase model source license is missing" }
    }

    companion object {
        private const val MAX_ARTIFACT_BYTES = 64L * 1024L
        private const val MAX_SOURCE_BYTES = 64L * 1024L
        private const val MAX_ENTRY_LIMIT = 256
        private const val MAX_CANDIDATES_PER_CONTEXT_LIMIT = 4
        internal const val SUPPORTED_FORMAT_VERSION = 1
        private const val PHRASE_MODEL_ASSET_PATH = "dicts/vi_phrase_model_v1.tsv"
        private val REQUIRED_KEYS = setOf(
            "artifact",
            "byteCount",
            "entryCount",
            "formatVersion",
            "license",
            "locale",
            "maxCandidatesPerContext",
            "maxEntries",
            "sha256",
            "source",
            "sourceByteCount",
            "sourceLicense",
            "sourceSha256",
            "version",
        )

        fun parse(json: String): VietnamesePhraseModelManifest =
            Json.parseToJsonElement(json).jsonObject.let { objectJson ->
                require(objectJson.keys == REQUIRED_KEYS) {
                    "Vietnamese phrase model manifest has missing or unsupported fields"
                }
                VietnamesePhraseModelManifest(
                    artifact = objectJson.requiredString("artifact"),
                    locale = objectJson.requiredString("locale"),
                    version = objectJson.requiredString("version"),
                    source = objectJson.requiredString("source"),
                    license = objectJson.requiredString("license"),
                    sha256 = objectJson.requiredString("sha256").lowercase(Locale.ROOT),
                    byteCount = objectJson.requiredLong("byteCount"),
                    formatVersion = objectJson.requiredInt("formatVersion"),
                    entryCount = objectJson.requiredInt("entryCount"),
                    maxEntries = objectJson.requiredInt("maxEntries"),
                    maxCandidatesPerContext = objectJson.requiredInt("maxCandidatesPerContext"),
                    sourceSha256 = objectJson.requiredString("sourceSha256").lowercase(Locale.ROOT),
                    sourceByteCount = objectJson.requiredLong("sourceByteCount"),
                    sourceLicense = objectJson.requiredString("sourceLicense"),
                )
            }

        private fun JsonObject.requiredString(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("Vietnamese phrase model manifest is missing $key")

        private fun JsonObject.requiredLong(key: String): Long =
            this[key]?.jsonPrimitive?.longOrNull
                ?: throw IllegalArgumentException("Vietnamese phrase model manifest has invalid $key")

        private fun JsonObject.requiredInt(key: String): Int =
            this[key]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("Vietnamese phrase model manifest has invalid $key")
    }
}

/** A next-word candidate from the sealed Vietnamese phrase model. */
data class VietnamesePhrasePrediction(val word: String, val score: Int)

/**
 * A tiny read-only bigram/trigram model. It is purposely not a replacement for the binary main
 * dictionary: it only contributes next-word predictions after a completed Vietnamese word. The
 * model never sees raw editor text, requests a network resource, or learns from the user.
 */
class VietnamesePhraseModelData private constructor(
    private val bigrams: Map<String, List<VietnamesePhrasePrediction>>,
    private val trigrams: Map<String, List<VietnamesePhrasePrediction>>,
    private val maxCandidates: Int,
) {
    /**
     * Returns trigram candidates first, then fills remaining slots from the last-word bigram.
     * Lookup is NFC/case-insensitive while output keeps the authored display case (for example
     * `Việt` -> `Nam`). Invalid/contextual tokens such as `<S>` simply produce no match.
     */
    fun predict(previousWords: Array<String>, limit: Int = maxCandidates): List<VietnamesePhrasePrediction> {
        if (limit <= 0) return emptyList()
        val rawContext = previousWords.takeLast(2)
        if (rawContext.isEmpty()) return emptyList()
        // Do not discard an invalid final context token and accidentally predict from an older
        // word. Numeric fields, punctuation and the sentence marker must simply opt out.
        val maybeNormalizedWords = rawContext.map(::safeLookupToken)
        if (maybeNormalizedWords.any { it == null }) return emptyList()
        val normalizedWords = maybeNormalizedWords.filterNotNull()

        val candidates = ArrayList<VietnamesePhrasePrediction>(limit)
        if (normalizedWords.size == 2) {
            candidates.addAll(trigrams[normalizedWords.joinToString(CONTEXT_SEPARATOR)].orEmpty())
        }
        candidates.addAll(bigrams[normalizedWords.last()].orEmpty())
        return candidates
            .distinctBy { lookupToken(it.word) }
            .take(limit)
    }

    companion object {
        private const val MAX_SCORE = 1000
        private const val MAX_TOKEN_CODE_POINTS = 48
        private const val CONTEXT_SEPARATOR = "\u0001"
        private val REQUIRED_HEADER = listOf(
            "# SPDX-License-Identifier: GPL-3.0-only",
            "# Generated by tools/vietnamese/build_phrase_model.py; do not edit manually.",
            "# format: order<TAB>score<TAB>context word(s)<TAB>candidate",
        )

        /** Parses a verified artifact. Kept JVM-only so malformed-model cases are unit-testable. */
        fun parse(serializedModel: String, manifest: VietnamesePhraseModelManifest): VietnamesePhraseModelData {
            val lines = serializedModel.splitToSequence('\n').map { it.removeSuffix("\r") }.toList()
            require(lines.take(REQUIRED_HEADER.size) == REQUIRED_HEADER) {
                "Vietnamese phrase model header is missing or incompatible"
            }
            val entries = ArrayList<ParsedEntry>(manifest.entryCount)
            val seen = HashSet<String>()
            val perContextCounts = HashMap<String, Int>()
            lines.drop(REQUIRED_HEADER.size).forEachIndexed { entryOffset, line ->
                if (line.isBlank()) return@forEachIndexed
                require(!line.startsWith('#')) { "Unexpected comment in Vietnamese phrase model" }
                val parts = line.split('\t')
                val lineNumber = entryOffset + REQUIRED_HEADER.size + 1
                val order = parts.firstOrNull()?.toIntOrNull()
                    ?: throw IllegalArgumentException("Vietnamese phrase model line $lineNumber has invalid order")
                require(order == 2 || order == 3) {
                    "Vietnamese phrase model line $lineNumber has unsupported n-gram order"
                }
                require(parts.size == order + 2) {
                    "Vietnamese phrase model line $lineNumber has invalid column count"
                }
                val score = parts[1].toIntOrNull()
                    ?: throw IllegalArgumentException("Vietnamese phrase model line $lineNumber has invalid score")
                require(score in 1..MAX_SCORE) {
                    "Vietnamese phrase model line $lineNumber score is out of range"
                }
                val context = parts.subList(2, parts.lastIndex).map(::displayToken)
                val candidate = displayToken(parts.last())
                val normalizedContext = context.map(::lookupToken).joinToString(CONTEXT_SEPARATOR)
                val candidateKey = lookupToken(candidate)
                require(seen.add("$order\u0002$normalizedContext\u0002$candidateKey")) {
                    "Vietnamese phrase model line $lineNumber duplicates an existing candidate"
                }
                val candidateCount = (perContextCounts[normalizedContext] ?: 0) + 1
                require(candidateCount <= manifest.maxCandidatesPerContext) {
                    "Vietnamese phrase model has too many candidates for one context"
                }
                perContextCounts[normalizedContext] = candidateCount
                entries += ParsedEntry(order, score, normalizedContext, candidate)
                require(entries.size <= manifest.maxEntries) { "Vietnamese phrase model exceeds entry limit" }
            }
            require(entries.size == manifest.entryCount) {
                "Vietnamese phrase model entry count does not match manifest"
            }

            fun grouped(order: Int): Map<String, List<VietnamesePhrasePrediction>> = entries
                .asSequence()
                .filter { it.order == order }
                .groupBy { it.normalizedContext }
                .mapValues { (_, candidates) ->
                    candidates.sortedWith(
                        compareByDescending<ParsedEntry> { it.score }
                            .thenBy { lookupToken(it.candidate) },
                    ).map { VietnamesePhrasePrediction(it.candidate, it.score) }
                }
            return VietnamesePhraseModelData(
                bigrams = grouped(order = 2),
                trigrams = grouped(order = 3),
                maxCandidates = manifest.maxCandidatesPerContext,
            )
        }

        private fun displayToken(rawToken: String): String {
            val token = Normalizer.normalize(rawToken.trim(), Normalizer.Form.NFC)
            require(token.isNotEmpty() && token.codePointCount(0, token.length) <= MAX_TOKEN_CODE_POINTS) {
                "Vietnamese phrase model token is empty or too long"
            }
            var charIndex = 0
            while (charIndex < token.length) {
                val codePoint = token.codePointAt(charIndex)
                val validLetterOrMark = Character.isLetter(codePoint) || when (Character.getType(codePoint)) {
                    Character.NON_SPACING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt() -> true
                    else -> false
                }
                val validApostrophe = (codePoint == '\''.code || codePoint == '\u2019'.code) &&
                    charIndex > 0 && charIndex + Character.charCount(codePoint) < token.length
                require(validLetterOrMark || validApostrophe) {
                    "Vietnamese phrase model token contains an invalid character"
                }
                charIndex += Character.charCount(codePoint)
            }
            return token
        }

        private fun lookupToken(rawToken: String): String =
            displayToken(rawToken).lowercase(Locale.ROOT)

        private fun safeLookupToken(rawToken: String): String? =
            runCatching { lookupToken(rawToken) }.getOrNull()

        private data class ParsedEntry(
            val order: Int,
            val score: Int,
            val normalizedContext: String,
            val candidate: String,
        )
    }
}

/** Verifies and lazily loads the immutable phrase model from APK assets. */
object VietnamesePhraseModelAssets {
    private const val PHRASE_MODEL_ASSET = "dicts/vi_phrase_model_v1.tsv"
    private const val MANIFEST_ASSET = "manifests/phrase_model_vi.json"
    private const val MAX_RUNTIME_ARTIFACT_BYTES = 64 * 1024

    private data class LoadResult(val model: VietnamesePhraseModelData?)

    private val cache = Collections.synchronizedMap(WeakHashMap<AssetManager, LoadResult>())

    /** Returns null outside Vietnamese and if the sealed asset is malformed or incomplete. */
    @JvmStatic
    fun load(context: Context, locale: Locale): VietnamesePhraseModelData? {
        if (!locale.language.equals("vi", ignoreCase = true)) return null
        // Keep this phrase layer behind the same gate as main_vi.dict. A valid small seed must
        // never mask a missing/corrupt primary Vietnamese dictionary.
        if (!VietnameseDictionaryAssets.isBundledMainDictionaryValid(context, locale)) return null
        val assets = context.assets
        cache[assets]?.let { return it.model }
        synchronized(cache) {
            cache[assets]?.let { return it.model }
            val model = runCatching {
                val manifest = assets.open(MANIFEST_ASSET).bufferedReader(Charsets.UTF_8).use {
                    VietnamesePhraseModelManifest.parse(it.readText())
                }
                val serializedModel = assets.open(PHRASE_MODEL_ASSET).use { input ->
                    input.readBounded(manifest.byteCount, MAX_RUNTIME_ARTIFACT_BYTES)
                }
                require(VietnamesePhraseModelVerifier.matches(manifest, serializedModel)) {
                    "Vietnamese phrase model checksum does not match manifest"
                }
                VietnamesePhraseModelData.parse(String(serializedModel, Charsets.UTF_8), manifest)
            }.getOrNull()
            cache[assets] = LoadResult(model)
            return model
        }
    }

    private fun InputStream.readBounded(expectedByteCount: Long, hardLimit: Int): ByteArray {
        require(expectedByteCount <= hardLimit) { "Vietnamese phrase model exceeds runtime size limit" }
        val output = ByteArrayOutputStream(expectedByteCount.toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= expectedByteCount && total <= hardLimit) {
                "Vietnamese phrase model exceeds manifest size"
            }
            output.write(buffer, 0, count)
        }
        require(total == expectedByteCount) { "Vietnamese phrase model size does not match manifest" }
        return output.toByteArray()
    }
}

/** JVM-only hash check shared by runtime loading and tamper tests. */
internal object VietnamesePhraseModelVerifier {
    fun matches(manifest: VietnamesePhraseModelManifest, serializedModel: ByteArray): Boolean {
        if (serializedModel.size.toLong() != manifest.byteCount) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(serializedModel)
        return MessageDigest.isEqual(digest, manifest.sha256.hexToBytes())
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
