// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EngineBundleInstallerTest {
    @Test
    fun parsesBoundedVerifiedBundleManifest() {
        val manifest = EngineBundleInstaller.parseManifest(
            JSONObject(
                """
                {
                  "schema":1,
                  "engine":"rime",
                  "version":"1.16.1",
                  "commit":"5d7467d037938a17abb394f560f016adc9f76e14",
                  "checkoutCommit":"de4700e9f6b75b109910613df907965e3cbe0567",
                  "abi":"arm64-v8a",
                  "source":"https://github.com/rime/librime",
                  "license":"BSD-3-Clause",
                  "totalBytes":42,
                  "files":[{
                    "asset":"cjk/offline-pinyin/schema.bin",
                    "path":"shared/offline.schema.bin",
                    "bytes":42,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }]
                }
                """.trimIndent()
            )
        )

        assertEquals(1, manifest.schemaVersion)
        assertEquals("rime", manifest.engine)
        assertEquals("1.16.1", manifest.version)
        assertEquals("arm64-v8a", manifest.abi)
        assertEquals(42, manifest.totalBytes)
        assertEquals("shared/offline.schema.bin", manifest.files.single().relativePath)
    }

    @Test
    fun rejectsTraversalAndUnverifiedEntries() {
        assertFailsWith<IOException> {
            EngineBundleInstaller.parseManifest(
                JSONObject(
                    """{"schema":1,"engine":"rime","version":"1","commit":"5d7467d037938a17abb394f560f016adc9f76e14",
                    "checkoutCommit":"de4700e9f6b75b109910613df907965e3cbe0567",
                    "abi":"arm64-v8a","source":"https://github.com/rime/librime","license":"BSD-3-Clause","totalBytes":1,"files":[{
                    "asset":"cjk/offline-pinyin/a","path":"../escape","bytes":1,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    }]}"""
                )
            )
        }
        assertFailsWith<IOException> {
            EngineBundleInstaller.parseManifest(
                JSONObject(
                    """{"schema":1,"engine":"rime","version":"1","commit":"5d7467d037938a17abb394f560f016adc9f76e14",
                    "checkoutCommit":"de4700e9f6b75b109910613df907965e3cbe0567",
                    "abi":"arm64-v8a","source":"https://github.com/rime/librime","license":"BSD-3-Clause","totalBytes":1,"files":[{
                    "asset":"cjk/offline-pinyin/a","path":"safe","bytes":1,"sha256":"not-a-hash"
                    }]}"""
                )
            )
        }
        assertFailsWith<IOException> {
            EngineBundleInstaller.parseManifest(
                JSONObject(
                    """{"schema":1,"engine":"mozc","version":"1","commit":"851c3fe33060d2a6090363e4d7ec44fafde2c03d",
                    "checkoutCommit":"851c3fe33060d2a6090363e4d7ec44fafde2c03d",
                    "abi":"x86_64","source":"https://github.com/google/mozc","license":"BSD-3-Clause","totalBytes":1,"files":[{
                    "asset":"cjk/offline-kana/a","path":"safe","bytes":1,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}"""
                )
            )
        }
    }

    @Test
    fun acceptsOnlyLockedRimeAndMozcProvenance() {
        val rime = CjkEngineSourceLock.sourceFor("rime")
        val mozc = CjkEngineSourceLock.sourceFor("mozc")
        assertEquals("5d7467d037938a17abb394f560f016adc9f76e14", rime?.commit)
        assertEquals("de4700e9f6b75b109910613df907965e3cbe0567", rime?.checkoutCommit)
        assertEquals("851c3fe33060d2a6090363e4d7ec44fafde2c03d", mozc?.commit)

        val mozcManifest = EngineBundleInstaller.parseManifest(
            JSONObject(
                """
                {"schema":1,"engine":"mozc","version":"commit-851c3fe",
                "commit":"851c3fe33060d2a6090363e4d7ec44fafde2c03d","abi":"arm64-v8a",
                "checkoutCommit":"851c3fe33060d2a6090363e4d7ec44fafde2c03d",
                "source":"https://github.com/google/mozc","license":"BSD-3-Clause","totalBytes":1,
                "files":[{"asset":"cjk/mozc/data.bin","path":"data/data.bin","bytes":1,
                "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
                """.trimIndent(),
            ),
        )
        assertEquals("mozc", mozcManifest.engine)

        assertFailsWith<IOException> {
            EngineBundleInstaller.parseManifest(
                JSONObject(
                    """{"schema":1,"engine":"rime","version":"1.16.1","commit":"aaaaaaaa",
                    "checkoutCommit":"de4700e9f6b75b109910613df907965e3cbe0567",
                    "abi":"arm64-v8a","source":"https://github.com/rime/librime","license":"BSD-3-Clause","totalBytes":1,
                    "files":[{"asset":"cjk/rime/data.bin","path":"data/data.bin","bytes":1,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}""",
                ),
            )
        }
        assertFailsWith<IOException> {
            EngineBundleInstaller.parseManifest(
                JSONObject(
                    """{"schema":1,"engine":"rime","version":"1.16.1",
                    "commit":"5d7467d037938a17abb394f560f016adc9f76e14","abi":"arm64-v8a",
                    "checkoutCommit":"de4700e9f6b75b109910613df907965e3cbe0567",
                    "source":"https://example.invalid/rime","license":"BSD-3-Clause","totalBytes":1,
                    "files":[{"asset":"cjk/rime/data.bin","path":"data/data.bin","bytes":1,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}""",
                ),
            )
        }
        assertFailsWith<IOException> {
            EngineBundleInstaller.parseManifest(
                JSONObject(
                    """{"schema":1,"engine":"rime","version":"1.16.1",
                    "commit":"5d7467d037938a17abb394f560f016adc9f76e14",
                    "checkoutCommit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","abi":"arm64-v8a",
                    "source":"https://github.com/rime/librime","license":"BSD-3-Clause","totalBytes":1,
                    "files":[{"asset":"cjk/rime/data.bin","path":"data/data.bin","bytes":1,
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}""",
                ),
            )
        }
    }

    @Test
    fun rejectsAssetPathAliasesAndInstallMarkerReplacement() {
        listOf(".installed.json", "data//data.bin", "data/./data.bin", "data/../data.bin").forEach { path ->
            assertFailsWith<IOException> {
                EngineBundleInstaller.parseManifest(
                    JSONObject(
                        """{"schema":1,"engine":"rime","version":"1.16.1",
                        "commit":"5d7467d037938a17abb394f560f016adc9f76e14","abi":"arm64-v8a",
                        "checkoutCommit":"de4700e9f6b75b109910613df907965e3cbe0567",
                        "source":"https://github.com/rime/librime","license":"BSD-3-Clause","totalBytes":1,
                        "files":[{"asset":"cjk/rime/data.bin","path":"$path","bytes":1,
                        "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}""",
                    ),
                )
            }
        }
    }
}
