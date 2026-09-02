// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.io.IOException

/**
 * Immutable provenance accepted for native CJK engine bundles.
 *
 * This is deliberately code, rather than mutable app storage or a network catalogue: a manifest
 * may only activate code/data that was built from the exact upstream revision reviewed for this
 * APK.  OpenCC data, when it is eventually used for Rime traditional candidates, belongs in the
 * verified Rime bundle and must be listed and hashed in that bundle's manifest as well.
 */
object CjkEngineSourceLock {
    data class Source(
        val engine: String,
        val version: String?,
        /** Immutable revision recorded in an APK bundle manifest. */
        val commit: String,
        /** Actual Git commit whose tree is compiled into the native/data bundle. */
        val checkoutCommit: String,
        /** Git ref fetched by reproducible source-acquisition tooling. */
        val fetchRef: String,
        val manifestSource: String,
        val checkoutSource: String,
        val license: String,
    )

    private val sources = listOf(
        Source(
            engine = "rime",
            version = "1.16.1",
            // `5d7467d` is the annotated refs/tags/1.16.1 object, not a commit object. Record
            // it because it is the requested upstream revision, and also pin the commit tree it
            // dereferences to so a build cannot silently compile a different source snapshot.
            commit = "5d7467d037938a17abb394f560f016adc9f76e14",
            checkoutCommit = "de4700e9f6b75b109910613df907965e3cbe0567",
            fetchRef = "refs/tags/1.16.1",
            manifestSource = "https://github.com/rime/librime",
            checkoutSource = "https://github.com/rime/librime.git",
            license = "BSD-3-Clause",
        ),
        // Mozc does not provide a release label in the approved plan; its full commit is the
        // version authority.  A bundle may use a descriptive build version, but never another
        // source commit.
        Source(
            engine = "mozc",
            version = null,
            commit = "851c3fe33060d2a6090363e4d7ec44fafde2c03d",
            checkoutCommit = "851c3fe33060d2a6090363e4d7ec44fafde2c03d",
            fetchRef = "851c3fe33060d2a6090363e4d7ec44fafde2c03d",
            manifestSource = "https://github.com/google/mozc",
            checkoutSource = "https://github.com/google/mozc.git",
            license = "BSD-3-Clause",
        ),
    )

    private val sourcesByEngine = sources.associateBy(Source::engine)

    /** Stable source-lock snapshot for build tooling and tests. */
    fun all(): List<Source> = sources.toList()

    fun sourceFor(engine: String): Source? = sourcesByEngine[engine]

    /**
     * Rejects a manifest whose native/data payload is not tied to the exact reviewed source.
     *
     * The APK signature protects the manifest file, while this lock prevents a locally edited
     * manifest from silently claiming an arbitrary Rime/Mozc checkout.  Hashes for every payload
     * file are still verified separately by [EngineBundleInstaller].
     */
    @Throws(IOException::class)
    fun requireMatches(manifest: EngineBundleInstaller.EngineBundleManifest) {
        val expected = sourcesByEngine[manifest.engine]
            ?: throw IOException("Unsupported offline CJK engine '${manifest.engine}'")
        if (manifest.source != expected.manifestSource) {
            throw IOException("CJK manifest source does not match the locked ${manifest.engine} source")
        }
        if (manifest.sourceCommit != expected.commit) {
            throw IOException("CJK manifest commit does not match the locked ${manifest.engine} source")
        }
        if (manifest.sourceCheckoutCommit != expected.checkoutCommit) {
            throw IOException("CJK manifest checkout commit does not match the locked ${manifest.engine} source")
        }
        if (manifest.license != expected.license) {
            throw IOException("CJK manifest license does not match the locked ${manifest.engine} source")
        }
        val expectedVersion = expected.version
        if (expectedVersion != null && manifest.version != expectedVersion) {
            throw IOException("CJK manifest version does not match the locked ${manifest.engine} source")
        }
    }
}
