// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Locks the privacy policy against a later partial Android backup/D2D exclusion. */
class DataExtractionRulesTest {
    @Test
    fun automaticBackupAndEveryExtractionDomainAreDisabled() {
        val manifest = findProjectFile("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.readText().contains("android:allowBackup=\"false\""))

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            findProjectFile("app/src/main/res/xml/data_extraction_rules.xml"),
        )
        val expectedDomains = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = document.getElementsByTagName(section)
            assertEquals(1, nodes.length, "Expected exactly one <$section> policy")
            val element = nodes.item(0) as org.w3c.dom.Element
            val actual = mutableSetOf<String>()
            val excludes = element.getElementsByTagName("exclude")
            for (index in 0 until excludes.length) {
                val exclude = excludes.item(index) as org.w3c.dom.Element
                assertEquals(".", exclude.getAttribute("path"), "All backup exclusions must cover their full domain")
                actual += exclude.getAttribute("domain")
            }
            assertEquals(expectedDomains, actual, "Incomplete <$section> privacy exclusions")
        }
    }

    @Test
    fun extractionRulesDoNotUseIncludeAllowlisting() {
        val text = findProjectFile("app/src/main/res/xml/data_extraction_rules.xml").readText()
        assertFalse(Regex("<include\\b").containsMatchIn(text))
    }

    private fun findProjectFile(relative: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val current = directory ?: return@repeat
            val candidate = File(current, relative)
            if (candidate.isFile) return candidate
            directory = current.parentFile
        }
        error("Could not locate $relative from ${System.getProperty("user.dir")}")
    }
}
