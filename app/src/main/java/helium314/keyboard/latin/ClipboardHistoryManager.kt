// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ContentUris
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Outline
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.view.isGone
import coil.load
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.isValidNumber
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    @Volatile
    private var clipboardDao: ClipboardDao? = null
    @Volatile
    private var historyChangeListener: ClipboardDao.Listener? = null
    private var screenshotObserver: ContentObserver? = null
    private val screenshotDebounceHandler = Handler(Looper.getMainLooper())
    private val screenshotProcessingMutex = Mutex()
    // A single queue keeps SQLite mutations and cache-file operations ordered. In particular an
    // image-copy completion can never race a clear/delete issued from the clipboard panel.
    private val managerScope = CoroutineScope(SupervisorJob() + ClipboardDao.storageDispatcher)
    private val screenshotCheckRunnable = Runnable { checkForNewScreenshot() }
    private var lastProcessedImageUri: String? = null
    private val processedScreenshotUris = ArrayDeque<String>()
    private val screenshotInFlightUris = mutableSetOf<String>()
    @Volatile
    private var latestImageSuggestion: RecentClip.Image? = null
    private val screenshotPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Settings.PREF_ENABLE_CLIPBOARD_HISTORY
                || key == Settings.PREF_SHOW_SCREENSHOTS_IN_CLIPBOARD
            ) {
                if (!canAccessScreenshotImages()) {
                    latestImageSuggestion = null
                    screenshotDebounceHandler.removeCallbacks(screenshotCheckRunnable)
                }
                syncScreenshotObserver()
            }
        }

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        latinIME.prefs().registerOnSharedPreferenceChangeListener(screenshotPreferenceListener)
        restoreProcessedScreenshotUris()
        managerScope.launch {
            clipboardDao = ClipboardDao.getInstance(latinIME)
            clipboardDao?.setHistoryChangeListener(historyChangeListener)
            if (latinIME.mSettings.current.mClipboardHistoryEnabled) fetchPrimaryClip()
        }
        syncScreenshotObserver()
    }

    fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
        latinIME.prefs().unregisterOnSharedPreferenceChangeListener(screenshotPreferenceListener)
        unregisterScreenshotObserver()
        screenshotDebounceHandler.removeCallbacks(screenshotCheckRunnable)
        managerScope.cancel()
    }

    private fun syncScreenshotObserver() {
        if (shouldObserveScreenshots()) registerScreenshotObserver()
        else unregisterScreenshotObserver()
    }

    private fun shouldObserveScreenshots(): Boolean {
        return canAccessScreenshotImages()
    }

    /**
     * Screenshot images are a separate, explicit feature. Keeping this guard shared by the
     * observer and clipboard-image path prevents a revoked or selected-photo-only permission
     * from being bypassed by an image put on the primary clipboard.
     */
    private fun canAccessScreenshotImages(): Boolean {
        val prefs = latinIME.prefs()
        return prefs.getBoolean(
            Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
            Defaults.PREF_ENABLE_CLIPBOARD_HISTORY
        )
                && prefs.getBoolean(
                    Settings.PREF_SHOW_SCREENSHOTS_IN_CLIPBOARD,
                    Defaults.PREF_SHOW_SCREENSHOTS_IN_CLIPBOARD
                )
                && PermissionsUtil.hasFullScreenshotReadPermission(latinIME)
    }

    private fun registerScreenshotObserver() {
        if (screenshotObserver != null) return
        
        screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                onScreenshotMediaChanged()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                onScreenshotMediaChanged()
            }

            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                super.onChange(selfChange, uris, flags)
                onScreenshotMediaChanged()
            }
        }
        
        try {
            latinIME.contentResolver.registerContentObserver(
                imageCollectionUri(),
                true,
                screenshotObserver!!
            )
        } catch (e: Exception) {
            Log.e("ClipboardHistoryManager", "Failed to register screenshot content observer", e)
        }
    }

    private fun unregisterScreenshotObserver() {
        screenshotObserver?.let {
            try {
                latinIME.contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                Log.e("ClipboardHistoryManager", "Failed to unregister screenshot observer", e)
            }
            screenshotObserver = null
        }
    }

    private fun onScreenshotMediaChanged() {
        if (!shouldObserveScreenshots()) {
            syncScreenshotObserver()
            return
        }
        // MediaStore often emits several notifications for one screenshot. Coalescing them
        // avoids duplicate queries, image copies and SQLite writes.
        screenshotDebounceHandler.removeCallbacks(screenshotCheckRunnable)
        screenshotDebounceHandler.postDelayed(screenshotCheckRunnable, SCREENSHOT_DEBOUNCE_MILLIS)
    }

    private fun checkForNewScreenshot() {
        managerScope.launch {
            // ClipboardDao maintains an in-memory cache, so serialize all screenshot work even
            // if the MediaStore sends a second notification before the first scan completes.
            screenshotProcessingMutex.withLock {
                try {
                    if (!shouldObserveScreenshots()) return@withLock
                    val dao = clipboardDao
                    var processedAny = false

                    for (screenshot in queryRecentScreenshots().asReversed()) {
                        val uriString = screenshot.uri.toString()
                        if (!claimScreenshotUri(uriString)) continue

                        var processed = false
                        try {
                            val timeStamp = System.currentTimeMillis()
                            val imageClip = RecentClip.Image(
                                uri = screenshot.uri,
                                mimeType = normalizeImageMimeType(screenshot.mimeType).mimeType,
                                label = "Screenshot",
                                timeStamp = timeStamp
                            )

                            val cachedUri = cacheImageClip(imageClip)
                            if (cachedUri != null && dao != null) {
                                dao.addClip(
                                    timeStamp,
                                    false,
                                    encodeImageHistoryClip(cachedUri, imageClip.mimeType, imageClip.label)
                                )
                                latinIME.mHandler.post {
                                    rememberImageSuggestion(imageClip.copy(uri = cachedUri))
                                }
                                processed = true
                                processedAny = true
                            }
                        } catch (e: Exception) {
                            // Screenshot URIs are private user media locations.
                            Log.e("ClipboardHistoryManager", "Failed processing a screenshot", e)
                        } finally {
                            completeScreenshotUri(uriString, screenshot.dateAdded, processed)
                        }
                    }

                    if (processedAny) refreshClipboardSuggestion()
                } catch (e: Exception) {
                    Log.e("ClipboardHistoryManager", "Failed checking for new screenshot", e)
                }
            }
        }
    }

    private data class ScreenshotMedia(
        val uri: Uri,
        val mimeType: String,
        val dateAdded: Long
    )

    private fun queryRecentScreenshots(): List<ScreenshotMedia> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        val recentSinceSeconds = System.currentTimeMillis() / 1000L - SCREENSHOT_RECENT_WINDOW_SECONDS
        val (selection, selectionArgs) = screenshotSelection(recentSinceSeconds)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        val screenshots = mutableListOf<ScreenshotMedia>()
        latinIME.contentResolver.query(
            imageCollectionUri(),
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            if (idIndex == -1 || dateIndex == -1 || mimeIndex == -1 || pathIndex == -1) return emptyList()

            var checkedRows = 0
            while (cursor.moveToNext() && checkedRows < MAX_SCREENSHOT_ROWS_TO_CHECK) {
                checkedRows++
                val path = cursor.getString(pathIndex)
                if (!isScreenshotPath(path)) continue

                val id = cursor.getLong(idIndex)
                val itemUri = ContentUris.withAppendedId(imageCollectionUri(), id)
                val mimeType = cursor.getString(mimeIndex)
                    ?.takeIf { ClipDescription.compareMimeTypes(it, "image/*") }
                    ?: latinIME.contentResolver.getType(itemUri)
                        ?.takeIf { ClipDescription.compareMimeTypes(it, "image/*") }
                    ?: continue
                screenshots.add(ScreenshotMedia(itemUri, normalizeImageMimeType(mimeType).mimeType, cursor.getLong(dateIndex)))
            }
        }
        return screenshots
    }

    private fun imageCollectionUri(): Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun screenshotSelection(recentSinceSeconds: Long): Pair<String, Array<String>> {
        val pathSelection = SCREENSHOT_RELATIVE_PATHS.joinToString(" OR ") {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        }
        val selection = "($pathSelection) AND ${MediaStore.Images.Media.DATE_ADDED} >= ? " +
                "AND ${MediaStore.Images.Media.MIME_TYPE} LIKE ? " +
                "AND ${MediaStore.Images.Media.IS_PENDING} = 0"
        val args = SCREENSHOT_RELATIVE_PATHS.map { "$it%" } +
                listOf(recentSinceSeconds.toString(), "image/%")
        return selection to args.toTypedArray()
    }

    private fun isScreenshotPath(path: String?): Boolean {
        val normalizedPath = path?.replace('\\', '/') ?: return false
        return SCREENSHOT_RELATIVE_PATHS.any { normalizedPath.startsWith(it, ignoreCase = true) }
    }

    private fun claimScreenshotUri(uriString: String): Boolean = synchronized(this) {
        if (processedScreenshotUris.contains(uriString)
            || uriString == lastProcessedImageUri
            || screenshotInFlightUris.contains(uriString)
        ) {
            false
        } else {
            screenshotInFlightUris.add(uriString)
            true
        }
    }

    private fun completeScreenshotUri(uriString: String, dateAdded: Long, processed: Boolean) {
        val processedSnapshot = synchronized(this) {
            if (processed) rememberProcessedScreenshotUriLocked(uriString)
            screenshotInFlightUris.remove(uriString)
            if (processed) processedScreenshotUris.toList() else null
        }
        if (processedSnapshot != null) {
            persistProcessedScreenshotUris(processedSnapshot, uriString, dateAdded)
        }
    }

    private fun restoreProcessedScreenshotUris() {
        synchronized(this) {
            if (processedScreenshotUris.isNotEmpty()) return

            val persistedUris = latinIME.prefs()
                .getString(PREF_PROCESSED_SCREENSHOT_MEDIA_URIS, null)
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                .orEmpty()
            val migratedUri = latinIME.prefs().getString(PREF_LAST_SCREENSHOT_MEDIA_URI, null)
                ?.takeIf { it.isNotBlank() && it !in persistedUris }

            (persistedUris + listOfNotNull(migratedUri))
                .takeLast(MAX_PROCESSED_SCREENSHOT_URIS)
                .forEach { processedScreenshotUris.addLast(it) }
            lastProcessedImageUri = processedScreenshotUris.lastOrNull()
        }
    }

    private fun rememberProcessedScreenshotUriLocked(uriString: String) {
        processedScreenshotUris.remove(uriString)
        processedScreenshotUris.addLast(uriString)
        while (processedScreenshotUris.size > MAX_PROCESSED_SCREENSHOT_URIS) {
            processedScreenshotUris.removeFirst()
        }
        lastProcessedImageUri = uriString
    }

    private fun persistProcessedScreenshotUris(
        processedUris: List<String>,
        latestUri: String,
        dateAdded: Long
    ) {
        latinIME.prefs().edit {
            putString(PREF_PROCESSED_SCREENSHOT_MEDIA_URIS, processedUris.joinToString("\n"))
            putString(PREF_LAST_SCREENSHOT_MEDIA_URI, latestUri)
            putLong(PREF_LAST_SCREENSHOT_DATE_ADDED, dateAdded)
        }
    }

    override fun onPrimaryClipChanged() {
        dontShowCurrentSuggestion = false
        // Make sure we read clipboard history content only if history settings is set.
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip(clipChanged = true)
        }
        refreshClipboardSuggestion()
    }

    fun updatePrimaryClip() {
        syncScreenshotObserver()
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
        }
    }

    private fun fetchPrimaryClip(clipChanged: Boolean = false) {
        try {
            val clipData = clipboardManager.primaryClip ?: run {
                if (clipChanged) latestImageSuggestion = null
                return
            }
            if (clipData.itemCount == 0) {
                if (clipChanged) latestImageSuggestion = null
                return
            }
            val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
            val shouldShowImages = canAccessScreenshotImages()
            // Never retain an old image suggestion after the platform reports a new primary
            // clip. The replacement is published on the IME handler only after its file has
            // been copied and verified off the main thread.
            if (clipChanged || shouldReplaceLatestImageSuggestion(timeStamp, false)) {
                latestImageSuggestion = null
            }

            managerScope.launch {
                try {
                    if (shouldShowImages) {
                        recentImageClip(clipData)?.let { imageClip ->
                            val cachedUri = cacheImageClip(imageClip) ?: return@launch
                            clipboardDao?.addClip(
                                timeStamp,
                                false,
                                encodeImageHistoryClip(cachedUri, imageClip.mimeType, imageClip.label)
                            )
                            latinIME.mHandler.post {
                                rememberImageSuggestion(imageClip.copy(uri = cachedUri))
                                refreshClipboardSuggestion()
                            }
                            return@launch
                        }
                    }
                    if (clipData.description?.hasMimeType("text/*") == false) return@launch
                    val content = clipData.getItemAt(0)?.coerceToText(latinIME) ?: return@launch
                    if (TextUtils.isEmpty(content)) return@launch
                    clipboardDao?.addClip(timeStamp, false, truncateClipboardText(content.toString()))
                } catch (e: Exception) {
                    Log.e("ClipboardHistoryManager", "Failed to persist primary clip", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ClipboardHistoryManager", "Failed to fetch primary clip safely", e)
        }
    }

    private fun refreshClipboardSuggestion() {
        latinIME.mHandler.post {
            if (latinIME.isInputViewShown && latinIME.hasSuggestionStripView()) {
                latinIME.setNeutralSuggestionStrip()
            }
        }
    }

    private fun truncateClipboardText(text: String): String {
        if (text.codePointCount(0, text.length) <= MAX_CLIPBOARD_TEXT_CODEPOINTS) return text
        val end = text.offsetByCodePoints(0, MAX_CLIPBOARD_TEXT_CODEPOINTS)
        Log.w("ClipboardHistoryManager", "Truncated oversized clipboard text to $MAX_CLIPBOARD_TEXT_CODEPOINTS code points")
        return text.substring(0, end)
    }

    fun toggleClipPinned(id: Long) {
        managerScope.launch { clipboardDao?.togglePinned(id) }
    }

    fun clearHistory() {
        managerScope.launch { clipboardDao?.clearNonPinned() }
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = clipboardDao?.isPinned(index) == false

    fun removeEntry(index: Int) {
        if (canRemove(index))
            managerScope.launch { clipboardDao?.deleteClipAt(index) }
    }

    fun sortHistoryEntries() {
        managerScope.launch { clipboardDao?.sortAndNotify() }
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() {
        managerScope.launch { clipboardDao?.clearOldClips(true) }
    }

    fun getHistorySize() = clipboardDao?.count() ?: 0

    fun getHistoryEntry(position: Int) = clipboardDao?.getAt(position)

    fun getHistoryEntryContent(id: Long) = clipboardDao?.get(id)

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        historyChangeListener = listener
        managerScope.launch {
            clipboardDao?.setHistoryChangeListener(listener)
            if (listener == null) {
                // Deferred retention/quota cleanup is safe once RecyclerView no longer owns item
                // positions, and remains on the same serialized IO queue as all other DAO work.
                clipboardDao?.clearOldClips(true)
            }
        }
    }

    fun retrieveClipboardContent(): CharSequence {
        val clipData = clipboardManager.primaryClip ?: return ""
        if (clipData.itemCount == 0) return ""
        return clipData.getItemAt(0)?.coerceToText(latinIME) ?: ""
    }

    private fun isClipSensitive(inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    private sealed class RecentClip(open val timeStamp: Long) {
        data class Text(val content: CharSequence, override val timeStamp: Long) : RecentClip(timeStamp)
        data class Image(
            val uri: Uri,
            val mimeType: String,
            val label: String,
            override val timeStamp: Long
        ) : RecentClip(timeStamp)
    }

    private fun ClipDescription.imageMimeType(): String? {
        for (i in 0 until mimeTypeCount) {
            val mimeType = getMimeType(i)
            if (ClipDescription.compareMimeTypes(mimeType, "image/*")) return mimeType
        }
        return null
    }

    private fun recentImageClip(clipData: android.content.ClipData): RecentClip.Image? {
        if (clipData.itemCount == 0) return null
        val uri = clipData.imageUri() ?: return null
        val description = clipData.description
        val descriptionMimeType = description?.imageMimeType()
            ?.takeUnless { it == "image/*" }
        val resolverMimeType = latinIME.contentResolver.getType(uri)
            ?.takeIf { ClipDescription.compareMimeTypes(it, "image/*") }
        val mimeType = descriptionMimeType ?: resolverMimeType ?: description?.imageMimeType()
            ?: return null
        val labelText = description?.label?.toString().orEmpty()
        val uriText = uri.toString()
        val label = if (
            labelText.contains("screenshot", ignoreCase = true)
            || uriText.contains("screenshot", ignoreCase = true)
        ) {
            "Screenshot"
        } else {
            "Image"
        }
        return RecentClip.Image(uri, normalizeImageMimeType(mimeType).mimeType, label, ClipboardManagerCompat.getClipTimestamp(clipData))
    }

    private fun rememberImageSuggestion(clip: RecentClip.Image) {
        latestImageSuggestion = clip
        dontShowCurrentSuggestion = false
    }

    private fun shouldReplaceLatestImageSuggestion(timeStamp: Long, clipChanged: Boolean): Boolean {
        val imageClip = latestImageSuggestion ?: return true
        return clipChanged || timeStamp >= imageClip.timeStamp
    }

    private fun latestRecentImageSuggestion(inputType: Int): RecentClip.Image? {
        if (!canAccessScreenshotImages()) {
            latestImageSuggestion = null
            return null
        }
        if (InputTypeUtils.isPasswordInputType(inputType) || InputTypeUtils.isNumberInputType(inputType)) {
            return null
        }
        val imageClip = latestImageSuggestion ?: return null
        if (System.currentTimeMillis() - imageClip.timeStamp > RECENT_TIME_MILLIS) {
            latestImageSuggestion = null
            return null
        }
        return imageClip
    }

    private fun android.content.ClipData.imageUri(): Uri? {
        for (i in 0 until itemCount) {
            val item = getItemAt(i) ?: continue
            item.uri?.let { return it }
            item.intent?.data?.let { return it }
        }
        return null
    }

    private fun recentClip(editorInfo: EditorInfo?): RecentClip? {
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        latestRecentImageSuggestion(inputType)?.let { return it }
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val description = clipData.description ?: return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null

        if (canAccessScreenshotImages()) recentImageClip(clipData)?.let { imageClip ->
            if (InputTypeUtils.isPasswordInputType(inputType) || InputTypeUtils.isNumberInputType(inputType)) {
                return null
            }
            return imageClip
        }

        if (!description.hasMimeType("text/*")) return null
        val content = clipData.getItemAt(0)?.coerceToText(latinIME) ?: return null
        if (TextUtils.isEmpty(content)) return null
        if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null
        return RecentClip.Text(content, timeStamp)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        // maybe no need to create a new view
        // but a cache has to consider a few possible changes, so better don't implement without need
        clipboardSuggestionView = null

        // get the content, or return null
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null) return null
        val clip = recentClip(editorInfo) ?: return null
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL

        // Check if the keyboard is initialized before trying to access its icons set
        val keyboard = latinIME.mKeyboardSwitcher.keyboard ?: return null

        // create the view
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        val preview = binding.clipboardSuggestionPreview
        KeyboardTypeface.applyToTextView(textView)
        val clipIcon = keyboard.mIconsSet.getIconDrawable(ToolbarKey.PASTE.name.lowercase())

        when (clip) {
            is RecentClip.Text -> {
                preview.isGone = true
                textView.text = (if (isClipSensitive(inputType)) "*".repeat(clip.content.length) else clip.content)
                    .take(200) // truncate displayed text for performance reasons
                textView.setCompoundDrawablesRelativeWithIntrinsicBounds(clipIcon, null, null, null)
                textView.setOnClickListener {
                    dontShowCurrentSuggestion = true
                    latinIME.onTextInput(clip.content.toString())
                    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
                    binding.root.isGone = true
                }
            }
            is RecentClip.Image -> {
                textView.text = clip.label
                textView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                preview.isGone = false
                preview.configureCircularPreview()
                preview.load(clip.uri)
                binding.root.setOnClickListener { pasteImageClip(clip, binding.root, it) }
                textView.setOnClickListener { pasteImageClip(clip, binding.root, it) }
                preview.setOnClickListener { pasteImageClip(clip, binding.root, it) }
            }
        }
        val closeButton = binding.clipboardSuggestionClose
        closeButton.setImageDrawable(keyboard.mIconsSet.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
        closeButton.setOnClickListener { removeClipboardSuggestion() }

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        if (clip is RecentClip.Text) clipIcon?.let { colors.setColor(it, ColorType.KEY_ICON) }
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun ImageView.configureCircularPreview() {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
    }

    private fun pasteImageClip(clip: RecentClip.Image, chipView: View, feedbackView: View) {
        dontShowCurrentSuggestion = true
        // A content URI may point at a remote/large provider. Copy it on the manager IO scope,
        // then touch the editor and views only on the IME thread.
        managerScope.launch {
            val cachedUri = cacheImageClip(clip) ?: return@launch
            latinIME.mHandler.post {
                val pasted = latinIME.commitKlipyContent(
                    cachedUri,
                    clip.label,
                    normalizeImageMimeType(clip.mimeType).mimeType
                )
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                    KeyCode.NOT_SPECIFIED,
                    feedbackView,
                    HapticEvent.KEY_PRESS
                )
                if (pasted) chipView.isGone = true
            }
        }
    }

    private suspend fun cacheImageClip(clip: RecentClip.Image): Uri? {
        resolveOwnClipboardCacheFile(latinIME, clip.uri)?.let { cachedFile ->
            return if (cachedFile.exists() && cachedFile.length() > 0L) clip.uri else null
        }

        val mimeInfo = normalizeImageMimeType(clip.mimeType)
        val clipboardDir = File(latinIME.cacheDir, "clipboard").apply { mkdirs() }
        val imageFile = File(clipboardDir, cacheFileNameForSource(clip.uri, clip.timeStamp, mimeInfo.mimeType))
        if (!imageFile.exists() || imageFile.length() == 0L) {
            if (!copyImageClipAtomically(clip.uri, imageFile)) {
                return null
            }
        }
        return FileProvider.getUriForFile(latinIME, "${latinIME.packageName}.fileprovider", imageFile)
    }

    fun pasteHistoryEntry(entry: ClipboardHistoryEntry): Boolean {
        val clip = decodeImageHistoryClip(entry.text) ?: return false
        return latinIME.commitKlipyContent(clip.uri, clip.label, normalizeImageMimeType(clip.mimeType).mimeType)
    }

    private suspend fun copyImageClipAtomically(sourceUri: Uri, imageFile: File): Boolean {
        val tempFile = File.createTempFile("${imageFile.nameWithoutExtension}_", ".tmp", imageFile.parentFile)
        return try {
            latinIME.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copiedBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copiedBytes += count
                        if (copiedBytes > MAX_CLIPBOARD_IMAGE_BYTES) {
                            throw IOException("Clipboard image exceeds $MAX_CLIPBOARD_IMAGE_BYTES bytes")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return false
            if (tempFile.length() == 0L || tempFile.length() > MAX_CLIPBOARD_IMAGE_BYTES) return false

            moveClipboardImageIntoPlace(tempFile, imageFile)
            imageFile.length() > 0L
        } catch (e: CancellationException) {
            // Cancellation on IME teardown is expected. The finally block removes the temporary
            // file and the existing cache entry remains untouched.
            throw e
        } catch (e: Exception) {
            Log.e("ClipboardHistoryManager", "Failed to cache image clipboard clip", e)
            false
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                // A temporary cache path must not be copied to release diagnostics.
                Log.e("ClipboardHistoryManager", "Failed to delete a temporary clipboard image")
            }
        }
    }

    /**
     * Same-directory atomic replacement keeps an existing clipboard image readable while an
     * update is in progress. On a filesystem without atomic replacement we keep the old file and
     * fail the new copy rather than deleting the user's last valid cached image.
     */
    private fun moveClipboardImageIntoPlace(tempFile: File, imageFile: File) {
        try {
            Files.move(
                tempFile.toPath(),
                imageFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            if (imageFile.exists() || !tempFile.renameTo(imageFile)) {
                throw IOException("Could not atomically finalize clipboard image cache")
            }
        }
    }

    private fun removeClipboardSuggestion() {
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        data class ImageMimeInfo(val mimeType: String, val extension: String)

        private const val PREF_PROCESSED_SCREENSHOT_MEDIA_URIS = "clipboard_processed_screenshot_media_uris"
        private const val PREF_LAST_SCREENSHOT_MEDIA_URI = "clipboard_last_screenshot_media_uri"
        private const val PREF_LAST_SCREENSHOT_DATE_ADDED = "clipboard_last_screenshot_date_added"
        private const val SCREENSHOT_RECENT_WINDOW_SECONDS = 30L
        private const val SCREENSHOT_DEBOUNCE_MILLIS = 500L
        private const val MAX_SCREENSHOT_ROWS_TO_CHECK = 10
        private const val MAX_PROCESSED_SCREENSHOT_URIS = 20
        private const val MAX_CLIPBOARD_IMAGE_BYTES = 10L * 1024L * 1024L
        private const val MAX_CLIPBOARD_TEXT_CODEPOINTS = 100_000
        private val SCREENSHOT_RELATIVE_PATHS = listOf(
            "Pictures/Screenshots/",
            "DCIM/Screenshots/",
            "Screenshots/"
        )
        private var dontShowCurrentSuggestion: Boolean = false
        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)

        fun decodeImageHistoryClip(text: String) = ClipboardImageHistoryClip.decode(text)

        fun encodeImageHistoryClip(uri: Uri, mimeType: String, label: String) =
            ClipboardImageHistoryClip.encode(uri, mimeType, label)

        internal fun normalizeImageMimeType(mimeType: String?): ImageMimeInfo {
            val normalized = mimeType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.US)
                .orEmpty()
            return when (normalized) {
                "image/jpg", "image/pjpeg", "image/jpeg" -> ImageMimeInfo("image/jpeg", "jpg")
                "image/x-png", "image/png", "image/*" -> ImageMimeInfo("image/png", "png")
                "image/x-webp", "image/webp" -> ImageMimeInfo("image/webp", "webp")
                "image/gif" -> ImageMimeInfo("image/gif", "gif")
                "image/heic", "image/heic-sequence" -> ImageMimeInfo("image/heic", "heic")
                "image/heif", "image/heif-sequence" -> ImageMimeInfo("image/heif", "heif")
                "image/x-ms-bmp", "image/bmp" -> ImageMimeInfo("image/bmp", "bmp")
                else -> {
                    if (!normalized.startsWith("image/")) return ImageMimeInfo("image/png", "png")
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(normalized)
                        ?: normalized.substringAfter("image/")
                            .substringBefore('+')
                            .let(::sanitizeImageExtension)
                    ImageMimeInfo(normalized, extension.ifBlank { "img" })
                }
            }
        }

        internal fun cacheFileNameForSource(sourceUri: Uri, timeStamp: Long, mimeType: String): String {
            val extension = normalizeImageMimeType(mimeType).extension
            return "clip_${timeStamp}_${stableCacheToken(sourceUri)}.$extension"
        }

        internal fun isOwnClipboardCacheUri(context: Context, uri: Uri): Boolean {
            return resolveOwnClipboardCacheFile(context, uri) != null
        }

        private fun resolveOwnClipboardCacheFile(context: Context, uri: Uri): File? {
            if (uri.scheme != "content" || uri.authority != "${context.packageName}.fileprovider") return null
            val segments = uri.pathSegments
            if (segments.size < 3 || segments[0] != "cache" || segments[1] != "clipboard") return null
            val clipboardDir = File(context.cacheDir, "clipboard")
            val file = File(context.cacheDir, segments.drop(1).joinToString(File.separator))
            return try {
                val canonicalDir = clipboardDir.canonicalFile
                val canonicalFile = file.canonicalFile
                val dirPath = canonicalDir.path
                val filePath = canonicalFile.path
                if (filePath.startsWith("$dirPath${File.separator}")) canonicalFile else null
            } catch (_: Exception) {
                null
            }
        }

        private fun sanitizeImageExtension(extension: String): String {
            return extension
                .lowercase(Locale.US)
                .filter { it.isLetterOrDigit() }
                .take(12)
                .ifBlank { "img" }
        }

        private fun stableCacheToken(uri: Uri): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") {
                java.lang.String.format(Locale.US, "%02x", it.toInt() and 0xff)
            }
        }
    }
}
