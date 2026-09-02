// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Keeps runtime provenance enforcement and the reproducible source-acquisition lock in sync. */
class CjkEngineSourceLockTest {
    @Test
    fun toolingSourceLockMatchesRuntimeSourceLock() {
        val file = findSourceLockFile()
        val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        assertEquals(1, json.getValue("schema").jsonPrimitive.int)

        val toolingSources: Map<String, JsonObject> = json.getValue("engines").jsonArray.associate { element ->
            val source = element.jsonObject
            source.getValue("id").jsonPrimitive.content to source
        }
        assertEquals(CjkEngineSourceLock.all().map { it.engine }.toSet(), toolingSources.keys)
        CjkEngineSourceLock.all().forEach { runtime ->
            val tooling = assertNotNull(toolingSources[runtime.engine])
            assertEquals(runtime.commit, tooling.getValue("commit").jsonPrimitive.content)
            assertEquals(runtime.checkoutCommit, tooling.getValue("checkoutCommit").jsonPrimitive.content)
            assertEquals(runtime.fetchRef, tooling.getValue("fetchRef").jsonPrimitive.content)
            assertEquals(runtime.checkoutSource, tooling.getValue("checkoutSource").jsonPrimitive.content)
            assertEquals(runtime.manifestSource, tooling.getValue("manifestSource").jsonPrimitive.content)
            assertEquals(runtime.license, tooling.getValue("license").jsonPrimitive.content)
            assertEquals("source-pinned-not-bundled", tooling.getValue("status").jsonPrimitive.content)
        }
    }

    private fun findSourceLockFile(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val currentDirectory = directory ?: return@repeat
            val candidate = File(currentDirectory, "tools/cjk/engine-sources.json")
            if (candidate.isFile) return candidate
            directory = currentDirectory.parentFile
        }
        error("Could not locate tools/cjk/engine-sources.json from ${System.getProperty("user.dir")}")
    }
}
