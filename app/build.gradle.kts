import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
}

// This is a personal, frozen fork. Keep release identity independent from the
// checkout history so locally built APKs are reproducible and update correctly.
val FROSTKEYS_VERSION_CODE = 3_000_001
val FROSTKEYS_VERSION_NAME = "3.0.0-vn.1"

/**
 * Debug/test builds retain the upstream asset catalogue. The personal release keeps only assets
 * reachable by English/Vietnamese, the still-supported Korean/Thai layouts, and generic keyboard
 * panels. Japanese/Chinese engine assets are never allowlisted from the checkout: a future
 * engine payload may only enter the APK through the separately verified generated source set,
 * never as a partial layout.
 *
 * Android's ignore-assets API matches file names rather than source-relative paths. Guard the
 * generated exclusions against a basename collision with an allowlisted asset so adding a future
 * asset cannot silently remove a required one.
 */
val personalReleaseAssetRoot = file("src/main/assets")
val personalReleaseAllowedLocaleAndLayoutAssets = setOf(
    "layouts/main/qwerty.txt",
    "layouts/main/korean.json",
    "layouts/main/korean_phonetic.json",
    "layouts/main/korean_sebeolsik_390.json",
    "layouts/main/korean_sebeolsik_final.json",
    "layouts/main/thai.json",
    "layouts/symbols/symbols.txt",
    "layouts/functional/functional_keys.json",
    "layouts/functional/functional_keys_tablet.json",
    "locale_key_texts/vi.txt",
    "locale_key_texts/ko.txt",
    "locale_key_texts/th.txt",
    "locale_key_texts/more_popups_all.txt",
    "locale_key_texts/more_popups_main.txt",
    "locale_key_texts/more_popups_more.txt",
)
val personalReleaseAllowedAssetBasenames = personalReleaseAllowedLocaleAndLayoutAssets
    .map { File(it).name }
    .toSet()
val personalReleaseIgnoredLocaleAndLayoutAssetNames = fileTree(personalReleaseAssetRoot) {
    include(
        "layouts/main/**",
        "layouts/symbols/**",
        "layouts/functional/**",
        "locale_key_texts/**",
        "bn-khipro.mim",
    )
}.files
    .map { it.relativeTo(personalReleaseAssetRoot).path.replace(File.separatorChar, '/') }
    .filterNot { it in personalReleaseAllowedLocaleAndLayoutAssets }
    .map { File(it).name }
    .sorted()
check(personalReleaseIgnoredLocaleAndLayoutAssetNames.none { it in personalReleaseAllowedAssetBasenames }) {
    "A personal release asset exclusion collides with an allowlisted asset basename. " +
        "Update the explicit release asset policy before building."
}

/** The VN release deliberately exposes only English and Vietnamese offline main dictionaries. */
val personalReleaseIgnoredAssetPatterns = listOf(
    "main_bg.dict",
    "main_bn.dict",
    "main_de.dict",
    "main_el.dict",
    "main_en-GB.dict",
    "main_es.dict",
    "main_fr.dict",
    "main_hu.dict",
    "main_it.dict",
    "main_nl.dict",
    "main_pl.dict",
    "main_pt-BR.dict",
    "main_pt-PT.dict",
    "main_ro.dict",
    "main_ru.dict",
    "main_sv.dict",
    "main_tr.dict",
    // This was only the legacy remote-dictionary catalog; the personal build never reads it.
    "dictionaries_in_dict_repo.csv",
) + personalReleaseIgnoredLocaleAndLayoutAssetNames

abstract class VerifyPersonalReleaseSigning : DefaultTask() {
    @get:Input
    abstract val signingConfigured: Property<Boolean>

    @get:Input
    abstract val configuredStorePath: Property<String>

    /** SHA-256 of the long-lived personal signing certificate, without separators. */
    @get:Input
    abstract val expectedCertificateSha256: Property<String>

    // Keep this internal so a missing key reaches the tailored error below instead of Gradle's
    // generic input-file validation. The task has no outputs and is deliberately rerun for every
    // release request, so the keystore file is not needed as an incremental-build input.
    @get:Internal
    abstract val configuredStoreFile: RegularFileProperty

    @TaskAction
    fun verify() {
        if (!signingConfigured.get()) {
            throw GradleException(
                "Release signing is required. Set FROSTKEYS_STORE_FILE, FROSTKEYS_STORE_PASSWORD, " +
                    "FROSTKEYS_KEY_ALIAS, FROSTKEYS_KEY_PASSWORD, and FROSTKEYS_CERT_SHA256 in the environment, Gradle properties, " +
                    "or ignored keystore.properties. See keystore.properties.example."
            )
        }
        check(configuredStoreFile.get().asFile.isFile) {
            "Configured FrostKeys signing keystore does not exist: ${configuredStorePath.get()}"
        }
        check(expectedCertificateSha256.get().matches(Regex("[0-9a-f]{64}"))) {
            "FROSTKEYS_CERT_SHA256 must be the 64-character SHA-256 fingerprint of the long-lived personal signing certificate."
        }
    }
}

/**
 * The upstream WebP AAR bundled four ABI variants whose ELF load segments were only 4 KiB
 * aligned. The checked-in replacement is built reproducibly from the pinned sources documented
 * in app/libs/WEBP_16K_BUILD.md; fail early if it is replaced or corrupted.
 */
abstract class VerifyVendoredWebpAar : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: RegularFileProperty

    @get:Input
    abstract val expectedSha256: Property<String>

    @TaskAction
    fun verify() {
        val file = artifact.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!actual.equals(expectedSha256.get(), ignoreCase = true)) {
            throw GradleException(
                "Vendored WebP AAR integrity check failed for ${file.absolutePath}. " +
                    "Expected ${expectedSha256.get()}, got $actual."
            )
        }
    }
}

/**
 * Produces the IME metadata from a non-resource template.
 *
 * Android reads subtype declarations before app code can run, so a Java/Kotlin runtime flag is
 * not sufficient to hide an optional native keyboard.  The template is generated into the only
 * `@xml/method` resource: optional CJK subtypes are physically absent from that XML unless their
 * verified payloads are explicitly enabled for this build.
 */
abstract class GenerateImeMethodXml : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val templateFile: RegularFileProperty

    @get:Input
    abstract val includeMozcSubtype: Property<Boolean>

    @get:Input
    abstract val includeRimeSubtype: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val template = templateFile.get().asFile.readText(Charsets.UTF_8)
        val mozcMarker = "<!-- FROSTKEYS_MOZC_SUBTYPE -->"
        val rimeMarker = "<!-- FROSTKEYS_RIME_SUBTYPE -->"
        check(template.countOccurrences(mozcMarker) == 1) {
            "IME metadata template must contain exactly one Mozc subtype marker"
        }
        check(template.countOccurrences(rimeMarker) == 1) {
            "IME metadata template must contain exactly one Rime subtype marker"
        }
        val mozcSubtype = if (includeMozcSubtype.get()) {
            """
                <!-- Generated only with a verified offline Mozc payload. -->
                <subtype android:icon="@drawable/ic_ime_switcher"
                    android:label="@string/subtype_japanese_mozc"
                    android:subtypeId="0x7c16f003"
                    android:imeSubtypeLocale="ja_JP"
                    android:languageTag="ja-JP"
                    android:imeSubtypeMode="keyboard"
                    android:imeSubtypeExtraValue="KeyboardLayoutSet=MAIN:qwerty,Mozc=1,AsciiCapable,EmojiCapable"
                    android:isAsciiCapable="true" />
            """.trimIndent()
        } else {
            ""
        }
        val rimeSubtype = if (includeRimeSubtype.get()) {
            """
                <!-- Generated only with a verified offline Rime/OpenCC payload. -->
                <subtype android:icon="@drawable/ic_ime_switcher"
                    android:label="@string/subtype_chinese_rime"
                    android:subtypeId="0x7c16f004"
                    android:imeSubtypeLocale="zh_CN"
                    android:languageTag="zh-CN"
                    android:imeSubtypeMode="keyboard"
                    android:imeSubtypeExtraValue="KeyboardLayoutSet=MAIN:qwerty,Rime=1,AsciiCapable,EmojiCapable"
                    android:isAsciiCapable="true" />
            """.trimIndent()
        } else {
            ""
        }
        val output = File(outputDirectory.get().asFile, "xml/method.xml")
        if (!output.parentFile.isDirectory && !output.parentFile.mkdirs()) {
            throw GradleException("Could not create generated IME metadata directory")
        }
        output.writeText(
            template.replace(mozcMarker, mozcSubtype).replace(rimeMarker, rimeSubtype),
            Charsets.UTF_8,
        )
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length, 1)
        .count { it == value }
}

/**
 * Verifies the native compatibility properties that are otherwise only visible in a built APK.
 *
 * This is deliberately a release-only finalizer: it extracts each packaged ARM64 shared object
 * and checks its APK signature, ELF machine/segment alignment, then asks the Android SDK's
 * zipalign to validate the APK with a 16 KiB page alignment. It never creates, signs, or
 * modifies an APK.
 */
abstract class VerifyReleaseNativeCompatibility : DefaultTask() {
    @get:Input
    abstract val apkOutputDirectory: Property<String>

    @get:Input
    abstract val androidSdkDirectory: Property<String>

    @get:Input
    abstract val ndkDirectory: Property<String>

    @get:Input
    abstract val expectedAbi: Property<String>

    @get:Input
    abstract val expectedCertificateSha256: Property<String>

    @TaskAction
    fun verify() {
        val apk = findReleaseApk()
        val apkSigner = findApkSigner()
            ?: throw GradleException(
                "Release verification requires the Android SDK apksigner tool. " +
                    "Install Android SDK Build-Tools or put apksigner on PATH. " +
                    "Configured SDK directory: '${androidSdkDirectory.get()}'."
            )
        val readElf = findReadElf()
            ?: throw GradleException(
                "Native release verification requires llvm-readelf or readelf. " +
                    "Install Android NDK 28.0.13004108 or put a compatible tool on PATH. " +
                    "Configured NDK directory: '${ndkDirectory.get()}'."
            )
        val zipAlign = findZipAlign()
            ?: throw GradleException(
                "Native release verification requires the Android SDK zipalign tool. " +
                    "Install Android SDK Build-Tools or put zipalign on PATH. " +
                    "Configured SDK directory: '${androidSdkDirectory.get()}'."
            )

        verifyApkSignature(apkSigner, apk)
        verifyZipAlignment(zipAlign, apk)
        verifyNativeLibraries(readElf, apk)
    }

    private fun findReleaseApk(): File {
        val directory = File(apkOutputDirectory.get())
        if (!directory.isDirectory) {
            throw GradleException(
                "Release APK output directory is missing: ${directory.absolutePath}. " +
                    "Run :app:assembleRelease before native verification."
            )
        }
        val apks = directory.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .toList()
        if (apks.size != 1) {
            throw GradleException(
                "Expected exactly one release APK below ${directory.absolutePath}, found ${apks.size}: " +
                    apks.joinToString { it.name }
            )
        }
        return apks.single()
    }

    private fun verifyZipAlignment(zipAlign: File, apk: File) {
        val output = runTool(
            zipAlign,
            listOf("-c", "-P", "16", "-v", "4", apk.absolutePath),
            "zipalign 16 KiB verification",
        )
        logger.info("zipalign verified ${apk.name}: $output")
    }

    private fun verifyNativeLibraries(readElf: File, apk: File) {
        val abi = expectedAbi.get()
        ZipFile(apk).use { archive ->
            val nativeEntries = archive.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .toList()
            val unexpectedAbis = nativeEntries.filter { !it.name.startsWith("lib/$abi/") }
            if (unexpectedAbis.isNotEmpty()) {
                throw GradleException(
                    "Release APK contains native libraries outside $abi: " +
                        unexpectedAbis.joinToString { it.name }
                )
            }
            if (nativeEntries.isEmpty()) {
                throw GradleException("Release APK contains no lib/$abi/*.so files to verify")
            }

            val extractionRoot = File(temporaryDir, "${apk.nameWithoutExtension}-$abi")
            if (!extractionRoot.isDirectory && !extractionRoot.mkdirs()) {
                throw GradleException("Could not create temporary native verification directory")
            }
            nativeEntries.forEach { entry ->
                val fileName = entry.name.removePrefix("lib/$abi/")
                if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\')) {
                    throw GradleException("Release APK has an unsafe native library path: ${entry.name}")
                }
                val extracted = File(extractionRoot, fileName)
                archive.getInputStream(entry).use { input ->
                    extracted.outputStream().use { output -> input.copyTo(output) }
                }
                verifyArm64Elf(readElf, extracted)
            }
        }
    }

    private fun verifyArm64Elf(readElf: File, library: File) {
        val header = runTool(readElf, listOf("-h", library.absolutePath), "ELF header verification")
        if (!header.contains("AArch64", ignoreCase = true)) {
            throw GradleException("${library.name} is not an ARM64 ELF shared object:\n$header")
        }

        val programHeaders = runTool(
            readElf,
            listOf("-lW", library.absolutePath),
            "ELF PT_LOAD alignment verification",
        )
        val loadLines = programHeaders.lineSequence()
            .filter { it.trimStart().startsWith("LOAD ") }
            .toList()
        if (loadLines.isEmpty()) {
            throw GradleException("${library.name} has no PT_LOAD program headers:\n$programHeaders")
        }
        loadLines.forEach { line ->
            val alignmentToken = line.trim().split(Regex("\\s+")).lastOrNull()
                ?: throw GradleException("Could not parse PT_LOAD alignment for ${library.name}: $line")
            val alignment = parseElfNumber(alignmentToken)
                ?: throw GradleException("Could not parse PT_LOAD alignment '$alignmentToken' for ${library.name}")
            if (alignment < REQUIRED_PAGE_ALIGNMENT || alignment % REQUIRED_PAGE_ALIGNMENT != 0L) {
                throw GradleException(
                    "${library.name} PT_LOAD alignment $alignmentToken is not compatible with 16 KiB pages. " +
                        "Rebuild with -Wl,-z,max-page-size=16384."
                )
            }
        }
    }

    private fun parseElfNumber(token: String): Long? = when {
        token.startsWith("0x", ignoreCase = true) -> token.substring(2).toLongOrNull(16)
        else -> token.toLongOrNull()
    }

    private fun findReadElf(): File? {
        val ndk = File(ndkDirectory.get())
        val candidates = buildList {
            if (ndk.isDirectory) {
                val prebuilt = File(ndk, "toolchains/llvm/prebuilt")
                prebuilt.listFiles { file -> file.isDirectory }?.forEach { host ->
                    executableNames("llvm-readelf").forEach { add(File(host, "bin/$it")) }
                    executableNames("readelf").forEach { add(File(host, "bin/$it")) }
                }
            }
        }
        return candidates.firstOrNull { it.isFile } ?: findOnPath("llvm-readelf", "readelf")
    }

    private fun findZipAlign(): File? {
        val sdk = File(androidSdkDirectory.get())
        val candidates = buildList {
            if (sdk.isDirectory) {
                File(sdk, "build-tools").listFiles { file -> file.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?.forEach { buildTools ->
                        executableNames("zipalign").forEach { add(File(buildTools, it)) }
                    }
            }
        }
        return candidates.firstOrNull { it.isFile } ?: findOnPath("zipalign")
    }

    private fun findApkSigner(): File? {
        val sdk = File(androidSdkDirectory.get())
        val candidates = buildList {
            if (sdk.isDirectory) {
                File(sdk, "build-tools").listFiles { file -> file.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?.forEach { buildTools ->
                        executableNames("apksigner").forEach { add(File(buildTools, it)) }
                    }
            }
        }
        return candidates.firstOrNull { it.isFile } ?: findOnPath("apksigner")
    }

    private fun verifyApkSignature(apkSigner: File, apk: File) {
        val output = runTool(
            apkSigner,
            listOf("verify", "--verbose", "--print-certs", "--min-sdk-version", "31", apk.absolutePath),
            "APK signature verification",
        )
        val certificateFingerprint = Regex(
            """Signer #1 certificate SHA-256 digest:\\s*([0-9A-Fa-f:]{64,})""",
        ).find(output)?.groupValues?.getOrNull(1)?.replace(":", "")?.lowercase()
            ?: throw GradleException("apksigner did not report signer #1's SHA-256 certificate fingerprint")
        val expected = expectedCertificateSha256.get().lowercase()
        if (certificateFingerprint != expected) {
            throw GradleException(
                "Release APK is signed with a different certificate. Expected SHA-256 $expected, " +
                    "but apksigner reported $certificateFingerprint. Keep the original personal key for updates."
            )
        }
        logger.info("apksigner verified ${apk.name}: $output")
    }

    private fun findOnPath(vararg baseNames: String): File? {
        val pathEntries = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
        pathEntries.forEach { directory ->
            baseNames.forEach { baseName ->
                executableNames(baseName).forEach { executableName ->
                    val executable = File(directory, executableName)
                    if (executable.isFile) return executable
                }
            }
        }
        return null
    }

    private fun executableNames(baseName: String): List<String> = if (
        System.getProperty("os.name").contains("Windows", ignoreCase = true)
    ) listOf("$baseName.exe", "$baseName.bat", "$baseName.cmd", baseName) else listOf(baseName)

    private fun runTool(tool: File, arguments: List<String>, label: String): String {
        val command = if (tool.extension.equals("bat", ignoreCase = true) ||
            tool.extension.equals("cmd", ignoreCase = true)
        ) {
            // Android Build Tools publish apksigner as a batch file on Windows.  ProcessBuilder
            // cannot execute a batch file directly, so ask the platform command interpreter to
            // run it while preserving each path/argument as one token.
            val shell = System.getenv("ComSpec") ?: "cmd.exe"
            val escaped = (listOf(tool.absolutePath) + arguments).joinToString(" ") {
                "\"${it.replace("\"", "\\\"")}\""
            }
            listOf(shell, "/d", "/s", "/c", escaped)
        } else {
            listOf(tool.absolutePath) + arguments
        }
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            throw GradleException("Could not start $label with ${tool.absolutePath}", error)
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException(
                "$label failed (exit $exitCode): ${command.joinToString(" ")}\n$output"
            )
        }
        return output
    }

    private companion object {
        const val REQUIRED_PAGE_ALIGNMENT = 16L * 1024L
    }
}

/**
 * Checks the parts of the personal release APK that are independent of its signature and ELF
 * layout.  Keeping this separate from [VerifyReleaseNativeCompatibility] makes a failed content
 * policy obvious while still allowing both checks to be attached to `packageRelease` as
 * finalizers (which avoids a packageRelease -> verifier -> packageRelease dependency cycle).
 *
 * Optional CJK native libraries are deliberately packaged once below `lib/<abi>/`; their large
 * data files remain in assets for first-use, hash-verified atomic installation. This verifier
 * rejects a regression that duplicates a native ELF below the extractable data tree.
 */
abstract class VerifyPersonalReleasePackageContents : DefaultTask() {
    @get:Input
    abstract val apkOutputDirectory: Property<String>

    @get:Input
    abstract val expectedAbi: Property<String>

    @get:Input
    abstract val requiredEntries: ListProperty<String>

    @get:Input
    abstract val forbiddenEntries: ListProperty<String>

    @get:Input
    abstract val requiredNoticeMarkers: ListProperty<String>

    @TaskAction
    fun verify() {
        val apk = findReleaseApk()
        ZipFile(apk).use { archive ->
            val entries = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
            val entryNames = entries.map { it.name }.toSet()

            val unsafePaths = entryNames.filter(::isUnsafeArchivePath)
            if (unsafePaths.isNotEmpty()) {
                throw GradleException(
                    "Release APK has unsafe archive path(s): ${unsafePaths.joinToString()}"
                )
            }

            val missingEntries = requiredEntries.get().filterNot(entryNames::contains)
            if (missingEntries.isNotEmpty()) {
                throw GradleException(
                    "Release APK is missing required offline assets/notices: " +
                        missingEntries.joinToString()
                )
            }

            val nativeEntries = entries.filter { entry ->
                entry.name.startsWith("lib/") && entry.name.endsWith(".so")
            }
            val unexpectedAbiEntries = nativeEntries.filterNot { entry ->
                entry.name.startsWith("lib/${expectedAbi.get()}/")
            }
            if (unexpectedAbiEntries.isNotEmpty()) {
                throw GradleException(
                    "Release APK contains non-${expectedAbi.get()} native library entries: " +
                        unexpectedAbiEntries.joinToString { it.name }
                )
            }
            if (nativeEntries.isEmpty()) {
                throw GradleException("Release APK contains no lib/${expectedAbi.get()}/*.so entries")
            }

            val sensitiveDataEntries = entryNames.filter(::isForbiddenPersonalDataPath)
            if (sensitiveDataEntries.isNotEmpty()) {
                throw GradleException(
                    "Release APK contains forbidden secret or personal-default data path(s): " +
                        sensitiveDataEntries.joinToString()
                )
            }

            val duplicateCjkNativeEntries = forbiddenEntries.get().filter(entryNames::contains)
            if (duplicateCjkNativeEntries.isNotEmpty()) {
                throw GradleException(
                    "Release APK duplicates a JNI library in its lazy CJK asset tree: " +
                        duplicateCjkNativeEntries.joinToString()
                )
            }

            val notices = archive.getEntry(REQUIRED_NOTICE_PATH)
                ?: throw GradleException("Release APK is missing $REQUIRED_NOTICE_PATH")
            val noticeText = readSmallText(archive, notices)
                ?: throw GradleException("$REQUIRED_NOTICE_PATH is too large or cannot be decoded as UTF-8")
            val missingNoticeMarkers = requiredNoticeMarkers.get().filterNot(noticeText::contains)
            if (missingNoticeMarkers.isNotEmpty()) {
                throw GradleException(
                    "$REQUIRED_NOTICE_PATH is missing required attribution marker(s): " +
                        missingNoticeMarkers.joinToString()
                )
            }

            entries.forEach { entry ->
                if (!isInspectableTextAsset(entry)) return@forEach
                val contents = readSmallText(archive, entry) ?: return@forEach
                val secretKind = SECRET_SIGNATURES.firstOrNull { (_, pattern) ->
                    pattern.containsMatchIn(contents)
                }?.first
                if (secretKind != null) {
                    // Deliberately do not echo a matched credential into Gradle output.
                    throw GradleException(
                        "Release APK asset ${entry.name} appears to contain $secretKind. " +
                            "Remove it and provide cloud credentials only after device unlock."
                    )
                }
            }
        }
    }

    private fun findReleaseApk(): File {
        val directory = File(apkOutputDirectory.get())
        if (!directory.isDirectory) {
            throw GradleException(
                "Release APK output directory is missing: ${directory.absolutePath}. " +
                    "Run :app:packageRelease before package-content verification."
            )
        }
        val apks = directory.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .toList()
        if (apks.size != 1) {
            throw GradleException(
                "Expected exactly one release APK below ${directory.absolutePath}, found ${apks.size}: " +
                    apks.joinToString { it.name }
            )
        }
        return apks.single()
    }

    private fun isUnsafeArchivePath(path: String): Boolean {
        val segments = path.split('/')
        return path.startsWith('/') || path.contains('\\') || segments.any { it.isEmpty() || it == "." || it == ".." }
    }

    private fun isForbiddenPersonalDataPath(path: String): Boolean {
        val normalized = path.lowercase()
        return normalized in FORBIDDEN_EXACT_PATHS ||
            FORBIDDEN_PATH_PREFIXES.any(normalized::startsWith) ||
            PRIVATE_KEY_FILE_SUFFIXES.any(normalized::endsWith)
    }

    private fun isInspectableTextAsset(entry: java.util.zip.ZipEntry): Boolean =
        entry.name.startsWith("assets/") && entry.size in 0..MAX_TEXT_SCAN_BYTES &&
            TEXT_ASSET_SUFFIXES.any(entry.name.lowercase()::endsWith)

    private fun readSmallText(archive: ZipFile, entry: java.util.zip.ZipEntry): String? {
        if (entry.size !in 0..MAX_TEXT_SCAN_BYTES) return null
        return archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private companion object {
        const val REQUIRED_NOTICE_PATH = "assets/THIRD_PARTY_NOTICES.md"
        const val MAX_TEXT_SCAN_BYTES = 1L * 1024L * 1024L

        val FORBIDDEN_EXACT_PATHS = setOf(
            "assets/default_backup.zip",
            "assets/default_backup.json",
            "assets/cloud_credentials.json",
            "assets/keystore.properties",
        )
        val FORBIDDEN_PATH_PREFIXES = listOf(
            "assets/default_backup/",
            "assets/clipboard_history/",
            "assets/history/",
            "assets/learned/",
            "assets/user_data/",
            "assets/personal_data/",
            "assets/cloud_credentials/",
        )
        val PRIVATE_KEY_FILE_SUFFIXES = listOf(".jks", ".keystore", ".p12", ".pfx", ".pem", ".key")
        val TEXT_ASSET_SUFFIXES = listOf(".cfg", ".conf", ".json", ".md", ".properties", ".txt", ".xml")
        val SECRET_SIGNATURES = listOf(
            "a Google API key" to Regex("""AIza[0-9A-Za-z_-]{35}"""),
            "a GitHub token" to Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{36,255}\b"""),
            "an OpenAI-style API token" to Regex("""\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b"""),
            "a PEM private key" to Regex("""-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----"""),
        )
    }
}

/**
 * Derives the only CJK files Gradle is allowed to package from an already-built, verified Mozc
 * bundle. The builder output intentionally lives outside the repository: it contains local build
 * paths in provenance metadata, and must be checked again before a signed APK sees it.
 *
 * The Python verifier is part of this source tree and rejects an upstream `libmozc.so`, stale
 * source/toolchain locks, non-ARM64 ELF, malformed manifests, unexpected files, and hashes that
 * do not match the data/native payload. It then writes a sanitized asset manifest plus the exact
 * JNI library staging directory. No network operation is performed here.
 */
abstract class PrepareVerifiedMozcApkInputs : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verifiedBundleDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val preparationScript: RegularFileProperty

    /** Lock files and FrostKeys-owned bridge sources consulted by the verifier. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verificationInputs: ConfigurableFileCollection

    @get:Input
    abstract val pythonExecutable: Property<String>

    @get:OutputDirectory
    abstract val assetsOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val jniOutputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val command = listOf(
            pythonExecutable.get(),
            preparationScript.get().asFile.absolutePath,
            "--bundle", verifiedBundleDirectory.get().asFile.absolutePath,
            "--assets-output", assetsOutputDirectory.get().asFile.absolutePath,
            "--jni-output", jniOutputDirectory.get().asFile.absolutePath,
        )
        val process = try {
            ProcessBuilder(command)
                .directory(project.rootDir)
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            throw GradleException(
                "Could not start the FrostKeys Mozc bundle verifier. " +
                    "Set FROSTKEYS_PYTHON to a Python 3 executable if `python` is unavailable.",
                error,
            )
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException(
                "Verified Mozc APK input preparation failed (exit $exitCode):\n$output",
            )
        }
        logger.lifecycle(output.trim())
    }
}

/**
 * The Rime equivalent of [PrepareVerifiedMozcApkInputs]. Rime carries both the FrostKeys JNI
 * bridge and its hash-bound `librime.so` core, plus Pinyin/OpenCC data, so it uses a separate
 * verifier rather than reusing a Mozc-specific allowlist. As with Mozc, this task never obtains
 * network data: the maintainer must explicitly supply an external package built by the locked
 * host builder and package-engine-bundle gate.
 */
abstract class PrepareVerifiedRimeApkInputs : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verifiedBundleDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val preparationScript: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verificationInputs: ConfigurableFileCollection

    @get:Input
    abstract val pythonExecutable: Property<String>

    @get:OutputDirectory
    abstract val assetsOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val jniOutputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val command = listOf(
            pythonExecutable.get(),
            preparationScript.get().asFile.absolutePath,
            "--bundle", verifiedBundleDirectory.get().asFile.absolutePath,
            "--assets-output", assetsOutputDirectory.get().asFile.absolutePath,
            "--jni-output", jniOutputDirectory.get().asFile.absolutePath,
        )
        val process = try {
            ProcessBuilder(command)
                .directory(project.rootDir)
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            throw GradleException(
                "Could not start the FrostKeys Rime bundle verifier. " +
                    "Set FROSTKEYS_PYTHON to a Python 3 executable if `python` is unavailable.",
                error,
            )
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("Verified Rime APK input preparation failed (exit $exitCode):\n$output")
        }
        logger.lifecycle(output.trim())
    }
}

/** Downloads the small, pinned Vietnamese dictionaries into generated APK assets. */
abstract class DownloadVerifiedDictionaryAssets : DefaultTask() {
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val assetDescriptors: ListProperty<String>

    @TaskAction
    fun download() {
        val destinationRoot = outputDirectory.get().asFile
        val dictionaries = assetDescriptors.get().map(::parseAssetDescriptor)
        // The previous implementation produced a Signal-derived emoji_vi.dict in this same
        // generated directory. Remove only stale task output; never touch checked-in assets.
        File(destinationRoot, "dicts/emoji_vi.dict").delete()
        dictionaries.forEach { asset ->
            val target = File(destinationRoot, asset.relativeOutputPath)
            if (target.isFile && target.length() == asset.byteCount && sha256(target) == asset.sha256) {
                return@forEach
            }
            target.parentFile.mkdirs()
            val temporary = File.createTempFile("${target.name}.", ".download", target.parentFile)
            try {
                URI(asset.url).toURL().openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                }.getInputStream().use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (temporary.length() != asset.byteCount || sha256(temporary) != asset.sha256) {
                    throw GradleException("Verified dictionary download failed integrity check: ${asset.relativeOutputPath}")
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            } finally {
                temporary.delete()
            }
        }
        // Ship provenance alongside the binary. Runtime code can surface/validate this metadata
        // without relying on a mutable download catalog.
        val mainDictionary = dictionaries.first()
        val manifest = File(destinationRoot, "manifests/dictionary_vi.json")
        manifest.parentFile.mkdirs()
        manifest.writeText(
            """{
              |  "locale": "vi",
              |  "version": "leipzig-2023-09-16",
              |  "source": "Leipzig Corpora Collection",
              |  "license": "CC-BY-4.0",
              |  "sha256": "${mainDictionary.sha256}",
              |  "byteCount": ${mainDictionary.byteCount},
              |  "formatVersion": 1
              |}
            """.trimMargin() + "\n"
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun parseAssetDescriptor(descriptor: String): VerifiedDictionaryAsset {
        val parts = descriptor.split("|", limit = 4)
        if (parts.size != 4) {
            throw GradleException("Invalid verified dictionary descriptor: $descriptor")
        }
        val byteCount = parts[3].toLongOrNull()
            ?: throw GradleException("Invalid dictionary byte count in descriptor: $descriptor")
        return VerifiedDictionaryAsset(
            relativeOutputPath = parts[0],
            url = parts[1],
            sha256 = parts[2],
            byteCount = byteCount,
        )
    }
}

private data class TranslationValueSignature(
    val formatTokens: List<String>,
    val markupTokens: List<String>,
)

private data class TranslationResource(
    val source: File,
    val formatted: Boolean,
    val values: List<TranslationValueSignature>,
)

/**
 * Checks Vietnamese string-resource completeness and rejects missing translations or incompatible
 * placeholders/markup. This is a release invariant for the personal Vietnamese-first build.
 */
abstract class VerifyVietnameseTranslationParity : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val defaultValuesDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val vietnameseValuesDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputReport: RegularFileProperty

    @get:Input
    abstract val strict: Property<Boolean>

    @TaskAction
    fun verify() {
        val base = readResources(defaultValuesDirectory.get().asFile)
        val vietnamese = readResources(vietnameseValuesDirectory.get().asFile)
        val missing = (base.keys - vietnamese.keys).sorted()
        val extra = (vietnamese.keys - base.keys).sorted()
        val formattedMismatches = mutableListOf<String>()
        val placeholderMismatches = mutableListOf<String>()
        val markupMismatches = mutableListOf<String>()
        val itemCountMismatches = mutableListOf<String>()

        (base.keys intersect vietnamese.keys).sorted().forEach { key ->
            val source = base.getValue(key)
            val translation = vietnamese.getValue(key)
            if (source.formatted != translation.formatted) {
                formattedMismatches += "$key: formatted=${source.formatted} vs ${translation.formatted}"
            }
            if (source.values.size != translation.values.size) {
                itemCountMismatches += "$key: ${source.values.size} item(s) vs ${translation.values.size}"
                return@forEach
            }
            source.values.zip(translation.values).forEachIndexed { index, (sourceValue, translatedValue) ->
                if (sourceValue.formatTokens != translatedValue.formatTokens) {
                    placeholderMismatches += "$key[$index]: ${sourceValue.formatTokens} vs ${translatedValue.formatTokens}"
                }
                if (sourceValue.markupTokens != translatedValue.markupTokens) {
                    markupMismatches += "$key[$index]: ${sourceValue.markupTokens} vs ${translatedValue.markupTokens}"
                }
            }
        }

        val report = buildString {
            appendLine("Vietnamese localization parity report")
            appendLine("base translatable entries: ${base.size}")
            appendLine("Vietnamese entries: ${vietnamese.size}")
            appendLine("missing entries: ${missing.size}")
            appendLine("extra Vietnamese entries: ${extra.size}")
            appendLine("formatted attribute mismatches: ${formattedMismatches.size}")
            appendLine("item-count mismatches: ${itemCountMismatches.size}")
            appendLine("placeholder mismatches: ${placeholderMismatches.size}")
            appendLine("markup mismatches: ${markupMismatches.size}")
            appendSection("Missing Vietnamese entries", missing)
            appendSection("Extra Vietnamese entries", extra)
            appendSection("Formatted attribute mismatches", formattedMismatches)
            appendSection("Item-count mismatches", itemCountMismatches)
            appendSection("Placeholder mismatches", placeholderMismatches)
            appendSection("Markup mismatches", markupMismatches)
        }
        val reportFile = outputReport.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report)

        val violationCount = missing.size + formattedMismatches.size + itemCountMismatches.size +
            placeholderMismatches.size + markupMismatches.size
        val summary = "Vietnamese localization parity: base=${base.size}, vi=${vietnamese.size}, " +
            "missing=${missing.size}, formatted=${formattedMismatches.size}, " +
            "itemCount=${itemCountMismatches.size}, placeholders=${placeholderMismatches.size}, " +
            "markup=${markupMismatches.size}. Report: ${reportFile.absolutePath}"
        logger.lifecycle(summary)
        if (violationCount > 0) logger.warn("Vietnamese localization parity found $violationCount issue(s).")
        if (strict.get() && violationCount > 0) {
            throw GradleException(
                "Vietnamese localization parity failed ($violationCount issue(s)). See ${reportFile.absolutePath}"
            )
        }
    }

    private fun readResources(directory: File): Map<String, TranslationResource> {
        val resources = sortedMapOf<String, TranslationResource>()
        directory.listFiles { file -> file.isFile && file.extension.equals("xml", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                // These files intentionally contain locale-independent parser/configuration data,
                // rather than UI text. Keeping one canonical copy avoids changing punctuation rules
                // merely because the app UI locale changes.
                if (file.name.equals("donottranslate.xml", ignoreCase = true) ||
                    file.name.startsWith("donottranslate-", ignoreCase = true)
                ) {
                    return@forEach
                }
                val document = newDocumentBuilder().parse(file)
                val root = document.documentElement
                    ?: throw GradleException("Missing <resources> root in ${file.absolutePath}")
                for (resource in childElements(root)) {
                    val type = resource.tagName
                    if (type !in RESOURCE_TYPES || resource.getAttribute("translatable").equals("false", ignoreCase = true)) {
                        continue
                    }
                    val name = resource.getAttribute("name")
                    if (name.isBlank()) continue
                    val key = "$type/$name"
                    val values = if (type == "string") listOf(resource) else childElements(resource)
                        .filter { it.tagName == "item" }
                    val formatted = !resource.getAttribute("formatted").equals("false", ignoreCase = true)
                    val previous = resources.put(
                        key,
                        TranslationResource(
                            source = file,
                            formatted = formatted,
                            values = values.map { value ->
                                TranslationValueSignature(
                                    formatTokens = if (formatted) formatTokens(value.textContent) else emptyList(),
                                    markupTokens = markupTokens(value),
                                )
                            },
                        )
                    )
                    if (previous != null) {
                        throw GradleException("Duplicate resource $key in ${previous.source.name} and ${file.name}")
                    }
                }
            }
        return resources
    }

    private fun newDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder()

    private fun childElements(element: Element): List<Element> = buildList {
        for (index in 0 until element.childNodes.length) {
            val child = element.childNodes.item(index)
            if (child.nodeType == Node.ELEMENT_NODE) add(child as Element)
        }
    }

    private fun formatTokens(text: String): List<String> = FORMAT_TOKEN.findAll(text)
        .map { it.value.replace(FORMAT_ARGUMENT_INDEX, "%") }
        .sorted()
        .toList()

    private fun markupTokens(element: Element): List<String> = buildList {
        fun visit(node: Node) {
            when (node.nodeType) {
                Node.ELEMENT_NODE -> {
                    val child = node as Element
                    val name = child.namespaceURI?.let { "{$it}${child.localName}" } ?: child.tagName
                    val attributes = (0 until child.attributes.length).map { child.attributes.item(it) }
                        .filterNot {
                            it.nodeName == "xmlns" || it.prefix == "xmlns" ||
                                it.nodeName == "example" || it.localName == "example"
                        }
                        .map { "${it.nodeName}=${it.nodeValue}" }
                        .sorted()
                    add("<$name${attributes.joinToString(prefix = " ", separator = " ").takeIf { attributes.isNotEmpty() } ?: ""}>")
                    for (index in 0 until child.childNodes.length) visit(child.childNodes.item(index))
                    add("</$name>")
                }
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> ESCAPED_HTML_TAG.findAll(node.nodeValue.orEmpty())
                    .forEach { add("escaped:${it.value.replace(WHITESPACE, " ").lowercase()}") }
            }
        }
        for (index in 0 until element.childNodes.length) visit(element.childNodes.item(index))
    }

    private fun StringBuilder.appendSection(title: String, entries: List<String>) {
        appendLine()
        appendLine("$title (${entries.size})")
        entries.forEach { appendLine(it) }
    }

    private companion object {
        val RESOURCE_TYPES = setOf("string", "plurals", "string-array")
        val FORMAT_TOKEN = Regex(
            """(?<![%\d])%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaAn])"""
        )
        val FORMAT_ARGUMENT_INDEX = Regex("""^%(?:\d+\$)?""")
        val ESCAPED_HTML_TAG = Regex("""</?[A-Za-z][A-Za-z0-9:._-]*(?:\s+[^<>]*?)?/?>""")
        val WHITESPACE = Regex("""\s+""")
    }
}

private data class VerifiedDictionaryAsset(
    val relativeOutputPath: String,
    val url: String,
    val sha256: String,
    val byteCount: Long,
)

val personalSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

val localAndroidProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun personalSigningProperty(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: personalSigningProperties.getProperty(name)

/**
 * java.util.Properties normally unescapes `sdk.dir`, but accept a manually escaped Windows path
 * too. This keeps the verifier aligned with Gradle/AGP when local.properties uses the usual
 * `C\:\\Users\\...` spelling.
 */
fun normalizeAndroidToolPath(rawPath: String): String {
    val path = rawPath.trim()
    val normalized = StringBuilder(path.length)
    var index = 0
    while (index < path.length) {
        if (path[index] == '\\' && index + 1 < path.length) {
            when (val escaped = path[index + 1]) {
                ':', '\\' -> {
                    normalized.append(escaped)
                    index += 2
                    continue
                }
            }
        }
        normalized.append(path[index])
        index += 1
    }
    return normalized.toString()
}

// The personal key deliberately lives outside the checkout.  An explicit environment/property
// value wins, while an unconfigured local build follows the documented Windows-friendly default.
val defaultPersonalStoreFile = File(
    System.getenv("USERPROFILE")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"),
    ".android/frostkeys-personal.jks",
).absolutePath
val personalStoreFile = personalSigningProperty("FROSTKEYS_STORE_FILE") ?: defaultPersonalStoreFile
val personalStorePassword = personalSigningProperty("FROSTKEYS_STORE_PASSWORD")
val personalKeyAlias = personalSigningProperty("FROSTKEYS_KEY_ALIAS") ?: "frostkeys-personal"
val personalKeyPassword = personalSigningProperty("FROSTKEYS_KEY_PASSWORD")
val personalCertificateSha256 = personalSigningProperty("FROSTKEYS_CERT_SHA256")
    ?.replace(":", "")
    ?.lowercase()
val personalReleaseSigningReady = listOf(
    personalStoreFile,
    personalStorePassword,
    personalKeyAlias,
    personalKeyPassword,
    personalCertificateSha256,
).all { !it.isNullOrBlank() }

val configuredAndroidSdkDirectory = normalizeAndroidToolPath(
    providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: providers.environmentVariable("ANDROID_HOME").orNull
        ?: localAndroidProperties.getProperty("sdk.dir").orEmpty(),
)
val configuredNdkDirectory = normalizeAndroidToolPath(
    providers.environmentVariable("ANDROID_NDK_ROOT").orNull
        ?: providers.environmentVariable("ANDROID_NDK_HOME").orNull
        ?: localAndroidProperties.getProperty("ndk.dir")
        ?: configuredAndroidSdkDirectory.takeIf { it.isNotBlank() }
            ?.let { File(it, "ndk/28.0.13004108").absolutePath }
            .orEmpty(),
)

// CJK binaries are never downloaded during an app build. A maintainer must explicitly point at
// the output of tools/cjk/package-engine-bundle.py; the Gradle task below then verifies and
// sanitizes that output again before it becomes an APK asset/JNI input.
val configuredMozcBundleDirectory = providers.environmentVariable("FROSTKEYS_MOZC_BUNDLE_DIR")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val mozcBundleEnabled = configuredMozcBundleDirectory != null
val configuredRimeBundleDirectory = providers.environmentVariable("FROSTKEYS_RIME_BUNDLE_DIR")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val rimeBundleEnabled = configuredRimeBundleDirectory != null
val configuredPythonExecutable = providers.environmentVariable("FROSTKEYS_PYTHON")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "python"

val generateImeMethodXml = tasks.register<GenerateImeMethodXml>("generateImeMethodXml") {
    group = "build setup"
    description = "Generates IME subtype metadata, including optional CJK engines only with verified bundles."
    templateFile.set(layout.projectDirectory.file("src/main/method.xml.template"))
    includeMozcSubtype.set(mozcBundleEnabled)
    includeRimeSubtype.set(rimeBundleEnabled)
    outputDirectory.set(layout.buildDirectory.dir("generated/ime-method-res"))
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "com.orion.frostkeys"
        minSdk = 31
        targetSdk = 36
        versionCode = FROSTKEYS_VERSION_CODE
        versionName = FROSTKEYS_VERSION_NAME
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"${applicationId}.stickercontentprovider\"")
        // This flag means the verified offline Mozc payload is physically present. The generated
        // @xml/method metadata uses the same build input, so Java/Kotlin code can never activate
        // a Japanese subtype that Android was not allowed to advertise.
        buildConfigField("boolean", "FROSTKEYS_MOZC_BUNDLE_ENABLED", mozcBundleEnabled.toString())
        // Rime is physically staged only through the same hash-verified external-bundle path.
        // Its subtype is generated only after the Rime runtime is wired; this flag nevertheless
        // prevents a stale system subtype from ever activating a bundle-less update.
        buildConfigField("boolean", "FROSTKEYS_RIME_BUNDLE_ENABLED", rimeBundleEnabled.toString())
        manifestPlaceholders["stickerAuthority"] = "${applicationId}.stickercontentprovider"
        manifestPlaceholders["stickerProviderAuthority"] = "${applicationId}.stickercontentprovider"
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            ndkBuild {
                arguments.add("-j1")
            }
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    // This private fork intentionally ships UI resources only for the maintained app locales.
    // Keyboard subtypes without a maintained UI translation fall back to English.
    androidResources {
        localeFilters += listOf("vi", "ja", "ko", "th", "zh-rCN", "zh-rTW")
    }

    // FrostKeys VN ships one self-contained APK. Locale changes are handled from resources
    // already bundled with the app, never by Play language splits or a network download.
    bundle {
        language {
            enableSplit = false
        }
    }

    // Some AARs publish native libraries for every ABI. This personal APK deliberately supports
    // Android 12+ ARM64 only, so prevent those unrelated binaries from reaching the artifact.
    packaging {
        jniLibs {
            excludes += setOf(
                "**/armeabi-v7a/**",
                "**/x86/**",
                "**/x86_64/**",
                "**/mips/**",
                "**/mips64/**",
            )
        }
    }

    signingConfigs {
        create("personalRelease") {
            if (personalReleaseSigningReady) {
                storeFile = rootProject.file(personalStoreFile!!)
                storePassword = personalStorePassword
                keyAlias = personalKeyAlias
                keyPassword = personalKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            if (personalReleaseSigningReady) {
                signingConfig = signingConfigs.getByName("personalRelease")
            }
        }
        create("nouserlib") {
            // Internal packaging smoke-test only. It must never be mistaken for a personal
            // release or replace it on a device, so give it a distinct package/version and the
            // normal debug key. The sole distributable artifact is `assembleRelease`.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal"
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = false
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
            manifestPlaceholders["stickerProviderAuthority"] = "${defaultConfig.applicationId}.debug.stickercontentprovider"
        }
        create("runTests") { // build variant for unit tests and CI
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            manifestPlaceholders["stickerProviderAuthority"] = "${defaultConfig.applicationId}.debug.stickercontentprovider"
        }

        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/dontoptimize.pro"))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
            if (variant.buildType == "release" || variant.buildType == "nouserlib") {
                variant.androidResources.ignoreAssetsPatterns = personalReleaseIgnoredAssetPatterns
            }
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    val artifactSuffix = when (variant.buildType) {
                        "release" -> "arm64"
                        "nouserlib" -> "internal"
                        else -> variant.buildType
                    }
                    output.outputFileName = "FrostKeys_${defaultConfig.versionName}-$artifactSuffix.apk"
                }
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            // Android 12+ ARM64 needs uncompressed page-aligned .so entries so zipalign can
            // enforce the Android 15 16 KiB page-size requirement.
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        target {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

val verifyPersonalReleaseSigning = tasks.register<VerifyPersonalReleaseSigning>("verifyPersonalReleaseSigning") {
    group = "verification"
    description = "Verifies that a personal signing key is configured before creating a release APK."
    signingConfigured.set(personalReleaseSigningReady)
    configuredStorePath.set(personalStoreFile.orEmpty())
    expectedCertificateSha256.set(personalCertificateSha256.orEmpty())
    if (personalStoreFile != null) {
        configuredStoreFile.set(rootProject.file(personalStoreFile))
    }
}

val verifyVendoredWebpAar = tasks.register<VerifyVendoredWebpAar>("verifyVendoredWebpAar") {
    group = "verification"
    description = "Verifies the SHA-256 of the locally rebuilt 16 KiB WebP codec AAR."
    artifact.set(layout.projectDirectory.file("libs/webp-android-1.1.2-16k.aar"))
    expectedSha256.set("cbfa5d7b604accd29767dee7744fd6fd983a002efb9c3f090cade73a38e333f1")
}

tasks.named("preBuild") {
    dependsOn(verifyVendoredWebpAar)
}

val verifyPersonalReleasePackageContents =
    tasks.register<VerifyPersonalReleasePackageContents>("verifyPersonalReleasePackageContents") {
        group = "verification"
        description = "Checks the signed personal release APK for required offline assets, notices, ABI, and secrets."
        apkOutputDirectory.set(layout.buildDirectory.dir("outputs/apk/release").map { it.asFile.absolutePath })
        expectedAbi.set("arm64-v8a")
        val baseRequiredEntries = listOf(
            "assets/THIRD_PARTY_NOTICES.md",
            "assets/licenses/bouncycastle-license.txt",
            "assets/licenses/libwebp-BSD-3-Clause.txt",
            "assets/licenses/webp-android-MIT.txt",
            "assets/dicts/main_en-US.dict",
            "assets/dicts/main_vi.dict",
            "assets/dicts/vi_phrase_model_v1.tsv",
            "assets/emoji/vi.xml",
            "assets/manifests/dictionary_vi.json",
            "assets/manifests/phrase_model_vi.json",
            "assets/layouts/main/qwerty.txt",
            "assets/layouts/main/korean.json",
            "assets/layouts/main/thai.json",
            "assets/locale_key_texts/vi.txt",
        )
        val mozcRequiredEntries = if (mozcBundleEnabled) listOf(
            "assets/cjk/mozc/commit-851c3fe/manifest.json",
            "assets/cjk/mozc/commit-851c3fe/data/mozc.data",
            "assets/cjk/mozc/commit-851c3fe/licenses/LICENSE",
            "assets/cjk/mozc/commit-851c3fe/metadata/build-inputs.json",
            "assets/cjk/mozc/commit-851c3fe/metadata/build-provenance.json",
            "lib/arm64-v8a/libfrostkeys_mozc.so",
        ) else emptyList()
        val rimeRequiredEntries = if (rimeBundleEnabled) listOf(
            "assets/cjk/rime/1.16.1/manifest.json",
            "assets/cjk/rime/1.16.1/shared/luna_pinyin.schema.yaml",
            "assets/cjk/rime/1.16.1/shared/luna_pinyin.dict.yaml",
            "assets/cjk/rime/1.16.1/shared/t2s.json",
            "assets/cjk/rime/1.16.1/shared/t2tw.json",
            "assets/cjk/rime/1.16.1/shared/STPhrases.ocd2",
            "assets/cjk/rime/1.16.1/metadata/build-inputs.json",
            "assets/cjk/rime/1.16.1/metadata/build-provenance.json",
            "assets/cjk/rime/1.16.1/licenses/librime-LICENSE.txt",
            "assets/cjk/rime/1.16.1/licenses/opencc-LICENSE.txt",
            "assets/cjk/rime/1.16.1/licenses/boost-LICENSE_1_0.txt",
            "lib/arm64-v8a/libfrostkeys_rime.so",
            "lib/arm64-v8a/librime.so",
        ) else emptyList()
        requiredEntries.set(baseRequiredEntries + mozcRequiredEntries + rimeRequiredEntries)
        forbiddenEntries.set(
            buildList {
                if (mozcBundleEnabled) {
                    add("assets/cjk/mozc/commit-851c3fe/lib/arm64-v8a/libfrostkeys_mozc.so")
                }
                if (rimeBundleEnabled) {
                    add("assets/cjk/rime/1.16.1/lib/arm64-v8a/libfrostkeys_rime.so")
                    add("assets/cjk/rime/1.16.1/lib/arm64-v8a/librime.so")
                }
            },
        )
        val baseNoticeMarkers = listOf(
            "Leipzig Corpora Collection",
            "Unicode CLDR",
            "FrostKeys Vietnamese phrase seed",
            "WebP",
            "Bouncy Castle",
        )
        requiredNoticeMarkers.set(
            baseNoticeMarkers +
                (if (mozcBundleEnabled) listOf("Mozc") else emptyList()) +
                (if (rimeBundleEnabled) listOf("Rime", "OpenCC", "Boost") else emptyList()),
        )
    }

val verifyReleaseNativeCompatibility =
    tasks.register<VerifyReleaseNativeCompatibility>("verifyReleaseNativeCompatibility") {
        group = "verification"
        description = "Checks a packaged release APK signature, native ELF, and 16 KiB zip alignment."
        // Do not depend on packageRelease here: packageRelease itself finalizes this task, and a
        // reciprocal dependency would let Gradle form a cycle. Invoking this task alone verifies
        // the already-packaged APK and gives a clear missing-output error if there is none.
        apkOutputDirectory.set(layout.buildDirectory.dir("outputs/apk/release").map { it.asFile.absolutePath })
        androidSdkDirectory.set(configuredAndroidSdkDirectory)
        ndkDirectory.set(configuredNdkDirectory)
        expectedAbi.set("arm64-v8a")
        expectedCertificateSha256.set(personalCertificateSha256.orEmpty())
    }

// packageRelease is public and may be called directly, so make the artifact gates finalizers of
// that exact task rather than only of assembleRelease.  This preserves package -> verifier order
// without creating a packageRelease <-> verifier dependency cycle.
tasks.configureEach {
    if (name == "packageRelease") {
        finalizedBy(verifyPersonalReleasePackageContents, verifyReleaseNativeCompatibility)
    }
}

val downloadVerifiedDictionaryAssets = tasks.register<DownloadVerifiedDictionaryAssets>("downloadVerifiedDictionaryAssets") {
    group = "build setup"
    description = "Downloads SHA-256-pinned Vietnamese dictionaries for offline APK use."
    outputDirectory.set(layout.buildDirectory.dir("generated/verified-dictionary-assets"))
    // These descriptors are Gradle inputs, so changing a URL, hash or expected size invalidates
    // prior generated assets instead of relying on an easy-to-forget manual revision bump.
    assetDescriptors.set(listOf(
        // Snapshot 69afafc... rather than a mutable branch. The SHA-256 below is still verified
        // so a compromised/misconfigured mirror cannot silently alter a build.
        "dicts/main_vi.dict|https://codeberg.org/Helium314/aosp-dictionaries/raw/commit/69afafc3887d189515fa0be8b4585b91df80b92d/dictionaries_experimental/main_vi.dict|410fb85388b646b6694373e83f30052040332acd2baaeb640574d3846c7c5ea4|128328",
        // Pure CLDR annotations; do not bundle the upstream Signal-derived emoji binary.
        "emoji/vi.xml|https://raw.githubusercontent.com/unicode-org/cldr/e1d37acce5dae468c414172be53b666d58d45be8/common/annotations/vi.xml|76c891279647566f243fd3d2f8433479269fc561106821b55e7028a022508f58|330070",
    ))
}

val verifyVietnameseTranslationParity = tasks.register<VerifyVietnameseTranslationParity>("verifyVietnameseTranslationParity") {
    group = "verification"
    description = "Reports missing Vietnamese resources and checks placeholders/markup against values/."
    defaultValuesDirectory.set(layout.projectDirectory.dir("src/main/res/values"))
    vietnameseValuesDirectory.set(layout.projectDirectory.dir("src/main/res/values-vi"))
    outputReport.set(layout.buildDirectory.file("reports/localization/vietnamese-parity.txt"))
    // This is a release invariant, not an opt-in report: every translatable value must have a
    // Vietnamese counterpart with compatible placeholders and markup.
    strict.set(true)
}

/**
 * Complements resource parity: production UI code must not introduce a new literal that cannot
 * be translated or verified against values-vi. Preview fixtures and decorative glyphs are the
 * only deliberately excluded cases, documented in the checker itself.
 */
val verifyNoHardcodedProductionText = tasks.register<Exec>("verifyNoHardcodedProductionText") {
    group = "verification"
    description = "Rejects hardcoded user-visible text in production Java/Kotlin source."
    workingDir(rootProject.projectDir)
    commandLine(
        configuredPythonExecutable,
        layout.projectDirectory.file("../tools/localization/check_hardcoded_production_text.py").asFile.absolutePath,
        "--source-root",
        layout.projectDirectory.dir("src/main/java").asFile.absolutePath,
        "--resource-root",
        layout.projectDirectory.dir("src/main/res").asFile.absolutePath,
        "--report",
        layout.buildDirectory.file("reports/localization/hardcoded-production-text.txt").get().asFile.absolutePath,
    )
    inputs.files(
        layout.projectDirectory.file("../tools/localization/check_hardcoded_production_text.py"),
        layout.projectDirectory.dir("src/main/java"),
        layout.projectDirectory.dir("src/main/res"),
    ).withPropertyName("hardcodedTextInputs").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(layout.buildDirectory.file("reports/localization/hardcoded-production-text.txt"))
}

// The phrase seed is deliberately checked in rather than downloaded. This task proves the exact
// APK asset and provenance manifest are deterministically regenerated from that original, bounded
// source and also guards the three mandatory Vietnamese contextual predictions.
val verifyVietnamesePhraseModel = tasks.register<Exec>("verifyVietnamesePhraseModel") {
    group = "verification"
    description = "Verifies the reproducible, offline Vietnamese bigram/trigram phrase model."
    workingDir(rootProject.projectDir)
    commandLine(
        configuredPythonExecutable,
        layout.projectDirectory.file("../tools/vietnamese/test_phrase_model.py").asFile.absolutePath,
    )
    inputs.files(
        layout.projectDirectory.file("../tools/vietnamese/build_phrase_model.py"),
        layout.projectDirectory.file("../tools/vietnamese/seed_vi_phrases_v1.tsv"),
        layout.projectDirectory.file("../tools/vietnamese/test_phrase_model.py"),
        layout.projectDirectory.file("src/main/assets/dicts/vi_phrase_model_v1.tsv"),
        layout.projectDirectory.file("src/main/assets/manifests/phrase_model_vi.json"),
    ).withPropertyName("phraseModelInputs").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.upToDateWhen { false }
}

val prepareVerifiedMozcApkInputs = configuredMozcBundleDirectory?.let { rawBundleDirectory ->
    val bundleDirectory = file(rawBundleDirectory).absoluteFile
    check(bundleDirectory.isDirectory) {
        "FROSTKEYS_MOZC_BUNDLE_DIR must name a verified Mozc bundle directory: ${bundleDirectory.path}"
    }
    tasks.register<PrepareVerifiedMozcApkInputs>("prepareVerifiedMozcApkInputs") {
        group = "build setup"
        description = "Verifies and sanitizes a locally-built offline Mozc bundle before APK packaging."
        verifiedBundleDirectory.set(layout.dir(providers.provider { bundleDirectory }))
        preparationScript.set(layout.projectDirectory.file("../tools/cjk/prepare_mozc_apk_inputs.py"))
        verificationInputs.from(
            layout.projectDirectory.file("../tools/cjk/engine-sources.json"),
            layout.projectDirectory.file("../tools/cjk/toolchains.json"),
            layout.projectDirectory.file("../tools/cjk/mozc_bridge/frostkeys_mozc_jni.cc"),
            layout.projectDirectory.file("../tools/cjk/mozc_bridge/BUILD.bazel.template"),
        )
        pythonExecutable.set(configuredPythonExecutable)
        assetsOutputDirectory.set(layout.buildDirectory.dir("generated/verified-mozc-assets"))
        jniOutputDirectory.set(layout.buildDirectory.dir("generated/verified-mozc-jni"))
    }
}

val prepareVerifiedRimeApkInputs = configuredRimeBundleDirectory?.let { rawBundleDirectory ->
    val bundleDirectory = file(rawBundleDirectory).absoluteFile
    check(bundleDirectory.isDirectory) {
        "FROSTKEYS_RIME_BUNDLE_DIR must name a verified Rime bundle directory: ${bundleDirectory.path}"
    }
    tasks.register<PrepareVerifiedRimeApkInputs>("prepareVerifiedRimeApkInputs") {
        group = "build setup"
        description = "Verifies and sanitizes a locally-built offline Rime bundle before APK packaging."
        verifiedBundleDirectory.set(layout.dir(providers.provider { bundleDirectory }))
        preparationScript.set(layout.projectDirectory.file("../tools/cjk/prepare_rime_apk_inputs.py"))
        verificationInputs.from(
            layout.projectDirectory.file("../tools/cjk/engine-sources.json"),
            layout.projectDirectory.file("../tools/cjk/toolchains.json"),
            layout.projectDirectory.file("../tools/cjk/package-engine-bundle.py"),
            layout.projectDirectory.file("../tools/cjk/build-rime-arm64.sh"),
            layout.projectDirectory.file("../tools/cjk/rime_bridge/frostkeys_rime_jni.cc"),
            layout.projectDirectory.file("../tools/cjk/rime_bridge/CMakeLists.txt"),
        )
        pythonExecutable.set(configuredPythonExecutable)
        assetsOutputDirectory.set(layout.buildDirectory.dir("generated/verified-rime-assets"))
        jniOutputDirectory.set(layout.buildDirectory.dir("generated/verified-rime-jni"))
    }
}

// Keep generated dictionaries and an explicitly enabled, verified Mozc bundle indistinguishable
// from checked-in assets/JNI inputs to the runtime loaders. The bundle remains optional so normal
// local tests do not silently package or initialize CJK engines.
android.sourceSets.getByName("main").assets.srcDir(downloadVerifiedDictionaryAssets.flatMap { it.outputDirectory })
// `method.xml` is generated instead of checked in as a resource so Android never learns about
// Japanese Mozc in builds that omit the native/data payload.
android.sourceSets.getByName("main").res.srcDir(generateImeMethodXml.flatMap { it.outputDirectory })
prepareVerifiedMozcApkInputs?.let { task ->
    android.sourceSets.getByName("main").assets.srcDir(task.flatMap { it.assetsOutputDirectory })
    android.sourceSets.getByName("main").jniLibs.srcDir(task.flatMap { it.jniOutputDirectory })
}
prepareVerifiedRimeApkInputs?.let { task ->
    android.sourceSets.getByName("main").assets.srcDir(task.flatMap { it.assetsOutputDirectory })
    android.sourceSets.getByName("main").jniLibs.srcDir(task.flatMap { it.jniOutputDirectory })
}
tasks.configureEach {
    // AGP reads a source-set directory from several task families (resources, navigation,
    // deep-link extraction, lint and variant metadata). AndroidSourceDirectorySet does not retain
    // the TaskProvider producer when srcDir is configured, so Gradle 8 rejects individual
    // consumers as implicit dependencies. The XML generator is side-effect-free and cheap; make
    // every app task except clean depend on it so all current and future AGP consumers see a
    // complete generated directory. Keep `clean` independent so cleaning does not recreate it.
    if (name != generateImeMethodXml.name && name != "clean") {
        dependsOn(generateImeMethodXml)
    }
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(downloadVerifiedDictionaryAssets)
        prepareVerifiedMozcApkInputs?.let { dependsOn(it) }
        prepareVerifiedRimeApkInputs?.let { dependsOn(it) }
    }
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        prepareVerifiedMozcApkInputs?.let { dependsOn(it) }
        prepareVerifiedRimeApkInputs?.let { dependsOn(it) }
    }
    // AGP's lint model also reads every asset source set. Declare this explicitly so a release
    // build cannot race lint-vital against the pinned generated asset verifiers.
    if (name.contains("lint", ignoreCase = true)) {
        dependsOn(generateImeMethodXml)
        dependsOn(downloadVerifiedDictionaryAssets)
        prepareVerifiedMozcApkInputs?.let { dependsOn(it) }
        prepareVerifiedRimeApkInputs?.let { dependsOn(it) }
    }
}

tasks.configureEach {
    // packageRelease is public and can be invoked directly, so it must not be a way to
    // produce an unsigned file carrying the final personal-release filename.
    if (name == "packageRelease" || name == "assembleRelease") {
        dependsOn(verifyPersonalReleaseSigning)
        dependsOn(verifyVietnamesePhraseModel)
        dependsOn(verifyNoHardcodedProductionText)
    }
    if (name == "bundleRelease") {
        // This fork deliberately ships one APK, not an AAB. Keeping the AAB task available would
        // create a second release-shaped artifact outside the APK content/16 KiB verification
        // gates, so fail before it can package anything.
        doFirst {
            throw GradleException("FrostKeys VN distributes only the signed arm64 APK. Use :app:assembleRelease, not :app:bundleRelease.")
        }
    }
    if (name == "packageNouserlib" || name == "assembleNouserlib") {
        dependsOn(verifyVietnamesePhraseModel)
        dependsOn(verifyNoHardcodedProductionText)
    }
}

// Keep the Vietnamese completeness gate on every ordinary verification path.
tasks.configureEach {
    if (name == "check" || name == "lintRelease") {
        dependsOn(verifyVietnameseTranslationParity)
        dependsOn(verifyVietnamesePhraseModel)
        dependsOn(verifyNoHardcodedProductionText)
    }
}

dependencies {
    // androidx
    // 1.19 requires AGP 9.1 and compileSdk 37; this release stays on the verified AGP 8.13/SDK 36 toolchain.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // The backup format uses Bouncy Castle's maintained, pure-Java Argon2id implementation to
    // derive a key for password-protected learned-word dictionaries. AES-GCM itself remains the
    // Android platform cipher so no provider is registered globally.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Compose BOM 2026.08.00 requires AGP 9.1.2 and compileSdk 37. Keep the last compatible
    // BOM until the build toolchain is migrated as a single, separately verified change.
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3:1.5.0-alpha04")
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors
    // OkHttp 5.5 also requires compileSdk 37. Keep the latest compatible 4.x line for this build.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    // Animated sticker processing uses this WebP codec. Packaging rules above strip its unused
    // ABIs; the release gate verifies the retained ARM64 ELF alignment.
    implementation(files("libs/webp-android-1.1.2-16k.aar"))
    implementation("com.getkeepsafe.relinker:relinker:1.4.5")
    implementation("com.google.android.material:material:1.14.0")
    implementation("dev.chrisbanes.haze:haze:1.7.2")

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
}
