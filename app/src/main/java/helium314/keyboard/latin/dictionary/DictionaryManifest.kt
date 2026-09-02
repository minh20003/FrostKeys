// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Immutable provenance for an offline dictionary shipped in the APK. */
data class DictionaryManifest(
    val locale: String,
    val version: String,
    val source: String,
    val license: String,
    val sha256: String,
    val byteCount: Long,
    val formatVersion: Int,
) {
    init {
        require(locale.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*"))) { "Invalid dictionary locale" }
        require(version.isNotBlank() && source.isNotBlank() && license.isNotBlank()) {
            "Dictionary provenance is incomplete"
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid dictionary SHA-256" }
        require(byteCount > 0) { "Invalid dictionary byte count" }
        require(formatVersion > 0) { "Invalid dictionary format version" }
    }

    companion object {
        /** Kept JVM-only so provenance can be checked in ordinary unit tests and build tools. */
        fun parse(json: String): DictionaryManifest = Json.parseToJsonElement(json).jsonObject.let { objectJson ->
            DictionaryManifest(
                locale = objectJson.requiredString("locale"),
                version = objectJson.requiredString("version"),
                source = objectJson.requiredString("source"),
                license = objectJson.requiredString("license"),
                sha256 = objectJson.requiredString("sha256").lowercase(),
                byteCount = objectJson["byteCount"]?.jsonPrimitive?.longOrNull
                    ?: throw IllegalArgumentException("Dictionary manifest has invalid byteCount"),
                formatVersion = objectJson["formatVersion"]?.jsonPrimitive?.intOrNull
                    ?: throw IllegalArgumentException("Dictionary manifest has invalid formatVersion"),
            )
        }

        private fun JsonObject.requiredString(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("Dictionary manifest is missing $key")
    }
}
