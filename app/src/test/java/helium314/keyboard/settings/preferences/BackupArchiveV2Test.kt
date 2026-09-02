package helium314.keyboard.settings.preferences

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BackupArchiveV2Test {
    private lateinit var context: App
    private lateinit var customFont: File
    private lateinit var learningDictionary: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        customFont = File(context.filesDir, "custom_font")
        learningDictionary = File(context.filesDir, "UserHistoryDictionary.vi.dict")
        customFont.deleteRecursively()
        learningDictionary.delete()
        context.filesDir.listFiles().orEmpty()
            .filter {
                it.name.startsWith(".frostkeys-backup-v2-restore-") ||
                    it.name.startsWith(".custom_font.backup-v2-")
            }
            .forEach { it.deleteRecursively() }
        context.prefs().edit().remove(TEST_PORTABLE_KEY).remove(CloudManager.PREF_GEMINI_API_KEY)
            .remove("klipy_recent_searches_gif")
            .remove("clipboard_last_screenshot_media_uri")
            .remove("future_runtime_state").apply()
    }

    @After fun tearDown() {
        customFont.deleteRecursively()
        learningDictionary.delete()
        context.filesDir.listFiles().orEmpty()
            .filter {
                it.name.startsWith(".frostkeys-backup-v2-restore-") ||
                    it.name.startsWith(".custom_font.backup-v2-")
            }
            .forEach { it.deleteRecursively() }
        context.prefs().edit().remove(TEST_PORTABLE_KEY).remove(CloudManager.PREF_GEMINI_API_KEY)
            .remove("klipy_recent_searches_gif")
            .remove("clipboard_last_screenshot_media_uri")
            .remove("future_runtime_state").apply()
    }

    @Test fun roundTripContainsManifestAndExcludesSecretsAndCaches() {
        context.prefs().edit {
            putString(TEST_PORTABLE_KEY, "backup-value")
            putString(CloudManager.PREF_GEMINI_API_KEY, "must-not-export")
            putString("klipy_recent_searches_gif", "must-not-export")
            putString("clipboard_last_screenshot_media_uri", "content://private/screenshot")
            putString("future_runtime_state", "must-not-export")
        }
        customFont.writeText("font-before", StandardCharsets.UTF_8)

        val archive = ByteArrayOutputStream().also { BackupArchiveV2.write(context, it) }.toByteArray()
        val entries = readZipEntries(archive)
        val manifest = Json.decodeFromString<BackupManifestV2>(entries.getValue("backup_manifest_v2.json").toString(StandardCharsets.UTF_8))
        val settingsJson = entries.getValue("preferences_v2.json").toString(StandardCharsets.UTF_8)

        assertEquals(2, manifest.schema)
        assertEquals(manifest.entryCount, manifest.entries.size)
        assertTrue(manifest.entries.any { it.path == "files/custom_font" })
        assertFalse(settingsJson.contains("must-not-export"))
        assertFalse(settingsJson.contains(CloudManager.PREF_GEMINI_API_KEY))
        assertFalse(settingsJson.contains("klipy_recent_searches_gif"))
        assertFalse(settingsJson.contains("clipboard_last_screenshot_media_uri"))
        assertFalse(settingsJson.contains("future_runtime_state"))

        context.prefs().edit {
            putString(TEST_PORTABLE_KEY, "changed-value")
            putString(CloudManager.PREF_GEMINI_API_KEY, "local-secret-must-survive")
        }
        customFont.writeText("font-after", StandardCharsets.UTF_8)
        BackupArchiveV2.restore(context, ByteArrayInputStream(archive))

        assertEquals("backup-value", context.prefs().getString(TEST_PORTABLE_KEY, null))
        assertEquals("local-secret-must-survive", context.prefs().getString(CloudManager.PREF_GEMINI_API_KEY, null))
        assertEquals("font-before", customFont.readText(StandardCharsets.UTF_8))
    }

    @Test fun traversalArchiveIsRejectedBeforeLiveStateChanges() {
        context.prefs().edit { putString(TEST_PORTABLE_KEY, "live-value") }
        customFont.writeText("live-font", StandardCharsets.UTF_8)
        val preferences = """{"booleans":{},"ints":{},"longs":{},"floats":{},"strings":{},"stringSets":{}}"""
            .toByteArray(StandardCharsets.UTF_8)
        val malicious = createArchive(
            "preferences_v2.json" to preferences,
            "protected_preferences_v2.json" to preferences,
            "files/../custom_font" to "overwrite".toByteArray(StandardCharsets.UTF_8),
        )

        assertFailsWith<IOException> {
            BackupArchiveV2.restore(context, ByteArrayInputStream(malicious))
        }

        assertEquals("live-value", context.prefs().getString(TEST_PORTABLE_KEY, null))
        assertEquals("live-font", customFont.readText(StandardCharsets.UTF_8))
    }

    @Test fun unknownOrClipboardPreferenceCannotBeImported() {
        context.prefs().edit { putString(TEST_PORTABLE_KEY, "live-value") }
        val rejectedPreferences = """{
            "booleans":{},"ints":{},"longs":{},"floats":{},
            "strings":{"future_runtime_state":"private","clipboard_last_screenshot_media_uri":"content://private"},
            "stringSets":{}
        }""".trimIndent().toByteArray(StandardCharsets.UTF_8)
        val archive = createArchive(
            "preferences_v2.json" to rejectedPreferences,
            "protected_preferences_v2.json" to emptyPreferences(),
        )

        assertFailsWith<IOException> {
            BackupArchiveV2.restore(context, ByteArrayInputStream(archive))
        }

        assertEquals("live-value", context.prefs().getString(TEST_PORTABLE_KEY, null))
    }

    @Test fun learningDictionaryRoundTripsOnlyWhenProtectedByPassword() {
        val learned = "học sinh công nghệ Việt Nam".toByteArray(StandardCharsets.UTF_8)
        learningDictionary.writeBytes(learned)
        val password = "một mật khẩu sao lưu mạnh".toCharArray()

        val archive = ByteArrayOutputStream().also { BackupArchiveV2.write(context, it, password) }.toByteArray()
        val entries = readZipEntries(archive)
        val manifest = Json.decodeFromString<BackupManifestV2>(
            entries.getValue("backup_manifest_v2.json").toString(StandardCharsets.UTF_8),
        )

        assertTrue(password.all { it == '\u0000' })
        assertTrue(manifest.encryption.learningDataIncluded)
        assertEquals("AES-256-GCM", manifest.encryption.algorithm)
        assertEquals("Argon2id", manifest.encryption.kdf?.algorithm)
        assertEquals(1, manifest.encryption.encryptedEntryMetadata.size)
        assertFalse(entries.containsKey("files/UserHistoryDictionary.vi.dict"))
        val encryptedPath = manifest.encryption.encryptedEntryMetadata.single().encryptedPath
        assertTrue(entries.containsKey(encryptedPath))
        assertFalse(entries.getValue(encryptedPath).contentEquals(learned))

        learningDictionary.writeText("current learned words", StandardCharsets.UTF_8)
        val restorePassword = "một mật khẩu sao lưu mạnh".toCharArray()
        BackupArchiveV2.restore(context, ByteArrayInputStream(archive), restorePassword)

        assertTrue(restorePassword.all { it == '\u0000' })
        assertTrue(learningDictionary.readBytes().contentEquals(learned))
    }

    @Test fun wrongLearningPasswordLeavesLiveDictionaryUntouched() {
        learningDictionary.writeText("archived learned words", StandardCharsets.UTF_8)
        val archive = ByteArrayOutputStream().also {
            BackupArchiveV2.write(context, it, "correct backup password".toCharArray())
        }.toByteArray()
        learningDictionary.writeText("live learned words", StandardCharsets.UTF_8)

        assertFailsWith<IOException> {
            BackupArchiveV2.restore(context, ByteArrayInputStream(archive), "wrong backup password".toCharArray())
        }

        assertEquals("live learned words", learningDictionary.readText(StandardCharsets.UTF_8))
    }

    @Test fun noPasswordLeavesLearningDataOutOfArchive() {
        learningDictionary.writeText("learned words", StandardCharsets.UTF_8)

        val archive = ByteArrayOutputStream().also { BackupArchiveV2.write(context, it) }.toByteArray()
        val entries = readZipEntries(archive)
        val manifest = Json.decodeFromString<BackupManifestV2>(
            entries.getValue("backup_manifest_v2.json").toString(StandardCharsets.UTF_8),
        )

        assertFalse(manifest.encryption.learningDataIncluded)
        assertEquals("none", manifest.encryption.algorithm)
        assertFalse(entries.keys.any { it.contains("UserHistoryDictionary") })
    }

    @Test fun interruptedRestoreJournalRestoresOldFilesAndSettingsOnStartup() {
        customFont.writeText("font-before", StandardCharsets.UTF_8)
        context.prefs().edit { putString(TEST_PORTABLE_KEY, "before") }
        val baseline = readZipEntries(
            ByteArrayOutputStream().also { BackupArchiveV2.write(context, it) }.toByteArray(),
        )
        val operationId = UUID.randomUUID().toString()
        val journal = createJournal(
            operationId = operationId,
            publicBefore = baseline.getValue("preferences_v2.json"),
            protectedBefore = baseline.getValue("protected_preferences_v2.json"),
            destinationExisted = true,
            committed = false,
        )
        val rollback = File(customFont.parentFile, ".${customFont.name}.backup-v2-$operationId.rollback")
        assertTrue(customFont.renameTo(rollback))
        customFont.writeText("font-after", StandardCharsets.UTF_8)
        context.prefs().edit { putString(TEST_PORTABLE_KEY, "after") }

        BackupArchiveV2.recoverInterruptedRestores(context)

        assertEquals("font-before", customFont.readText(StandardCharsets.UTF_8))
        assertEquals("before", context.prefs().getString(TEST_PORTABLE_KEY, null))
        assertFalse(rollback.exists())
        assertFalse(journal.exists())
    }

    @Test fun committedRestoreJournalKeepsNewFilesAndOnlyCleansRecoveryArtifacts() {
        customFont.writeText("font-after", StandardCharsets.UTF_8)
        context.prefs().edit { putString(TEST_PORTABLE_KEY, "after") }
        val baseline = readZipEntries(
            ByteArrayOutputStream().also { BackupArchiveV2.write(context, it) }.toByteArray(),
        )
        val operationId = UUID.randomUUID().toString()
        val journal = createJournal(
            operationId = operationId,
            publicBefore = baseline.getValue("preferences_v2.json"),
            protectedBefore = baseline.getValue("protected_preferences_v2.json"),
            destinationExisted = true,
            committed = true,
        )
        val rollback = File(customFont.parentFile, ".${customFont.name}.backup-v2-$operationId.rollback")
        rollback.writeText("font-before", StandardCharsets.UTF_8)

        BackupArchiveV2.recoverInterruptedRestores(context)

        assertEquals("font-after", customFont.readText(StandardCharsets.UTF_8))
        assertEquals("after", context.prefs().getString(TEST_PORTABLE_KEY, null))
        assertFalse(rollback.exists())
        assertFalse(journal.exists())
    }

    @Test fun failedJournalRollbackKeepsTheOriginalRollbackCopyForRetry() {
        customFont.writeText("font-before", StandardCharsets.UTF_8)
        context.prefs().edit { putString(TEST_PORTABLE_KEY, "before") }
        val baseline = readZipEntries(
            ByteArrayOutputStream().also { BackupArchiveV2.write(context, it) }.toByteArray(),
        )
        val operationId = UUID.randomUUID().toString()
        val journal = createJournal(
            operationId = operationId,
            publicBefore = baseline.getValue("preferences_v2.json"),
            protectedBefore = baseline.getValue("protected_preferences_v2.json"),
            destinationExisted = true,
            committed = false,
        )
        val rollback = File(customFont.parentFile, ".${customFont.name}.backup-v2-$operationId.rollback")
        assertTrue(customFont.renameTo(rollback))
        assertTrue(customFont.mkdir())
        File(customFont, "child").writeText("prevents delete", StandardCharsets.UTF_8)

        BackupArchiveV2.recoverInterruptedRestores(context)

        assertTrue(rollback.exists())
        assertTrue(journal.exists())
        assertTrue(customFont.isDirectory)
    }

    @Test fun backupAndRestoreOperationsAreSerializedAcrossCallers() {
        val validArchive = ByteArrayOutputStream().also { BackupArchiveV2.write(context, it) }.toByteArray()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val first = Thread {
            runCatching {
                BackupArchiveV2.write(context, BlockingOutputStream(firstStarted, releaseFirst))
            }.onFailure(failure::set)
        }
        val second = Thread {
            runCatching { BackupArchiveV2.restore(context, ByteArrayInputStream(validArchive)) }
                .onFailure(failure::set)
            secondCompleted.countDown()
        }
        first.start()
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        second.start()
        assertFalse(secondCompleted.await(250, TimeUnit.MILLISECONDS))

        releaseFirst.countDown()
        first.join(5_000)
        second.join(5_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(secondCompleted.await(0, TimeUnit.MILLISECONDS))
        failure.get()?.let { throw AssertionError("Serialized backup failed", it) }
    }

    private fun createJournal(
        operationId: String,
        publicBefore: ByteArray,
        protectedBefore: ByteArray,
        destinationExisted: Boolean,
        committed: Boolean,
    ): File {
        val journal = File(context.filesDir, ".frostkeys-backup-v2-restore-$operationId")
        assertTrue(journal.mkdir())
        File(journal, "public_preferences_before.json").writeBytes(publicBefore)
        File(journal, "protected_preferences_before.json").writeBytes(protectedBefore)
        File(journal, "restore_journal.json").writeText(
            """{"schema":1,"operationId":"$operationId","entries":[{"archivePath":"files/custom_font","destinationExisted":$destinationExisted}]}""",
            StandardCharsets.UTF_8,
        )
        File(journal, "entry-0.started").writeText("files/custom_font", StandardCharsets.UTF_8)
        if (committed) File(journal, "committed").writeText(operationId, StandardCharsets.UTF_8)
        return journal
    }

    private fun createArchive(vararg contents: Pair<String, ByteArray>): ByteArray {
        val manifest = BackupManifestV2(
            schema = 2,
            appVersionCode = 0,
            appVersionName = "test",
            createdAtEpochMillis = 0,
            checksumAlgorithm = "SHA-256",
            entryCount = contents.size,
            totalUncompressedBytes = contents.sumOf { it.second.size.toLong() },
            entries = contents.map { (path, bytes) ->
                BackupManifestEntryV2(path, bytes.size.toLong(), sha256(bytes))
            },
            encryption = BackupEncryptionMetadataV2("none", emptyList(), false),
        )
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("backup_manifest_v2.json"))
                zip.write(Json.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                contents.forEach { (path, value) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(value)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun emptyPreferences(): ByteArray =
        """{"booleans":{},"ints":{},"longs":{},"floats":{},"strings":{},"stringSets":{}}"""
            .toByteArray(StandardCharsets.UTF_8)

    private fun readZipEntries(archive: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class BlockingOutputStream(
        private val started: CountDownLatch,
        private val release: CountDownLatch,
    ) : java.io.OutputStream() {
        private val blocked = AtomicBoolean(false)

        override fun write(b: Int) = write(byteArrayOf(b.toByte()))

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (blocked.compareAndSet(false, true)) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release backup" }
            }
        }
    }

    private companion object {
        const val TEST_PORTABLE_KEY = "user_colors_backup_v2_test"
    }
}
