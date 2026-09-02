// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/**
 * Cooperative cancellation for work that may copy or hash a large offline engine bundle.
 *
 * The callback is deliberately small and Android-free so the installer can be used from an IME
 * lifecycle owner as well as in local tests. It is checked before every file and buffer chunk;
 * APK asset reads themselves cannot be interrupted safely, so cancellation takes effect at the
 * next chunk boundary. A cancelled install never promotes its staging directory.
 */
fun interface EngineBundleCancellation {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE = EngineBundleCancellation { false }
    }
}

/** Thrown only to stop cooperative background installation; callers must not show it as an error. */
class EngineBundleInstallationCancelledException : IOException("CJK engine installation was cancelled")

/**
 * Installs immutable IME engine data from APK assets into app-private storage.
 *
 * Offline composition data sets are deliberately installed lazily: opening an English or
 * Vietnamese keyboard must not unpack large CJK data. Each file is verified before the staging
 * directory is promoted, so an interrupted first launch can never leave a partially usable
 * engine directory.
 */
object EngineBundleInstaller {
    const val MANIFEST_SCHEMA_VERSION = 1
    private const val MAX_MANIFEST_BYTES = 512 * 1024
    private const val MAX_FILE_BYTES = 128L * 1024L * 1024L
    // A complete Rime/Mozc payload is expected to be roughly 60–100 MiB. Keep a defensive
    // ceiling above that without permitting an APK asset to consume most app-private storage.
    private const val MAX_BUNDLE_BYTES = 256L * 1024L * 1024L
    private const val MAX_BUNDLE_FILES = 2_048
    private const val INSTALLED_MARKER = ".installed.json"

    /** One signed APK asset that belongs to an offline engine bundle. */
    data class EngineBundleFile(
        val assetPath: String,
        val relativePath: String,
        val byteCount: Long,
        val sha256: String,
    )

    /**
     * Verified metadata for an APK-bundled composition engine.
     *
     * All binary/data files are listed individually.  The APK signature protects the manifest
     * itself; [fingerprint] additionally forces a reinstall if provenance changes without a
     * version bump.  No network URL is ever used as a download location.
     */
    data class EngineBundleManifest(
        val schemaVersion: Int,
        val engine: String,
        val version: String,
        /** Immutable upstream ref object requested for this release (a tag object is allowed). */
        val sourceCommit: String,
        /** The concrete Git commit tree used to produce the native/data payload. */
        val sourceCheckoutCommit: String,
        val abi: String,
        val source: String,
        val license: String,
        val totalBytes: Long,
        val files: List<EngineBundleFile>,
    ) {
        val fingerprint: String by lazy {
            val contents = buildString {
                append(schemaVersion).append('|').append(engine).append('|').append(version).append('|')
                append(sourceCommit).append('|').append(sourceCheckoutCommit).append('|')
                append(abi).append('|').append(source).append('|')
                append(license).append('|').append(totalBytes)
                files.forEach { file ->
                    append('|').append(file.assetPath).append('|').append(file.relativePath)
                        .append('|').append(file.byteCount).append('|').append(file.sha256)
                }
            }
            EngineBundleInstaller.sha256(contents.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Reads [manifestAssetPath], installs it below `files/cjk/<id>/<version>`, and returns that
     * verified directory. Call this from an IO dispatcher, never on the IME main thread.
     */
    @Synchronized
    @Throws(IOException::class)
    fun install(context: Context, manifestAssetPath: String): File =
        install(context, manifestAssetPath, EngineBundleCancellation.NONE)

    /**
     * Same as [install], with cooperative cancellation for an IME view that was hidden, changed
     * subtype, or received memory pressure while its optional CJK assets were being prepared.
     */
    @Synchronized
    @Throws(IOException::class)
    fun install(
        context: Context,
        manifestAssetPath: String,
        cancellation: EngineBundleCancellation,
    ): File {
        throwIfCancellationRequested(cancellation)
        val normalizedManifestAssetPath = manifestAssetPath.replace('\\', '/')
        validateAssetPath(normalizedManifestAssetPath, "manifest")
        val manifest = parseManifest(
            JSONObject(readBoundedAssetText(context, normalizedManifestAssetPath, cancellation)),
        )
        throwIfCancellationRequested(cancellation)
        val root = File(context.filesDir, "cjk").canonicalFile
        val bundleRoot = containedFile(root, "${manifest.engine}/${manifest.version}")
        if (isInstalled(bundleRoot, manifest, cancellation)) return bundleRoot

        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Could not create CJK data directory")
        }
        val staging = containedFile(root, ".${manifest.engine}-${manifest.version}-${UUID.randomUUID()}.staging")
        if (!staging.mkdirs()) throw IOException("Could not create CJK staging directory")
        try {
            manifest.files.forEach { file ->
                throwIfCancellationRequested(cancellation)
                val output = containedFile(staging, file.relativePath)
                val parent = output.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                    throw IOException("Could not create CJK asset directory")
                }
                copyVerifiedAsset(context, file, output, cancellation)
            }
            throwIfCancellationRequested(cancellation)
            writeMarker(staging, manifest)
            if (!isInstalled(staging, manifest, cancellation)) {
                throw IOException("CJK staging verification failed")
            }
            throwIfCancellationRequested(cancellation)
            replaceAtomically(bundleRoot, staging, root, cancellation)
            return bundleRoot
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun parseManifest(json: JSONObject): EngineBundleManifest {
        val schemaVersion = json.optInt("schema", -1)
        if (schemaVersion != MANIFEST_SCHEMA_VERSION) {
            throw IOException("Unsupported CJK manifest schema $schemaVersion")
        }
        val engine = json.requireSafeSegment("engine")
        val version = json.requireSafeSegment("version")
        val sourceCommit = json.optString("commit", "").lowercase()
        if (!sourceCommit.matches(Regex("[0-9a-f]{7,64}"))) {
            throw IOException("CJK manifest has invalid commit")
        }
        val sourceCheckoutCommit = json.optString("checkoutCommit", "").lowercase()
        if (!sourceCheckoutCommit.matches(Regex("[0-9a-f]{40}"))) {
            throw IOException("CJK manifest has invalid checkoutCommit")
        }
        val abi = json.optString("abi", "")
        if (abi != "arm64-v8a") throw IOException("CJK manifest ABI must be arm64-v8a")
        val source = json.optString("source", "")
        val sourceUri = runCatching { URI(source) }.getOrNull()
        if (sourceUri?.scheme != "https" || sourceUri.host.isNullOrBlank()) {
            throw IOException("CJK manifest has invalid source URL")
        }
        val license = json.optString("license", "").trim()
        if (license.isBlank() || license.length > 160) throw IOException("CJK manifest has invalid license")
        val filesJson = json.optJSONArray("files") ?: throw IOException("CJK manifest has no files")
        if (filesJson.length() !in 1..MAX_BUNDLE_FILES) throw IOException("CJK manifest has an invalid file list")

        var totalBytes = 0L
        val files = buildList(filesJson.length()) {
            for (index in 0 until filesJson.length()) {
                val fileJson = filesJson.optJSONObject(index)
                    ?: throw IOException("CJK manifest entry $index is invalid")
                val assetPath = fileJson.requireSafeRelativePath("asset")
                val relativePath = fileJson.requireSafeRelativePath("path")
                if (relativePath == INSTALLED_MARKER) {
                    throw IOException("CJK bundle file may not replace its install marker")
                }
                val byteCount = fileJson.optLong("bytes", -1L)
                if (byteCount !in 1..MAX_FILE_BYTES) {
                    throw IOException("CJK file $relativePath has invalid size $byteCount")
                }
                totalBytes += byteCount
                if (totalBytes > MAX_BUNDLE_BYTES) throw IOException("CJK bundle exceeds maximum size")
                val sha256 = fileJson.optString("sha256", "").lowercase()
                if (!sha256.matches(Regex("[0-9a-f]{64}"))) {
                    throw IOException("CJK file $relativePath has invalid SHA-256")
                }
                add(EngineBundleFile(assetPath, relativePath, byteCount, sha256))
            }
        }
        if (files.map { it.relativePath }.toSet().size != files.size) {
            throw IOException("CJK manifest has duplicate target paths")
        }
        val declaredTotal = json.optLong("totalBytes", -1L)
        if (declaredTotal != totalBytes) throw IOException("CJK manifest totalBytes does not match files")
        val manifest = EngineBundleManifest(
            schemaVersion = schemaVersion,
            engine = engine,
            version = version,
            sourceCommit = sourceCommit,
            sourceCheckoutCommit = sourceCheckoutCommit,
            abi = abi,
            source = source,
            license = license,
            totalBytes = totalBytes,
            files = files,
        )
        CjkEngineSourceLock.requireMatches(manifest)
        return manifest
    }

    private fun readBoundedAssetText(
        context: Context,
        assetPath: String,
        cancellation: EngineBundleCancellation,
    ): String = context.assets.open(assetPath).use { rawInput ->
        BufferedInputStream(rawInput).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    throwIfCancellationRequested(cancellation)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_MANIFEST_BYTES) {
                        throw IOException("CJK manifest exceeds maximum size")
                    }
                    output.write(buffer, 0, count)
                }
                throwIfCancellationRequested(cancellation)
                output.toString(Charsets.UTF_8.name())
            }
        }
    }

    private fun copyVerifiedAsset(
        context: Context,
        file: EngineBundleFile,
        output: File,
        cancellation: EngineBundleCancellation,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L
        try {
            context.assets.open(file.assetPath).use { rawInput ->
                BufferedInputStream(rawInput).use { input ->
                    BufferedOutputStream(output.outputStream()).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            throwIfCancellationRequested(cancellation)
                            val count = input.read(buffer)
                            if (count < 0) break
                            copiedBytes += count
                            if (copiedBytes > file.byteCount || copiedBytes > MAX_FILE_BYTES) {
                                throw IOException("CJK asset ${file.assetPath} exceeded declared size")
                            }
                            digest.update(buffer, 0, count)
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
            throwIfCancellationRequested(cancellation)
            val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (copiedBytes != file.byteCount || actualHash != file.sha256) {
                throw IOException("CJK asset ${file.assetPath} failed integrity verification")
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun isInstalled(
        directory: File,
        manifest: EngineBundleManifest,
        cancellation: EngineBundleCancellation,
    ): Boolean {
        throwIfCancellationRequested(cancellation)
        val marker = File(directory, INSTALLED_MARKER)
        if (!marker.isFile) return false
        return try {
            val markerJson = JSONObject(marker.readText(Charsets.UTF_8))
            if (markerJson.optInt("schema") != manifest.schemaVersion
                || markerJson.optString("engine") != manifest.engine
                || markerJson.optString("version") != manifest.version
                || markerJson.optString("fingerprint") != manifest.fingerprint
            ) {
                return false
            }
            manifest.files.all { file ->
                throwIfCancellationRequested(cancellation)
                val installed = containedFile(directory, file.relativePath)
                installed.isFile
                    && installed.length() == file.byteCount
                    && sha256(installed, cancellation) == file.sha256
            }
        } catch (cancelled: EngineBundleInstallationCancelledException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    private fun writeMarker(directory: File, manifest: EngineBundleManifest) {
        val marker = JSONObject().apply {
            put("schema", manifest.schemaVersion)
            put("engine", manifest.engine)
            put("version", manifest.version)
            put("fingerprint", manifest.fingerprint)
            put("files", JSONArray().apply {
                manifest.files.forEach { put(it.relativePath) }
            })
        }
        File(directory, INSTALLED_MARKER).writeText(marker.toString(), Charsets.UTF_8)
    }

    private fun replaceAtomically(
        target: File,
        staging: File,
        root: File,
        cancellation: EngineBundleCancellation,
    ) {
        val backup = containedFile(root, ".${target.name}-${UUID.randomUUID()}.backup")
        throwIfCancellationRequested(cancellation)
        val hadExistingTarget = target.exists()
        if (hadExistingTarget && !target.renameTo(backup)) {
            throw IOException("Could not stage existing CJK bundle for replacement")
        }
        try {
            throwIfCancellationRequested(cancellation)
            if (!staging.renameTo(target)) {
                throw IOException("Could not activate verified CJK bundle")
            }
        } catch (error: Throwable) {
            if (hadExistingTarget && !target.exists() && backup.exists() && !backup.renameTo(target)) {
                error.addSuppressed(IOException("Could not restore the previous CJK bundle"))
            }
            throw error
        }
        backup.deleteRecursively()
    }

    private fun containedFile(root: File, path: String): File {
        val file = File(root, path).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) {
            throw IOException("CJK path escapes its data directory")
        }
        return file
    }

    private fun JSONObject.requireSafeSegment(key: String): String {
        val value = optString(key, "")
        if (!value.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {
            throw IOException("CJK manifest has invalid $key")
        }
        return value
    }

    private fun JSONObject.requireSafeRelativePath(key: String): String {
        val value = optString(key, "").replace('\\', '/')
        validateAssetPath(value, key)
        return value
    }

    /** True when an APK asset path has no aliases, traversal, or platform-specific separators. */
    internal fun isSafeAssetPath(rawPath: String): Boolean = runCatching {
        validateAssetPath(rawPath.replace('\\', '/'), "asset")
    }.isSuccess

    @Throws(IOException::class)
    private fun validateAssetPath(rawPath: String, label: String) {
        val value = rawPath.replace('\\', '/')
        val segments = value.split('/')
        if (value.isBlank() || value.startsWith('/') || segments.any { segment ->
                !segment.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))
            }
        ) {
            throw IOException("CJK manifest has unsafe $label path")
        }
    }

    private fun sha256(file: File, cancellation: EngineBundleCancellation): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            throwIfCancellationRequested(cancellation)
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        throwIfCancellationRequested(cancellation)
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun throwIfCancellationRequested(cancellation: EngineBundleCancellation) {
        if (cancellation.isCancellationRequested()) throw EngineBundleInstallationCancelledException()
    }
}
