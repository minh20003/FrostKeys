// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.transferOldPinnedClips
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable backup metadata. Checksums and byte counts cover every content entry, while the ZIP
 * transport provides the normal per-entry CRC check. Clipboard, GIF/Klipy, media cache, raw
 * gesture and secret data are deliberately never entries. Learned-word dictionaries may only be
 * present as separately authenticated, password-encrypted entries.
 */
@Serializable
data class BackupManifestV2(
    val schema: Int,
    val appVersionCode: Int,
    val appVersionName: String,
    val createdAtEpochMillis: Long,
    val checksumAlgorithm: String,
    val entryCount: Int,
    val totalUncompressedBytes: Long,
    val entries: List<BackupManifestEntryV2>,
    val encryption: BackupEncryptionMetadataV2,
)

@Serializable
data class BackupManifestEntryV2(
    val path: String,
    val byteCount: Long,
    val sha256: String,
)

@Serializable
data class BackupEncryptionMetadataV2(
    val algorithm: String = "none",
    val encryptedEntries: List<String> = emptyList(),
    val learningDataIncluded: Boolean = false,
    val kdf: BackupKdfMetadataV2? = null,
    val encryptedEntryMetadata: List<BackupEncryptedEntryV2> = emptyList(),
)

/** Parameters are stored with the archive so password derivation remains reproducible. */
@Serializable
data class BackupKdfMetadataV2(
    val algorithm: String,
    val saltBase64: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val keyLengthBytes: Int,
)

/** Maps an encrypted ZIP payload to the only local learned-word file it may restore. */
@Serializable
data class BackupEncryptedEntryV2(
    val encryptedPath: String,
    val targetPath: String,
    val nonceBase64: String,
    val plaintextByteCount: Long,
    val plaintextSha256: String,
)

@Serializable
private data class BackupPreferencesV2(
    val booleans: Map<String, Boolean> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val stringSets: Map<String, Set<String>> = emptyMap(),
)

/**
 * Schema-v2 archive implementation. The complete archive is checked in a private staging
 * directory before any live preference or file is touched. File replacements are rolled back if
 * a later replacement or preference commit fails.
 */
internal object BackupArchiveV2 {
    private const val TAG = "BackupArchiveV2"
    private const val SCHEMA = 2
    private const val MANIFEST_FILE = "backup_manifest_v2.json"
    private const val PREFERENCES_FILE = "preferences_v2.json"
    private const val PROTECTED_PREFERENCES_FILE = "protected_preferences_v2.json"
    private const val FILES_PREFIX = "files/"
    private const val DEVICE_PROTECTED_PREFIX = "device_protected/"
    private const val LEARNING_PREFIX = "learning/"
    private const val STAGING_PREFIX = ".frostkeys-backup-v2-"
    // A restore can replace several files and both preference stores.  Keep an on-disk journal
    // until the replacement is fully committed so an interrupted process can restore the old
    // state on its next start instead of silently discarding the only rollback copies.
    private const val RESTORE_JOURNAL_PREFIX = ".frostkeys-backup-v2-restore-"
    private const val RESTORE_JOURNAL_FILE = "restore_journal.json"
    private const val RESTORE_JOURNAL_PUBLIC_PREFS_FILE = "public_preferences_before.json"
    private const val RESTORE_JOURNAL_PROTECTED_PREFS_FILE = "protected_preferences_before.json"
    private const val RESTORE_JOURNAL_COMMITTED_FILE = "committed"
    private const val RESTORE_JOURNAL_SCHEMA = 1

    private const val ENCRYPTION_ALGORITHM = "AES-256-GCM"
    private const val KDF_ALGORITHM = "Argon2id"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val ARGON2_SALT_BYTES = 16
    // 64 MiB / 3 iterations is intentionally expensive enough to resist offline guessing while
    // remaining usable on Android 12+ devices. Import accepts only these bounded parameters.
    private const val ARGON2_MEMORY_KIB = 64 * 1024
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_PARALLELISM = 1
    private const val ARGON2_KEY_BYTES = 32

    // These limits make a malformed archive fail before it can exhaust app storage. They are
    // deliberately below the clipboard/media limits because those data categories are excluded.
    private const val MAX_ENTRY_COUNT = 128
    private const val MAX_ENTRY_BYTES = 16L * 1024L * 1024L
    private const val MAX_ENCRYPTED_LEARNING_ENTRY_BYTES = MAX_ENTRY_BYTES + GCM_TAG_BYTES
    private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 256 * 1024L
    private const val MAX_PREFERENCES_BYTES = 1024 * 1024L
    private const val MAX_PREFERENCE_ITEMS = 2_000
    private const val MAX_PREFERENCE_STRING_LENGTH = 64 * 1024

    private val secureRandom = SecureRandom()
    /** Serializes direct callers as well as the settings screen's executor work. */
    private val operationLock = Any()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Throws(IOException::class)
    fun write(context: Context, output: OutputStream, password: CharArray? = null) {
        try {
            synchronized(operationLock) {
                recoverPendingRestoresLocked(context)
                writeLocked(context, output, password)
            }
        } finally {
            password?.fill('\u0000')
        }
    }

    /**
     * Restores only a validated v2 archive. Legacy archives did not have a manifest or bounds and
     * are intentionally rejected rather than mutating the live installation before validation.
     */
    @Throws(IOException::class)
    fun restore(context: Context, input: InputStream, password: CharArray? = null) {
        try {
            synchronized(operationLock) {
                recoverPendingRestoresLocked(context)
                restoreLocked(context, input, password)
            }
        } finally {
            password?.fill('\u0000')
        }
    }

    /**
     * Runs at application startup before normal settings/dictionaries are opened. A failure is
     * logged and the journal is retained for a later retry; it must not make the app unstartable.
     */
    fun recoverInterruptedRestores(context: Context) {
        synchronized(operationLock) {
            runCatching { recoverPendingRestoresLocked(context) }
                .onFailure { Log.w(TAG, "Could not recover an interrupted backup restore", it) }
        }
    }

    private fun writeLocked(context: Context, output: OutputStream, password: CharArray?) {
        var encryptedSources: EncryptedBackupSources? = null
        try {
            val sourceSet = buildSources(context)
            val encrypted = if (password != null && password.isNotEmpty()) {
                encryptLearningSources(context, sourceSet.learningSources, password)
            } else {
                EncryptedBackupSources.empty()
            }
            encryptedSources = encrypted
            val sources = (sourceSet.portableSources + encrypted.sources).sortedBy { it.path }
            validateSourceLimits(sources)
            val manifest = BackupManifestV2(
                schema = SCHEMA,
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
                createdAtEpochMillis = System.currentTimeMillis(),
                checksumAlgorithm = "SHA-256",
                entryCount = sources.size,
                totalUncompressedBytes = sources.sumOf { it.digest.byteCount },
                entries = sources.map {
                    BackupManifestEntryV2(it.path, it.digest.byteCount, it.digest.sha256)
                },
                encryption = encrypted.metadata,
            )
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                writeBytesEntry(zip, MANIFEST_FILE, json.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8), MAX_MANIFEST_BYTES)
                sources.forEach { source -> writeSourceEntry(zip, source) }
            }
        } finally {
            encryptedSources?.deleteTemporaryFiles()
        }
    }

    private fun restoreLocked(context: Context, input: InputStream, password: CharArray?) {
        val stage = createStageDirectory(context)
        try {
            val staged = stageArchive(context, input, stage, password)
            applyStagedRestore(context, staged)
        } finally {
            deleteOwnedStage(stage)
        }
    }

    /** Refreshes dependent caches only after [restore] has committed successfully. */
    fun refreshAfterRestore(context: Context) {
        // User history files may have been replaced atomically. Drop only in-memory handles so
        // the next suggestion session opens restored data; do not delete any restored file.
        runCatching { PersonalizationHelper.clearUserHistoryDictionaryCache() }
        runCatching { checkVersionUpgrade(context) }
        runCatching { transferOldPinnedClips(context) }
        runCatching { SubtypeSettings.reloadEnabledSubtypes(context) }
        runCatching {
            context.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
        }
        runCatching { LayoutUtilsCustom.onLayoutFileChanged() }
        runCatching { LayoutUtilsCustom.removeMissingLayouts(context) }
        runCatching { SupportedEmojis.load(context) }
        runCatching { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    }

    private fun buildSources(context: Context): BackupSourceSet {
        val sources = mutableListOf<BackupSource>()
        val preferences = encodePreferences(context.prefs().all)
        val protectedPreferences = encodePreferences(context.protectedPrefs().all)
        sources += sourceForBytes(PREFERENCES_FILE, preferences)
        sources += sourceForBytes(PROTECTED_PREFERENCES_FILE, protectedPreferences)
        collectPortableFiles(context.filesDir, FILES_PREFIX).forEach { sources += it }
        collectPortableFiles(DeviceProtectedUtils.getFilesDir(context), DEVICE_PROTECTED_PREFIX).forEach { sources += it }
        return BackupSourceSet(
            portableSources = sources.sortedBy { it.path },
            learningSources = collectLearningSources(context.filesDir),
        )
    }

    /** User history is private input-derived data, so it is never added to a plaintext archive. */
    private fun collectLearningSources(root: File): List<BackupSource> {
        if (!root.isDirectory) return emptyList()
        val canonicalRoot = root.canonicalFile
        return root.listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile || !isLearningFile(file.name)) return@mapNotNull null
            val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
            if (!isChildOf(canonicalRoot, canonicalFile)) return@mapNotNull null
            sourceForFile(FILES_PREFIX + canonicalFile.name, canonicalFile)
        }.sortedBy { it.path }
    }

    /**
     * Encrypts each learned-word dictionary to a private temporary ciphertext file. Plaintext is
     * streamed directly from the live dictionary and never written outside the app's files area.
     */
    private fun encryptLearningSources(
        context: Context,
        learningSources: List<BackupSource>,
        password: CharArray,
    ): EncryptedBackupSources {
        if (learningSources.isEmpty()) return EncryptedBackupSources.empty()
        val salt = ByteArray(ARGON2_SALT_BYTES).also(secureRandom::nextBytes)
        val kdf = BackupKdfMetadataV2(
            algorithm = KDF_ALGORITHM,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            memoryKiB = ARGON2_MEMORY_KIB,
            iterations = ARGON2_ITERATIONS,
            parallelism = ARGON2_PARALLELISM,
            keyLengthBytes = ARGON2_KEY_BYTES,
        )
        val key = deriveArgon2idKey(password, kdf)
        val created = mutableListOf<BackupSource>()
        val metadata = mutableListOf<BackupEncryptedEntryV2>()
        try {
            learningSources.forEach { source ->
                val sourceFile = source.file ?: throw BackupFormatException("Learning data source is invalid")
                val targetPath = source.path
                val relativeName = targetPath.removePrefix(FILES_PREFIX)
                if (!isLearningTargetArchivePath(targetPath) || relativeName == targetPath) {
                    throw BackupFormatException("Learning data source is invalid")
                }
                val encryptedPath = "$LEARNING_PREFIX$relativeName.enc"
                val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
                val entry = BackupEncryptedEntryV2(
                    encryptedPath = encryptedPath,
                    targetPath = targetPath,
                    nonceBase64 = Base64.getEncoder().encodeToString(nonce),
                    plaintextByteCount = source.digest.byteCount,
                    plaintextSha256 = source.digest.sha256,
                )
                val ciphertextFile = File.createTempFile(".frostkeys-backup-v2-learning-", ".enc", context.cacheDir)
                try {
                    encryptLearningFile(sourceFile, ciphertextFile, source.digest, key, entry)
                    created += sourceForFile(encryptedPath, ciphertextFile)
                    metadata += entry
                } catch (t: Throwable) {
                    ciphertextFile.delete()
                    throw t
                }
            }
            return EncryptedBackupSources(
                sources = created,
                metadata = BackupEncryptionMetadataV2(
                    algorithm = ENCRYPTION_ALGORITHM,
                    encryptedEntries = metadata.map { it.encryptedPath },
                    learningDataIncluded = true,
                    kdf = kdf,
                    encryptedEntryMetadata = metadata,
                ),
            )
        } catch (t: Throwable) {
            created.forEach { it.file?.delete() }
            throw t
        } finally {
            key.fill(0)
            salt.fill(0)
        }
    }

    private fun encryptLearningFile(
        source: File,
        destination: File,
        expected: DigestInfo,
        key: ByteArray,
        metadata: BackupEncryptedEntryV2,
    ) {
        val nonce = decodeBase64(metadata.nonceBase64, "backup nonce")
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(learningAssociatedData(metadata))
        }
        val actual = FileInputStream(source).buffered().use { input ->
            FileOutputStream(destination).buffered().use { output ->
                CipherOutputStream(output, cipher).use { encrypted ->
                    copyAndDigest(input, encrypted, MAX_ENTRY_BYTES)
                }
            }
        }
        if (actual != expected) {
            throw BackupFormatException("Learning data changed while the backup was being created")
        }
    }

    private fun collectPortableFiles(root: File, archivePrefix: String): List<BackupSource> {
        if (!root.isDirectory) return emptyList()
        val canonicalRoot = root.canonicalFile
        return root.walkTopDown().mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
            if (!isChildOf(canonicalRoot, canonicalFile)) return@mapNotNull null
            val relativePath = canonicalFile.path.removePrefix(canonicalRoot.path)
                .trimStart(File.separatorChar)
                .replace('\\', '/')
            if (!isPortableFile(relativePath)) return@mapNotNull null
            sourceForFile(archivePrefix + relativePath, canonicalFile)
        }.toList()
    }

    private fun validateSourceLimits(sources: List<BackupSource>) {
        if (sources.size !in 2..MAX_ENTRY_COUNT) {
            throw BackupFormatException("Backup has an invalid number of entries")
        }
        if (sources.map { it.path }.toSet().size != sources.size) {
            throw BackupFormatException("Backup contains duplicate entry names")
        }
        val total = sources.sumOf {
            if (it.digest.byteCount > maxBytesFor(it.path)) {
                throw BackupFormatException("A backup entry exceeds the size limit")
            }
            it.digest.byteCount
        }
        if (total > MAX_TOTAL_BYTES) throw BackupFormatException("Backup exceeds the total size limit")
    }

    private fun writeSourceEntry(zip: ZipOutputStream, source: BackupSource) {
        zip.putNextEntry(ZipEntry(source.path))
        try {
            val maximum = maxBytesFor(source.path)
            val actual = when {
                source.bytes != null -> copyAndDigest(source.bytes.inputStream(), zip, maximum)
                source.file != null -> FileInputStream(source.file).buffered().use {
                    copyAndDigest(it, zip, maximum)
                }
                else -> error("Backup source has no content")
            }
            if (actual != source.digest) {
                throw BackupFormatException("A file changed while the backup was being created")
            }
        } finally {
            zip.closeEntry()
        }
    }

    private fun writeBytesEntry(zip: ZipOutputStream, path: String, bytes: ByteArray, maximum: Long) {
        if (bytes.size.toLong() > maximum) throw BackupFormatException("Backup metadata exceeds the size limit")
        zip.putNextEntry(ZipEntry(path))
        try {
            zip.write(bytes)
        } finally {
            zip.closeEntry()
        }
    }

    private fun stageArchive(
        context: Context,
        input: InputStream,
        stage: File,
        password: CharArray?,
    ): StagedBackup {
        var parsedManifest: BackupManifestV2? = null
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            val manifestEntry = zip.nextEntry ?: throw BackupFormatException("Backup is empty")
            if (manifestEntry.isDirectory || manifestEntry.name != MANIFEST_FILE) {
                throw BackupFormatException("Backup v2 manifest must be the first entry")
            }
            val manifestBytes = readBoundedEntry(zip, MAX_MANIFEST_BYTES)
            zip.closeEntry()
            parsedManifest = try {
                json.decodeFromString(String(manifestBytes, StandardCharsets.UTF_8))
            } catch (e: Exception) {
                throw BackupFormatException("Backup manifest is invalid", e)
            }
            val manifest = parsedManifest ?: throw BackupFormatException("Backup manifest is missing")
            validateManifest(manifest)
            val expectedEntries = manifest.entries.associateBy { it.path }
            val seenEntries = mutableSetOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (entry.isDirectory) throw BackupFormatException("Backup contains a directory entry")
                    val expected = expectedEntries[entry.name]
                        ?: throw BackupFormatException("Backup contains an unapproved entry")
                    if (!seenEntries.add(entry.name)) throw BackupFormatException("Backup contains a duplicate entry")
                    val target = stageTarget(stage, entry.name)
                    target.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create staging directory")
                    }
                    val actual = FileOutputStream(target).buffered().use { output ->
                        copyAndDigest(zip, output, minOf(maxBytesFor(entry.name), expected.byteCount))
                    }
                    if (entry.size >= 0L && entry.size != actual.byteCount) {
                        throw BackupFormatException("Backup entry size does not match its ZIP header")
                    }
                    if (actual.byteCount != expected.byteCount || !actual.sha256.equals(expected.sha256, ignoreCase = true)) {
                        throw BackupFormatException("Backup entry checksum does not match the manifest")
                    }
                } finally {
                    zip.closeEntry()
                }
            }
            if (seenEntries != expectedEntries.keys) throw BackupFormatException("Backup is missing a declared entry")
        }
        val manifest = parsedManifest ?: throw BackupFormatException("Backup manifest is missing")
        val publicPreferences = decodePreferences(stageTarget(stage, PREFERENCES_FILE))
        val protectedPreferences = decodePreferences(stageTarget(stage, PROTECTED_PREFERENCES_FILE))
        val encryptedEntries = validateManifest(manifest)
        val restoredLearningPaths = encryptedEntries?.let {
            decryptLearningEntries(stage, it, password)
        }.orEmpty()
        val restoredPortablePaths = manifest.entries.asSequence()
            .map { it.path }
            .filter(::isPortableArchiveFilePath)
            .toList()
        return StagedBackup(
            stage = stage,
            manifest = manifest,
            publicPreferences = publicPreferences,
            protectedPreferences = protectedPreferences,
            restoredFilePaths = (restoredPortablePaths + restoredLearningPaths).sorted(),
        )
    }

    /** Returns encryption metadata only after every KDF/entry constraint is validated. */
    private fun validateManifest(manifest: BackupManifestV2): BackupEncryptionMetadataV2? {
        if (manifest.schema != SCHEMA) throw BackupFormatException("Unsupported backup schema")
        if (manifest.checksumAlgorithm != "SHA-256") throw BackupFormatException("Unsupported backup checksum")
        val encryption = validateEncryptionMetadata(manifest.encryption)
        if (manifest.entryCount !in 2..MAX_ENTRY_COUNT || manifest.entryCount != manifest.entries.size) {
            throw BackupFormatException("Backup manifest entry count is invalid")
        }
        if (manifest.totalUncompressedBytes < 0 || manifest.totalUncompressedBytes > MAX_TOTAL_BYTES) {
            throw BackupFormatException("Backup manifest size is invalid")
        }
        val paths = mutableSetOf<String>()
        var total = 0L
        manifest.entries.forEach { entry ->
            if (!paths.add(entry.path)) throw BackupFormatException("Backup manifest has duplicate paths")
            if (!isAllowedManifestPath(entry.path, encryption)) {
                throw BackupFormatException("Backup manifest contains an unapproved path")
            }
            if (entry.byteCount < 0 || entry.byteCount > maxBytesFor(entry.path)) {
                throw BackupFormatException("Backup manifest contains an oversized entry")
            }
            if (!entry.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                throw BackupFormatException("Backup manifest has an invalid checksum")
            }
            total = safeAdd(total, entry.byteCount)
        }
        if (total != manifest.totalUncompressedBytes) throw BackupFormatException("Backup manifest total is invalid")
        if (PREFERENCES_FILE !in paths || PROTECTED_PREFERENCES_FILE !in paths) {
            throw BackupFormatException("Backup is missing its settings entries")
        }
        encryption?.encryptedEntryMetadata?.forEach { encrypted ->
            if (encrypted.encryptedPath !in paths) {
                throw BackupFormatException("Backup is missing encrypted learning data")
            }
        }
        return encryption
    }

    private fun validateEncryptionMetadata(metadata: BackupEncryptionMetadataV2): BackupEncryptionMetadataV2? {
        if (metadata.algorithm == "none") {
            if (metadata.encryptedEntries.isNotEmpty() || metadata.learningDataIncluded ||
                metadata.kdf != null || metadata.encryptedEntryMetadata.isNotEmpty()
            ) {
                throw BackupFormatException("Unencrypted backup has invalid encryption metadata")
            }
            return null
        }
        if (metadata.algorithm != ENCRYPTION_ALGORITHM || !metadata.learningDataIncluded) {
            throw BackupFormatException("Unsupported backup encryption format")
        }
        val kdf = metadata.kdf ?: throw BackupFormatException("Encrypted backup is missing key derivation metadata")
        if (kdf.algorithm != KDF_ALGORITHM ||
            kdf.memoryKiB != ARGON2_MEMORY_KIB ||
            kdf.iterations != ARGON2_ITERATIONS ||
            kdf.parallelism != ARGON2_PARALLELISM ||
            kdf.keyLengthBytes != ARGON2_KEY_BYTES
        ) {
            throw BackupFormatException("Encrypted backup uses unsupported key derivation parameters")
        }
        val salt = decodeBase64(kdf.saltBase64, "backup salt")
        if (salt.size != ARGON2_SALT_BYTES) throw BackupFormatException("Encrypted backup has an invalid salt")

        val encrypted = metadata.encryptedEntryMetadata
        if (encrypted.isEmpty() || encrypted.size > MAX_ENTRY_COUNT ||
            metadata.encryptedEntries != encrypted.map { it.encryptedPath }
        ) {
            throw BackupFormatException("Encrypted backup has invalid learning data metadata")
        }
        val encryptedPaths = mutableSetOf<String>()
        val targetPaths = mutableSetOf<String>()
        encrypted.forEach { entry ->
            if (!encryptedPaths.add(entry.encryptedPath) || !targetPaths.add(entry.targetPath) ||
                !isEncryptedLearningArchivePath(entry.encryptedPath) ||
                !isLearningTargetArchivePath(entry.targetPath)
            ) {
                throw BackupFormatException("Encrypted backup has an unapproved learning data path")
            }
            if (entry.plaintextByteCount !in 0..MAX_ENTRY_BYTES ||
                !entry.plaintextSha256.matches(Regex("[0-9a-fA-F]{64}"))
            ) {
                throw BackupFormatException("Encrypted backup has invalid learning data integrity metadata")
            }
            val nonce = decodeBase64(entry.nonceBase64, "backup nonce")
            if (nonce.size != GCM_NONCE_BYTES) throw BackupFormatException("Encrypted backup has an invalid nonce")
        }
        return metadata
    }

    /**
     * Decrypt into the private restore stage only after every ZIP entry checksum has been checked.
     * Any password/tag/integrity failure happens before [applyStagedRestore] touches live state.
     */
    private fun decryptLearningEntries(
        stage: File,
        metadata: BackupEncryptionMetadataV2,
        password: CharArray?,
    ): List<String> {
        if (password == null || password.isEmpty()) {
            throw BackupFormatException("This backup includes learned words and needs its password")
        }
        val kdf = requireNotNull(metadata.kdf)
        val key = deriveArgon2idKey(password, kdf)
        try {
            metadata.encryptedEntryMetadata.forEach { encrypted ->
                val source = stageTarget(stage, encrypted.encryptedPath)
                if (!source.isFile) throw BackupFormatException("Encrypted learning data is missing")
                val target = stageTarget(stage, encrypted.targetPath)
                target.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create learning data staging directory")
                }
                decryptLearningFile(source, target, key, encrypted)
            }
            return metadata.encryptedEntryMetadata.map { it.targetPath }
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: Throwable) {
            // AES-GCM intentionally reports both a wrong password and a modified ciphertext as a
            // generic authentication failure. Do not reveal which condition occurred.
            throw BackupFormatException("Backup password is incorrect or learning data is corrupted", e)
        } finally {
            key.fill(0)
        }
    }

    private fun decryptLearningFile(
        source: File,
        target: File,
        key: ByteArray,
        metadata: BackupEncryptedEntryV2,
    ) {
        val nonce = decodeBase64(metadata.nonceBase64, "backup nonce")
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(learningAssociatedData(metadata))
        }
        val temporary = File(target.parentFile, ".${target.name}.backup-v2-${UUID.randomUUID()}.plain")
        try {
            val actual = FileInputStream(source).buffered().use { input ->
                CipherInputStream(input, cipher).buffered().use { decrypted ->
                    FileOutputStream(temporary).buffered().use { output ->
                        copyAndDigest(decrypted, output, MAX_ENTRY_BYTES)
                    }
                }
            }
            if (actual.byteCount != metadata.plaintextByteCount ||
                !actual.sha256.equals(metadata.plaintextSha256, ignoreCase = true)
            ) {
                throw BackupFormatException("Encrypted learning data integrity check failed")
            }
            if (!temporary.renameTo(target)) throw IOException("Unable to stage decrypted learning data")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun stageTarget(stage: File, archivePath: String): File {
        if (!isSafeArchivePath(archivePath)) throw BackupFormatException("Backup path is not allowed")
        val canonicalStage = stage.canonicalFile
        val target = File(stage, archivePath).canonicalFile
        if (!isChildOf(canonicalStage, target)) throw BackupFormatException("Backup path escapes staging")
        return target
    }

    private fun decodePreferences(file: File): BackupPreferencesV2 {
        if (!file.isFile || file.length() > MAX_PREFERENCES_BYTES) {
            throw BackupFormatException("Backup settings entry is invalid")
        }
        val preferences = try {
            json.decodeFromString<BackupPreferencesV2>(file.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw BackupFormatException("Backup settings are invalid", e)
        }
        validatePreferences(preferences)
        return preferences
    }

    private fun applyStagedRestore(context: Context, staged: StagedBackup) {
        val publicPrefs = context.prefs()
        val protectedPrefs = context.protectedPrefs()
        val publicBefore = portablePreferencesFrom(publicPrefs.all)
        val protectedBefore = portablePreferencesFrom(protectedPrefs.all)
        val plans = staged.restoredFilePaths
            .asSequence()
            .sorted()
            .map { archivePath ->
                val destination = liveDestination(context, archivePath)
                RestorePlanEntry(archivePath, destination, destination.exists())
            }
            .toList()
        if (plans.map { it.archivePath }.toSet().size != plans.size) {
            throw BackupFormatException("Backup has duplicate restore destinations")
        }
        val journal = createRestoreJournal(context, plans, publicBefore, protectedBefore)
        var liveStateMutationStarted = false
        var committed = false
        runCatching { Settings.getInstance().stopListener() }
        try {
            // Native user-history dictionaries retain file handles. Close and remove them before
            // replacing a restored dictionary, not only after restoration has succeeded.
            if (plans.any { isLearningTargetArchivePath(it.archivePath) }) {
                PersonalizationHelper.clearUserHistoryDictionaryCache()
            }
            plans.forEach { plan ->
                if (plan.destination.exists() != plan.destinationExisted) {
                    throw IOException("Restore target changed while the backup was being applied")
                }
                liveStateMutationStarted = true
                replaceFile(stageTarget(staged.stage, plan.archivePath), plan, journal)
            }
            liveStateMutationStarted = true
            replacePortablePreferences(publicPrefs, staged.publicPreferences)
            replacePortablePreferences(protectedPrefs, staged.protectedPreferences)

            // The marker is durable before any rollback copy is discarded. If the process dies
            // after this point, startup treats the restore as committed and only cleans debris.
            markJournalCommitted(journal)
            committed = true
            cleanupCommittedJournal(context, journal)
        } catch (t: Throwable) {
            if (!committed) {
                if (!liveStateMutationStarted) {
                    deleteOwnedJournal(context, journal.directory)
                } else {
                    val rollbackFailures = rollbackJournal(context, journal)
                    if (rollbackFailures.isEmpty()) {
                        deleteOwnedJournal(context, journal.directory)
                    } else {
                        rollbackFailures.forEach(t::addSuppressed)
                        Log.w(TAG, "Restore failed and rollback was incomplete; recovery journal was retained", t)
                    }
                }
            }
            throw t
        } finally {
            runCatching { Settings.getInstance().startListener() }
        }
    }

    /** Moves the old file into the journal's rollback location and deliberately leaves it there. */
    private fun replaceFile(source: File, plan: RestorePlanEntry, journal: RestoreJournalSession) {
        val destination = plan.destination
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create restore directory")
        }
        if (destination.exists() != plan.destinationExisted) {
            throw IOException("Restore target changed while the backup was being applied")
        }
        markJournalEntryStarted(journal, plan.archivePath)
        if (plan.destinationExisted) {
            val rollback = rollbackFile(journal, destination)
            if (rollback.exists()) throw IOException("A previous restore rollback file is still present")
            if (!destination.renameTo(rollback)) {
                throw IOException("Unable to stage existing file for restore")
            }
        }
        moveIntoPlace(source, destination)
    }

    private fun moveIntoPlace(source: File, destination: File) {
        if (source.renameTo(destination)) return
        val temporary = File(destination.parentFile, ".${destination.name}.backup-v2-${UUID.randomUUID()}.tmp")
        try {
            FileInputStream(source).buffered().use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    val copied = copyAndDigest(input, output, MAX_ENTRY_BYTES)
                    if (copied.byteCount != source.length()) throw IOException("Unable to copy staged restore file")
                }
            }
            if (!temporary.renameTo(destination)) throw IOException("Unable to atomically replace restored file")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Creates a durable, private recovery record before the first live file or preference changes. */
    private fun createRestoreJournal(
        context: Context,
        plans: List<RestorePlanEntry>,
        publicBefore: BackupPreferencesV2,
        protectedBefore: BackupPreferencesV2,
    ): RestoreJournalSession {
        val operationId = UUID.randomUUID().toString()
        val filesRoot = context.filesDir.canonicalFile
        val directory = File(filesRoot, RESTORE_JOURNAL_PREFIX + operationId).canonicalFile
        if (!isChildOf(filesRoot, directory) || directory.exists() || !directory.mkdirs()) {
            throw IOException("Unable to create restore recovery journal")
        }
        val publicSnapshot = json.encodeToString(publicBefore).toByteArray(StandardCharsets.UTF_8)
        val protectedSnapshot = json.encodeToString(protectedBefore).toByteArray(StandardCharsets.UTF_8)
        if (publicSnapshot.size > MAX_PREFERENCES_BYTES || protectedSnapshot.size > MAX_PREFERENCES_BYTES) {
            deleteOwnedJournal(context, directory)
            throw BackupFormatException("Current settings exceed the safe restore journal limit")
        }
        val journal = RestoreJournalV2(
            schema = RESTORE_JOURNAL_SCHEMA,
            operationId = operationId,
            entries = plans.map { RestoreJournalEntryV2(it.archivePath, it.destinationExisted) },
        )
        try {
            writeJournalFile(
                File(directory, RESTORE_JOURNAL_PUBLIC_PREFS_FILE),
                publicSnapshot,
            )
            writeJournalFile(
                File(directory, RESTORE_JOURNAL_PROTECTED_PREFS_FILE),
                protectedSnapshot,
            )
            // Write the metadata last: an orphaned partial directory is never considered a
            // recovery journal and is harmless because no live mutation started yet.
            writeJournalFile(
                File(directory, RESTORE_JOURNAL_FILE),
                json.encodeToString(journal).toByteArray(StandardCharsets.UTF_8),
            )
            return RestoreJournalSession(directory, journal, publicBefore, protectedBefore)
        } catch (t: Throwable) {
            deleteOwnedJournal(context, directory)
            throw t
        }
    }

    /** A marker is written before each target may change so recovery never removes an untouched file. */
    private fun markJournalEntryStarted(journal: RestoreJournalSession, archivePath: String) {
        val marker = journalEntryMarker(journal, archivePath)
        if (marker.exists()) throw IOException("Restore journal contains a duplicate target")
        writeJournalFile(marker, archivePath.toByteArray(StandardCharsets.UTF_8))
    }

    private fun markJournalCommitted(journal: RestoreJournalSession) {
        val marker = File(journal.directory, RESTORE_JOURNAL_COMMITTED_FILE)
        if (marker.exists()) throw IOException("Restore journal has an unexpected commit marker")
        writeJournalFile(marker, journal.journal.operationId.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Recover every incomplete journal before a new operation begins. A committed journal only
     * has cleanup left; an in-progress journal is rolled back from its own private snapshots.
     */
    @Throws(IOException::class)
    private fun recoverPendingRestoresLocked(context: Context) {
        val filesRoot = context.filesDir.canonicalFile
        filesRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.startsWith(RESTORE_JOURNAL_PREFIX) }
            .sortedBy { it.name }
            .forEach { directory ->
                // Metadata is written last, before any live mutation. A process death while
                // creating the journal can therefore leave only harmless snapshot files.
                if (!File(directory, RESTORE_JOURNAL_FILE).exists()) {
                    if (!deleteOwnedJournal(context, directory)) {
                        throw IOException("Unable to remove incomplete restore journal")
                    }
                    return@forEach
                }
                val journal = readRestoreJournal(context, directory)
                if (journal.committed) {
                    cleanupCommittedJournal(context, journal)
                    return@forEach
                }
                val failures = rollbackJournal(context, journal)
                if (failures.isNotEmpty()) {
                    val error = IOException("Unable to recover an interrupted backup restore")
                    failures.forEach(error::addSuppressed)
                    throw error
                }
                if (!deleteOwnedJournal(context, journal.directory)) {
                    throw IOException("Unable to remove recovered restore journal")
                }
            }
    }

    private fun readRestoreJournal(context: Context, directory: File): RestoreJournalSession {
        if (!isOwnedJournalDirectory(context, directory)) {
            throw BackupFormatException("Restore recovery journal is outside app storage")
        }
        val metadataFile = File(directory, RESTORE_JOURNAL_FILE)
        if (!metadataFile.isFile || metadataFile.length() !in 1..MAX_MANIFEST_BYTES) {
            throw BackupFormatException("Restore recovery journal is invalid")
        }
        val journal = try {
            json.decodeFromString<RestoreJournalV2>(metadataFile.readText(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw BackupFormatException("Restore recovery journal is invalid", e)
        }
        validateRestoreJournal(context, directory, journal)
        val publicBefore = decodePreferences(File(directory, RESTORE_JOURNAL_PUBLIC_PREFS_FILE))
        val protectedBefore = decodePreferences(File(directory, RESTORE_JOURNAL_PROTECTED_PREFS_FILE))
        val committedFile = File(directory, RESTORE_JOURNAL_COMMITTED_FILE)
        val committed = when {
            !committedFile.exists() -> false
            !committedFile.isFile || committedFile.length() !in 1..128L -> {
                throw BackupFormatException("Restore recovery journal has an invalid commit marker")
            }
            committedFile.readText(StandardCharsets.UTF_8) != journal.operationId -> {
                throw BackupFormatException("Restore recovery journal has an invalid commit marker")
            }
            else -> true
        }
        return RestoreJournalSession(directory, journal, publicBefore, protectedBefore, committed)
    }

    private fun validateRestoreJournal(context: Context, directory: File, journal: RestoreJournalV2) {
        if (journal.schema != RESTORE_JOURNAL_SCHEMA || runCatching { UUID.fromString(journal.operationId) }.isFailure ||
            directory.name != RESTORE_JOURNAL_PREFIX + journal.operationId
        ) {
            throw BackupFormatException("Restore recovery journal is invalid")
        }
        val paths = journal.entries.map { it.archivePath }
        if (paths.size > MAX_ENTRY_COUNT || paths.toSet().size != paths.size || paths.any {
                !isPortableArchiveFilePath(it) && !isLearningTargetArchivePath(it)
            }
        ) {
            throw BackupFormatException("Restore recovery journal has invalid targets")
        }
        journal.entries.forEachIndexed { index, entry ->
            // Validate every destination even if this particular entry had not started when the
            // process died. Recovery must never trust a journal path merely because its marker is
            // absent.
            liveDestination(context, entry.archivePath)
            val marker = journalEntryMarker(directory, index)
            if (!marker.exists()) return@forEachIndexed
            if (!marker.isFile || marker.length() !in 1..512L ||
                marker.readText(StandardCharsets.UTF_8) != entry.archivePath
            ) {
                throw BackupFormatException("Restore recovery journal has an invalid target marker")
            }
        }
    }

    /** Attempts every rollback action and deliberately keeps the journal when any one fails. */
    private fun rollbackJournal(context: Context, journal: RestoreJournalSession): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        if (journal.journal.entries.any { isLearningTargetArchivePath(it.archivePath) }) {
            runCatching { PersonalizationHelper.clearUserHistoryDictionaryCache() }
                .onFailure { failures += it }
        }
        runCatching { replacePortablePreferences(context.prefs(), journal.publicBefore) }
            .onFailure { failures += it }
        runCatching { replacePortablePreferences(context.protectedPrefs(), journal.protectedBefore) }
            .onFailure { failures += it }
        journal.journal.entries.asReversed().forEachIndexed { reversedIndex, entry ->
            val index = journal.journal.entries.lastIndex - reversedIndex
            runCatching {
                if (journalEntryStarted(journal.directory, index, entry.archivePath)) {
                    rollbackJournalFile(context, journal, entry)
                }
            }.onFailure { failures += it }
        }
        return failures
    }

    private fun rollbackJournalFile(
        context: Context,
        journal: RestoreJournalSession,
        entry: RestoreJournalEntryV2,
    ) {
        val destination = liveDestination(context, entry.archivePath)
        if (!entry.destinationExisted) {
            if (destination.exists() && !destination.delete()) {
                throw IOException("Unable to remove partially restored file")
            }
            return
        }
        val rollback = rollbackFile(journal, destination)
        if (!rollback.exists()) {
            // The marker is written immediately before the rename. If a process died in that
            // narrow interval, the original destination is still present and no action is needed.
            if (!destination.exists()) throw IOException("Restore rollback file is missing")
            return
        }
        if (destination.exists() && !destination.delete()) {
            throw IOException("Unable to remove partially restored file")
        }
        if (!rollback.renameTo(destination)) {
            throw IOException("Unable to restore original file")
        }
    }

    /** Committed state is safe; a cleanup failure only leaves a private retryable journal. */
    private fun cleanupCommittedJournal(context: Context, journal: RestoreJournalSession) {
        var cleanupFailed = false
        journal.journal.entries.forEach { entry ->
            if (!entry.destinationExisted) return@forEach
            val destination = runCatching { liveDestination(context, entry.archivePath) }.getOrNull()
            val rollback = destination?.let { rollbackFile(journal, it) }
            if (rollback != null && rollback.exists() && !rollback.delete()) cleanupFailed = true
        }
        if (cleanupFailed || !deleteOwnedJournal(context, journal.directory)) {
            Log.w(TAG, "Restore committed but its recovery journal could not be cleaned up")
        }
    }

    private fun writeJournalFile(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: throw IOException("Restore journal has no parent directory")
        val temporary = File(parent, ".${file.name}.backup-v2-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("Unable to atomically update restore journal")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun journalEntryMarker(journal: RestoreJournalSession, archivePath: String): File {
        val index = journal.journal.entries.indexOfFirst { it.archivePath == archivePath }
        if (index < 0) throw BackupFormatException("Restore journal target is invalid")
        return journalEntryMarker(journal.directory, index)
    }

    private fun journalEntryMarker(directory: File, index: Int): File =
        File(directory, "entry-$index.started")

    private fun journalEntryStarted(directory: File, index: Int, archivePath: String): Boolean {
        val marker = journalEntryMarker(directory, index)
        if (!marker.exists()) return false
        if (!marker.isFile || marker.length() !in 1..512L || marker.readText(StandardCharsets.UTF_8) != archivePath) {
            throw BackupFormatException("Restore recovery journal has an invalid target marker")
        }
        return true
    }

    private fun rollbackFile(journal: RestoreJournalSession, destination: File): File {
        val parent = destination.parentFile ?: throw IOException("Restore destination has no parent directory")
        return File(parent, ".${destination.name}.backup-v2-${journal.journal.operationId}.rollback")
    }

    private fun isOwnedJournalDirectory(context: Context, directory: File): Boolean = runCatching {
        val root = context.filesDir.canonicalFile
        val canonicalDirectory = directory.canonicalFile
        canonicalDirectory.isDirectory && canonicalDirectory.name.startsWith(RESTORE_JOURNAL_PREFIX) &&
            isChildOf(root, canonicalDirectory)
    }.getOrDefault(false)

    private fun deleteOwnedJournal(context: Context, directory: File): Boolean {
        if (!isOwnedJournalDirectory(context, directory)) return false
        return runCatching { directory.deleteRecursively() }.getOrDefault(false)
    }

    private fun liveDestination(context: Context, archivePath: String): File {
        val (root, relativePath) = when {
            archivePath.startsWith(FILES_PREFIX) -> context.filesDir to archivePath.removePrefix(FILES_PREFIX)
            archivePath.startsWith(DEVICE_PROTECTED_PREFIX) -> DeviceProtectedUtils.getFilesDir(context) to archivePath.removePrefix(DEVICE_PROTECTED_PREFIX)
            else -> throw BackupFormatException("Backup file destination is invalid")
        }
        if (!isRestorableFile(relativePath)) throw BackupFormatException("Backup file is not allowed")
        val canonicalRoot = root.canonicalFile
        val target = File(root, relativePath).canonicalFile
        if (!isChildOf(canonicalRoot, target)) throw BackupFormatException("Backup file escapes its destination")
        return target
    }

    private fun replacePortablePreferences(prefs: SharedPreferences, values: BackupPreferencesV2) {
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (isPortablePreference(key, value)) editor.remove(key)
        }
        values.booleans.forEach { editor.putBoolean(it.key, it.value) }
        values.ints.forEach { editor.putInt(it.key, it.value) }
        values.longs.forEach { editor.putLong(it.key, it.value) }
        values.floats.forEach { editor.putFloat(it.key, it.value) }
        values.strings.forEach { editor.putString(it.key, it.value) }
        values.stringSets.forEach { editor.putStringSet(it.key, it.value) }
        if (!editor.commit()) throw IOException("Unable to commit restored settings")
    }

    private fun encodePreferences(settings: Map<String?, Any?>): ByteArray =
        json.encodeToString(portablePreferencesFrom(settings)).toByteArray(StandardCharsets.UTF_8)

    private fun portablePreferencesFrom(settings: Map<String?, Any?>): BackupPreferencesV2 {
        val booleans = mutableMapOf<String, Boolean>()
        val ints = mutableMapOf<String, Int>()
        val longs = mutableMapOf<String, Long>()
        val floats = mutableMapOf<String, Float>()
        val strings = mutableMapOf<String, String>()
        val stringSets = mutableMapOf<String, Set<String>>()
        settings.forEach { (key, value) ->
            if (key !is String || !isPortablePreference(key, value)) return@forEach
            when (value) {
                is Boolean -> booleans[key] = value
                is Int -> ints[key] = value
                is Long -> longs[key] = value
                is Float -> floats[key] = value
                is String -> strings[key] = value
                is Set<*> -> {
                    val stringsOnly = value.filterIsInstance<String>().toSet()
                    if (stringsOnly.size == value.size) stringSets[key] = stringsOnly
                }
            }
        }
        return BackupPreferencesV2(booleans, ints, longs, floats, strings, stringSets)
    }

    private fun validatePreferences(values: BackupPreferencesV2) {
        val keys = values.booleans.keys + values.ints.keys + values.longs.keys + values.floats.keys + values.strings.keys + values.stringSets.keys
        if (keys.size > MAX_PREFERENCE_ITEMS || keys.toSet().size != keys.size) {
            throw BackupFormatException("Backup has invalid settings")
        }
        values.booleans.forEach { (key, value) -> requirePortablePreference(key, value) }
        values.ints.forEach { (key, value) -> requirePortablePreference(key, value) }
        values.longs.forEach { (key, value) -> requirePortablePreference(key, value) }
        values.floats.forEach { (key, value) -> requirePortablePreference(key, value) }
        values.strings.forEach { (key, value) -> requirePortablePreference(key, value) }
        values.stringSets.forEach { (key, value) -> requirePortablePreference(key, value) }
    }

    private fun requirePortablePreference(key: String, value: Any) {
        if (!isPortablePreference(key, value)) throw BackupFormatException("Backup contains a non-portable setting")
    }

    private fun isPortablePreference(key: String, value: Any?): Boolean {
        if (key.isBlank() || key.length > 256 || !isPortablePreferenceKey(key)) return false
        val normalizedKey = key.lowercase(Locale.ROOT)
        if (normalizedKey.contains("clipboard") || normalizedKey.contains("screenshot") ||
            normalizedKey.contains("cache") || normalizedKey.contains("device_id") ||
            normalizedKey.contains("install_id") || normalizedKey.contains("gesture_data") ||
            normalizedKey.contains("raw_gesture") || normalizedKey.contains("log") ||
            normalizedKey.endsWith("_uri") || normalizedKey == "uri"
        ) return false
        return when (value) {
            is String -> value.length <= MAX_PREFERENCE_STRING_LENGTH && !looksLikeUri(value)
            is Set<*> -> value.size <= MAX_PREFERENCE_ITEMS && value.all {
                it is String && it.length <= MAX_PREFERENCE_STRING_LENGTH && !looksLikeUri(it)
            }
            is Boolean, is Int, is Long, is Float -> true
            else -> false
        }
    }

    /**
     * Only known, user-facing settings are portable. This is intentionally an allowlist rather
     * than a growing denylist: new runtime state is private by default until deliberately reviewed
     * here. Clipboard/screenshot settings are rejected again above so a future prefix cannot
     * accidentally re-introduce their contents or tracking state.
     */
    private fun isPortablePreferenceKey(key: String): Boolean =
        key in PORTABLE_PREFERENCE_KEYS ||
            PORTABLE_PREFERENCE_PREFIXES.any { prefix -> key.startsWith(prefix) }

    private fun looksLikeUri(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return normalized.startsWith("content://") || normalized.startsWith("file://") ||
            normalized.startsWith("android.resource://")
    }

    private fun isAllowedManifestPath(path: String, encryption: BackupEncryptionMetadataV2?): Boolean {
        if (isAllowedArchivePath(path)) return true
        return encryption?.encryptedEntryMetadata?.any { it.encryptedPath == path } == true
    }

    private fun isAllowedArchivePath(path: String): Boolean {
        if (!isSafeArchivePath(path)) return false
        return path == PREFERENCES_FILE || path == PROTECTED_PREFERENCES_FILE ||
            (path.startsWith(FILES_PREFIX) && isPortableFile(path.removePrefix(FILES_PREFIX))) ||
            (path.startsWith(DEVICE_PROTECTED_PREFIX) && isPortableFile(path.removePrefix(DEVICE_PROTECTED_PREFIX)))
    }

    private fun isPortableArchiveFilePath(path: String): Boolean =
        (path.startsWith(FILES_PREFIX) && isPortableFile(path.removePrefix(FILES_PREFIX))) ||
            (path.startsWith(DEVICE_PROTECTED_PREFIX) && isPortableFile(path.removePrefix(DEVICE_PROTECTED_PREFIX)))

    private fun isSafeArchivePath(path: String): Boolean {
        if (path.isBlank() || path.length > 512 || path.startsWith('/') || path.contains('\\') || path.contains('\u0000')) return false
        return path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    }

    /** Only layout/theme/font customization files are portable without a password-protected payload. */
    private fun isPortableFile(relativePath: String): Boolean {
        if (!isSafeArchivePath(relativePath)) return false
        return relativePath.matches(Regex("blacklists/[^/]+\\.txt")) ||
            relativePath.matches(Regex("layouts/${Regex.escape(LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX)}[^/]*\\.[^/]{1,5}")) ||
            relativePath in setOf("custom_background_image", "custom_font", "custom_emoji_font")
    }

    private fun isRestorableFile(relativePath: String): Boolean =
        isPortableFile(relativePath) || isLearningFile(relativePath)

    private fun isLearningFile(relativePath: String): Boolean =
        relativePath.matches(Regex("UserHistoryDictionary(?:\\.[A-Za-z0-9-]+)?\\.dict"))

    private fun isLearningTargetArchivePath(path: String): Boolean =
        path.startsWith(FILES_PREFIX) && isLearningFile(path.removePrefix(FILES_PREFIX))

    private fun isEncryptedLearningArchivePath(path: String): Boolean =
        path.startsWith(LEARNING_PREFIX) &&
            path.endsWith(".enc") &&
            isLearningFile(path.removePrefix(LEARNING_PREFIX).removeSuffix(".enc"))

    private fun maxBytesFor(path: String): Long = when (path) {
        PREFERENCES_FILE, PROTECTED_PREFERENCES_FILE -> MAX_PREFERENCES_BYTES
        else -> if (isEncryptedLearningArchivePath(path)) MAX_ENCRYPTED_LEARNING_ENTRY_BYTES else MAX_ENTRY_BYTES
    }

    private fun readBoundedEntry(input: InputStream, maximum: Long): ByteArray {
        val output = ByteArrayOutputStream()
        copyAndDigest(input, output, maximum)
        return output.toByteArray()
    }

    private fun copyAndDigest(input: InputStream, output: OutputStream, maximum: Long): DigestInfo {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total = safeAdd(total, count.toLong())
            if (total > maximum) throw BackupFormatException("Backup entry exceeds the size limit")
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        return DigestInfo(total, digest.digest().toHex())
    }

    private fun deriveArgon2idKey(password: CharArray, kdf: BackupKdfMetadataV2): ByteArray {
        val passwordBytes = password.concatToString().toByteArray(StandardCharsets.UTF_8)
        try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(decodeBase64(kdf.saltBase64, "backup salt"))
                .withMemoryAsKB(kdf.memoryKiB)
                .withIterations(kdf.iterations)
                .withParallelism(kdf.parallelism)
                .build()
            return ByteArray(kdf.keyLengthBytes).also { key ->
                Argon2BytesGenerator().apply { init(parameters) }.generateBytes(passwordBytes, key)
            }
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: Throwable) {
            throw BackupFormatException("Unable to derive backup encryption key", e)
        } finally {
            passwordBytes.fill(0)
        }
    }

    /** AES-GCM authenticates the file identity and plaintext integrity metadata in addition to bytes. */
    private fun learningAssociatedData(metadata: BackupEncryptedEntryV2): ByteArray =
        listOf(
            "FrostKeysBackupV2",
            metadata.encryptedPath,
            metadata.targetPath,
            metadata.plaintextByteCount.toString(),
            metadata.plaintextSha256.lowercase(Locale.ROOT),
        ).joinToString("|").toByteArray(StandardCharsets.UTF_8)

    private fun decodeBase64(encoded: String, field: String): ByteArray = try {
        Base64.getDecoder().decode(encoded)
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException("Encrypted backup has an invalid $field", e)
    }

    private fun safeAdd(left: Long, right: Long): Long {
        if (right < 0 || left > Long.MAX_VALUE - right) throw BackupFormatException("Backup size overflow")
        return left + right
    }

    private fun createStageDirectory(context: Context): File {
        val stage = File(context.cacheDir, STAGING_PREFIX + UUID.randomUUID())
        if (!stage.mkdirs()) throw IOException("Unable to create restore staging directory")
        return stage
    }

    private fun deleteOwnedStage(stage: File) {
        if (stage.name.startsWith(STAGING_PREFIX)) runCatching { stage.deleteRecursively() }
    }

    private fun isChildOf(root: File, child: File): Boolean =
        child.path.startsWith(root.path + File.separator)

    private data class DigestInfo(val byteCount: Long, val sha256: String)

    private data class BackupSource(
        val path: String,
        val digest: DigestInfo,
        val bytes: ByteArray? = null,
        val file: File? = null,
    )

    private data class BackupSourceSet(
        val portableSources: List<BackupSource>,
        val learningSources: List<BackupSource>,
    )

    private data class EncryptedBackupSources(
        val sources: List<BackupSource>,
        val metadata: BackupEncryptionMetadataV2,
    ) {
        fun deleteTemporaryFiles() {
            sources.forEach { source -> source.file?.delete() }
        }

        companion object {
            fun empty() = EncryptedBackupSources(
                sources = emptyList(),
                metadata = BackupEncryptionMetadataV2(),
            )
        }
    }

    private fun sourceForBytes(path: String, bytes: ByteArray): BackupSource =
        BackupSource(path, DigestInfo(bytes.size.toLong(), bytes.sha256()), bytes = bytes)

    private fun sourceForFile(path: String, file: File): BackupSource =
        BackupSource(path, digestFile(file, maxBytesFor(path)), file = file)

    private data class StagedBackup(
        val stage: File,
        val manifest: BackupManifestV2,
        val publicPreferences: BackupPreferencesV2,
        val protectedPreferences: BackupPreferencesV2,
        val restoredFilePaths: List<String>,
    )

    private data class RestorePlanEntry(
        val archivePath: String,
        val destination: File,
        val destinationExisted: Boolean,
    )

    @Serializable
    private data class RestoreJournalV2(
        val schema: Int,
        val operationId: String,
        val entries: List<RestoreJournalEntryV2>,
    )

    @Serializable
    private data class RestoreJournalEntryV2(
        val archivePath: String,
        val destinationExisted: Boolean,
    )

    private data class RestoreJournalSession(
        val directory: File,
        val journal: RestoreJournalV2,
        val publicBefore: BackupPreferencesV2,
        val protectedBefore: BackupPreferencesV2,
        val committed: Boolean = false,
    )

    private class BackupFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private fun digestFile(file: File, maximum: Long): DigestInfo = FileInputStream(file).buffered().use {
        copyAndDigest(it, NullOutputStream, maximum)
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private val PORTABLE_PREFERENCE_KEYS = setOf(
        // Vietnamese-first application locale is intentionally a portable user choice.
        "app_locale",
        "app_locale_initialized",

        // Appearance and layout behavior.
        Settings.PREF_THEME_STYLE,
        Settings.PREF_ICON_STYLE,
        Settings.PREF_THEME_COLORS,
        Settings.PREF_THEME_COLORS_NIGHT,
        Settings.PREF_THEME_KEY_BORDERS,
        Settings.PREF_THEME_DAY_NIGHT,
        Settings.PREF_FROSTED_BLUR_RADIUS,
        Settings.PREF_FROSTED_KEY_TRANSPARENCY,
        Settings.PREF_FROSTED_COLOR_BLEND,
        Settings.PREF_FROSTED_SATURATION,
        Settings.PREF_FROSTED_BG_TRANSPARENCY,
        Settings.PREF_FROSTED_SPECIAL_VIBRANCY,
        Settings.PREF_FROSTED_ALPHABET_VIBRANCY,
        Settings.PREF_FROSTED_BLUR_RADIUS_NIGHT,
        Settings.PREF_FROSTED_KEY_TRANSPARENCY_NIGHT,
        Settings.PREF_FROSTED_COLOR_BLEND_NIGHT,
        Settings.PREF_FROSTED_SATURATION_NIGHT,
        Settings.PREF_FROSTED_BG_TRANSPARENCY_NIGHT,
        Settings.PREF_FROSTED_SPECIAL_VIBRANCY_NIGHT,
        Settings.PREF_FROSTED_ALPHABET_VIBRANCY_NIGHT,
        Settings.PREF_FROSTED_DUST_ENABLED,
        Settings.PREF_FROSTED_DUST_ALPHA,
        Settings.PREF_FROSTED_DUST_ALPHA_NIGHT,
        Settings.PREF_FROSTED_GLASS_TRIGGER,
        Settings.PREF_BLUR_RENDER_OVERRIDE,
        Settings.PREF_NATIVE_BACKGROUND_BLUR_ONLY,
        Settings.PREF_CUSTOM_ICON_NAMES,
        Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES,
        Settings.PREF_ENABLE_SPLIT_KEYBOARD,
        Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE,
        Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED,
        Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE,
        Settings.PREF_KEYBOARD_CORNER_RADIUS,
        Settings.PREF_FONT_SCALE,
        Settings.PREF_EMOJI_FONT_SCALE,
        Settings.PREF_EMOJI_KEY_FIT,
        Settings.PREF_EMOJI_SKIN_TONE,
        Settings.PREF_NAVBAR_COLOR,
        Settings.PREF_NARROW_KEY_GAPS,

        // Typing, suggestions, language and toolbar behavior.
        Settings.PREF_AUTO_CAP,
        Settings.PREF_VIBRATE_ON,
        Settings.PREF_VIBRATE_IN_DND_MODE,
        Settings.PREF_SOUND_ON,
        Settings.PREF_SUGGEST_EMOJIS,
        Settings.PREF_INLINE_EMOJI_SEARCH,
        Settings.PREF_SHOW_EMOJI_DESCRIPTIONS,
        Settings.PREF_PERSISTENT_EMOJI_ROW,
        Settings.PREF_POPUP_ON,
        Settings.PREF_AUTO_CORRECTION,
        Settings.PREF_MORE_AUTO_CORRECTION,
        Settings.PREF_AUTO_CORRECT_THRESHOLD,
        Settings.PREF_AUTOCORRECT_SHORTCUTS,
        Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT,
        Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER,
        Settings.PREF_SHOW_SUGGESTIONS,
        Settings.PREF_ALWAYS_SHOW_SUGGESTIONS,
        Settings.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT,
        Settings.PREF_KEY_USE_PERSONALIZED_DICTS,
        Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD,
        Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE,
        Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY,
        Settings.PREF_LANGUAGE_SWITCH_KEY,
        Settings.PREF_SHOW_EMOJI_KEY,
        Settings.PREF_VARIABLE_TOOLBAR_DIRECTION,
        Settings.PREF_ADDITIONAL_SUBTYPES,
        Settings.PREF_SPACE_HORIZONTAL_SWIPE,
        Settings.PREF_SPACE_VERTICAL_SWIPE,
        Settings.PREF_DELETE_SWIPE,
        Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION,
        Settings.PREF_AUTOSPACE_AFTER_SUGGESTION,
        Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING,
        Settings.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING,
        Settings.PREF_SHIFT_REMOVES_AUTOSPACE,
        Settings.PREF_ALWAYS_INCOGNITO_MODE,
        Settings.PREF_BIGRAM_PREDICTIONS,
        Settings.PREF_SUGGEST_PUNCTUATION,
        Settings.PREF_PUNCTUATION_SUGGESTIONS,
        Settings.PREF_GESTURE_INPUT,
        Settings.PREF_VIBRATION_DURATION_SETTINGS,
        Settings.PREF_KEYPRESS_SOUND_VOLUME,
        Settings.PREF_KEY_LONGPRESS_TIMEOUT,
        Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY,
        Settings.PREF_GESTURE_PREVIEW_TRAIL,
        Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT,
        Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC,
        Settings.PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM,
        Settings.PREF_GESTURE_SPACE_AWARE,
        Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN,
        Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION,
        Settings.PREF_SHOW_SETUP_WIZARD_ICON,
        Settings.PREF_USE_CONTACTS,
        Settings.PREF_USE_APPS,
        Settings.PREF_SHOW_NUMBER_ROW,
        Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS,
        Settings.PREF_LOCALIZED_NUMBER_ROW,
        Settings.PREF_SHOW_NUMBER_ROW_HINTS,
        Settings.PREF_CUSTOM_CURRENCY_KEY,
        Settings.PREF_SHOW_HINTS,
        Settings.PREF_SHOW_POPUP_HINTS,
        Settings.PREF_MORE_POPUP_KEYS,
        Settings.PREF_SHOW_TLD_POPUP_KEYS,
        Settings.PREF_SPACE_TO_CHANGE_LANG,
        Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD,
        Settings.PREF_LANGUAGE_SWIPE_DISTANCE,
        Settings.PREF_TOUCHPAD_SENSITIVITY,
        Settings.PREF_TOUCHPAD_EDGE_SCROLL,
        Settings.PREF_ADD_TO_PERSONAL_DICTIONARY,
        Settings.PREF_ENABLED_SUBTYPES,
        Settings.PREF_SELECTED_SUBTYPE,
        Settings.PREF_VIETNAMESE_TONE_PLACEMENT,
        Settings.PREF_URL_DETECTION,
        Settings.PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG,
        Settings.PREF_QUICK_PIN_TOOLBAR_KEYS,
        Settings.PREF_PINNED_TOOLBAR_KEYS,
        Settings.PREF_PERSISTENT_TOOLBAR_KEY,
        Settings.PREF_TOOLBAR_KEYS,
        Settings.PREF_AUTO_SHOW_TOOLBAR,
        Settings.PREF_AUTO_HIDE_TOOLBAR,
        Settings.PREF_ABC_AFTER_EMOJI,
        Settings.PREF_ABC_AFTER_CLIP,
        Settings.PREF_ABC_AFTER_SYMBOL_SPACE,
        Settings.PREF_ABC_AFTER_NUMPAD_SPACE,
        Settings.PREF_REMOVE_REDUNDANT_POPUPS,
        Settings.PREF_SPACE_BAR_TEXT,
        Settings.PREF_TIMESTAMP_FORMAT,
        Settings.PREF_TOOLBAR_MODE,
        Settings.PREF_TOOLBAR_HIDING_GLOBAL,
        Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE,
        Settings.PREF_SPELLCHECK_SUGGEST,
        Settings.PREF_SEND_GIFS_AS_STICKERS,
        Settings.PREF_AI_VISUAL_EFFECTS,
        Settings.PREF_USE_5_WORD_SUGGESTION_CHIPS,
        Settings.PREF_EMOJI_MAX_SDK,
        Settings.PREF_SAVE_SUBTYPE_PER_APP,
    )

    private val PORTABLE_PREFERENCE_PREFIXES = setOf(
        Settings.PREF_USER_COLORS_PREFIX,
        Settings.PREF_USER_ALL_COLORS_PREFIX,
        Settings.PREF_USER_MORE_COLORS_PREFIX,
        Settings.PREF_LAYOUT_PREFIX,
        Settings.PREF_SPLIT_SPACER_SCALE_PREFIX,
        Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX,
        Settings.PREF_BOTTOM_ROW_SCALE_PREFIX,
        Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX,
        Settings.PREF_SIDE_PADDING_SCALE_PREFIX,
        Settings.PREF_ONE_HANDED_MODE_PREFIX,
        Settings.PREF_ONE_HANDED_GRAVITY_PREFIX,
        Settings.PREF_ONE_HANDED_SCALE_PREFIX,
        Settings.PREF_POPUP_KEYS_ORDER,
        Settings.PREF_POPUP_KEYS_HINT_ORDER,
    )
}
