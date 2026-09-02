// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.ClipboardImageHistoryClip
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 possible extension for later: allow non-text
 setting whether to allow it at all (because it could be slow with large files)
 separate retention time setting
 add mime type column
 add file name column
 add hash column (sha 256) for quick unique check (check full content on hash conflict)
 more sophisticated content loading: some getContent that reads the file, with cache
 async file reads and writes
 caches should be dropped on low memory
 */

/** Thread-safe cached access to the clipboard table. Screenshot imports use an IO scope while
 * normal clipboard callbacks arrive on the IME thread, so every cache/SQLite mutation is serialized. */
class ClipboardDao private constructor(private val db: Database, context: Context) {
    /**
     * A quota state deliberately contains only aggregate values. It is safe to surface in the
     * clipboard UI because it never carries a copied text value, image URI, file name, or label.
     */
    data class PinnedQuotaState(
        val entryQuotaReached: Boolean,
        val imageQuotaReached: Boolean,
    ) {
        val isQuotaReached: Boolean
            get() = entryQuotaReached || imageQuotaReached

        companion object {
            val NONE = PinnedQuotaState(
                entryQuotaReached = false,
                imageQuotaReached = false,
            )
        }
    }

    interface Listener {
        fun onClipInserted(position: Int)
        fun onClipsRemoved(position: Int, count: Int)
        fun onClipMoved(oldPosition: Int, newPosition: Int)

        /** Called after an asynchronously-created DAO has supplied its initial cache snapshot. */
        fun onClipboardHistoryReady() = Unit

        /**
         * Pinned records cannot be removed automatically. This lets the visible clipboard panel
         * explain why old unpinned records may disappear instead of silently discarding pins.
         */
        fun onPinnedClipboardQuotaChanged(state: PinnedQuotaState) = Unit
    }

    @Volatile
    private var listener: Listener? = null
    // Do not retain Context in the process-wide DAO cache. The two immutable values below
    // contain all file/provider information this DAO needs and cannot retain an IME Activity.
    private val clipboardCacheDir = File(context.applicationContext.cacheDir, "clipboard")
    private val fileProviderAuthority = "${context.applicationContext.packageName}.fileprovider"
    private val mainHandler = Handler(Looper.getMainLooper())

    // we clean up old clips when a new clip is added, but not too frequently
    private var lastClearOldClips = 0L

    // cache is loaded at start and never dropped
    private val cache = mutableListOf<ClipboardHistoryEntry>().apply {
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TEXT),
            null,
            null,
            null,
            null,
            "$COLUMN_PINNED, $COLUMN_TIMESTAMP DESC" // was only relevant in the initial approach of using a cursor instead of a cache
        ).use {
            while (it.moveToNext()) {
                add(ClipboardHistoryEntry(it.getLong(0), it.getLong(1), it.getInt(2) != 0, it.getString(3)))
            }
        }
        sort()
    }
    private var lastPinnedQuotaState = PinnedQuotaState.NONE

    /**
     * This is intentionally a DAO operation rather than an assignment from the UI thread.
     * ClipboardHistoryManager invokes it on its single IO queue so an initial RecyclerView never
     * observes an empty placeholder merely because opening the database is still in progress.
     */
    @Synchronized
    fun setHistoryChangeListener(listener: Listener?) {
        this.listener = listener
        if (listener != null) {
            val quotaState = currentPinnedQuotaState()
            lastPinnedQuotaState = quotaState
            notifyOnMain { it.onClipboardHistoryReady() }
            notifyOnMain { it.onPinnedClipboardQuotaChanged(quotaState) }
        }
    }

    @Synchronized
    fun addClip(timestamp: Long, pinned: Boolean, text: String) {
        clearOldClips()
        val existingIndex = cache.indexOfFirst { it.text == text }
        if (existingIndex >= 0 && cache[existingIndex].timeStamp == timestamp)
            return // nothing to do
        if (existingIndex >= 0) {
            updateTimestampAt(existingIndex, timestamp)
            return
        }
        insertNewEntry(timestamp, pinned, text)
    }

    private fun insertNewEntry(timestamp: Long, pinned: Boolean, text: String) {
        val cv = ContentValues(3)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        cv.put(COLUMN_PINNED, pinned)
        cv.put(COLUMN_TEXT, text)
        val rowId = db.writableDatabase.insert(TABLE, null, cv)

        val entry = ClipboardHistoryEntry(rowId, timestamp, pinned, text)
        cache.add(entry)
        cache.sort()
        enforceStorageLimits()
        publishPinnedQuotaState()
        cache.indexOf(entry).takeIf { it >= 0 }?.let(::notifyClipInserted)
    }

    private fun updateTimestampAt(index: Int, timestamp: Long) {
        val entry = cache[index]
        entry.timeStamp = timestamp
        cache.sort()
        notifyClipMoved(index, cache.indexOf(entry))
        val cv = ContentValues(1)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null)
    }

    @Synchronized
    fun isPinned(index: Int) = cache[index].isPinned

    @Synchronized
    fun getAt(index: Int) = cache[index]

    @Synchronized
    fun get(id: Long) = cache.first { it.id == id }

    @Synchronized
    fun count() = cache.size

    @Synchronized
    fun sort() = cache.sort()

    /** Reorders the cached snapshot then asks the visible RecyclerView to rebind it on main. */
    @Synchronized
    fun sortAndNotify() {
        cache.sort()
        notifyOnMain { it.onClipboardHistoryReady() }
    }

    @Synchronized
    fun togglePinned(id: Long) {
        val entry = cache.first { it.id == id }
        entry.isPinned = !entry.isPinned
        entry.timeStamp = System.currentTimeMillis()
        if (listener != null) {
            val oldPos = cache.indexOf(entry)
            cache.sort()
            val newPos = cache.indexOf(entry)
            notifyClipMoved(oldPos, newPos)
        } else {
            cache.sort()
        }
        val cv = ContentValues(2)
        cv.put(COLUMN_PINNED, entry.isPinned)
        cv.put(COLUMN_TIMESTAMP, entry.timeStamp)
        db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null)
        enforceStorageLimits()
        publishPinnedQuotaState()
    }

    // RecyclerView initiates this, so we don't call listener (or we'll get an IndexOutOfRangeException from RecyclerView)
    @Synchronized
    fun deleteClipAt(index: Int) {
        val entry = cache[index]
        cache.remove(entry)
        db.writableDatabase.delete(TABLE, "$COLUMN_ID = ${entry.id}", null)
        deleteCachedImageFiles(listOf(entry))
        publishPinnedQuotaState()
        notifyClipsRemoved(index, 1)
    }

    @Synchronized
    fun clearOldClips(now: Boolean = false) {
        if (listener != null)
            return // never clear when clipboard is visible
        if (!now && lastClearOldClips > SystemClock.elapsedRealtime() - 5 * 1000)
            return

        lastClearOldClips = SystemClock.elapsedRealtime()
        val retentionTime = Settings.getValues()?.mClipboardHistoryRetentionTime ?: 121L
        if (retentionTime > 120) {
            enforceStorageLimits()
            publishPinnedQuotaState()
            return
        }
        val minTime = System.currentTimeMillis() - retentionTime * 60 * 1000L
        val entriesToRemove = cache.filter { it.timeStamp < minTime && !it.isPinned }
        if (entriesToRemove.isNotEmpty()) {
            removeEntriesWithoutVisibleListener(entriesToRemove)
        }
        enforceStorageLimits()
        publishPinnedQuotaState()
    }

    @Synchronized
    fun clearNonPinned() {
        val entriesToRemove = cache.filter { !it.isPinned }
        if (entriesToRemove.isEmpty())
            return // nothing to remove

        if (listener != null) {
            val indicesToRemove = mutableListOf<Int>()
            cache.forEachIndexed { idx, clip ->
                if (!clip.isPinned)
                    indicesToRemove.add(idx)
            }
            cache.removeAll(entriesToRemove.toSet())
            notifyClipsRemoved(indicesToRemove[0], indicesToRemove.size)
        } else {
            cache.removeAll(entriesToRemove.toSet())
        }
        db.writableDatabase.delete(TABLE, "$COLUMN_PINNED = 0", null)
        deleteCachedImageFiles(entriesToRemove)
        publishPinnedQuotaState()
    }

    @Synchronized
    fun clear() {
        if (count() == 0) return
        val entriesToRemove = cache.toList()
        val removedCount = entriesToRemove.size
        cache.clear()
        notifyClipsRemoved(0, removedCount)
        db.writableDatabase.delete(TABLE, null, null)
        deleteCachedImageFiles(entriesToRemove)
        publishPinnedQuotaState()
    }

    private fun deleteCachedImageFiles(entries: Collection<ClipboardHistoryEntry>) {
        entries.forEach { entry ->
            val uri = ClipboardImageHistoryClip.decode(entry.text)?.uri ?: return@forEach
            val file = cachedImageFile(uri) ?: return@forEach
            if (file.exists() && !file.delete()) {
                // File paths can encode content/provider data. The cache role is enough here.
                Log.e(TAG, "Can't delete a cached clipboard image")
            }
        }
    }

    /**
     * Keep history bounded even when retention is disabled. Pinned records are never silently
     * deleted; if they alone exceed a quota the user can still unpin/delete them deliberately.
     * We defer quota trimming while the RecyclerView owns positional callbacks, matching the
     * existing retention policy and avoiding invalid adapter positions.
     */
    private fun enforceStorageLimits() {
        if (listener != null) return
        trimEntryCount()
        trimImageCacheBytes()
    }

    /**
     * File metadata is read only from the manager's serialized IO queue. The threshold is
     * inclusive: when pins fill a quota exactly, the next unpinned clip would otherwise appear to
     * vanish without an explanation.
     */
    private fun currentPinnedQuotaState(): PinnedQuotaState {
        var pinnedEntries = 0
        var pinnedImageBytes = 0L
        cache.asSequence()
            .filter { it.isPinned }
            .forEach { entry ->
                pinnedEntries++
                val uri = ClipboardImageHistoryClip.decode(entry.text)?.uri ?: return@forEach
                val file = cachedImageFile(uri)?.takeIf { it.isFile } ?: return@forEach
                pinnedImageBytes = (pinnedImageBytes + file.length()).coerceAtMost(Long.MAX_VALUE)
            }
        return pinnedQuotaStateFor(pinnedEntries, pinnedImageBytes)
    }

    private fun publishPinnedQuotaState() {
        val state = currentPinnedQuotaState()
        if (state == lastPinnedQuotaState) return
        lastPinnedQuotaState = state
        notifyOnMain { it.onPinnedClipboardQuotaChanged(state) }
    }

    private fun trimEntryCount() {
        val excess = cache.size - MAX_HISTORY_ENTRIES
        if (excess <= 0) return
        val removable = cache.filter { !it.isPinned }.sortedBy { it.timeStamp }.take(excess)
        if (removable.isNotEmpty()) removeEntriesWithoutVisibleListener(removable)
        if (cache.size > MAX_HISTORY_ENTRIES) {
            Log.w(TAG, "Pinned clipboard entries exceed the $MAX_HISTORY_ENTRIES item quota")
        }
    }

    private fun trimImageCacheBytes() {
        val imageEntries = cache.mapNotNull { entry ->
            val uri = ClipboardImageHistoryClip.decode(entry.text)?.uri ?: return@mapNotNull null
            val file = cachedImageFile(uri)?.takeIf { it.isFile } ?: return@mapNotNull null
            entry to file
        }
        var total = imageEntries.sumOf { (_, file) -> file.length() }
        if (total <= MAX_IMAGE_CACHE_BYTES) return
        val removable = mutableListOf<ClipboardHistoryEntry>()
        imageEntries.asSequence()
            .filter { (entry, _) -> !entry.isPinned }
            .sortedBy { (entry, _) -> entry.timeStamp }
            .forEach { (entry, file) ->
            if (total <= MAX_IMAGE_CACHE_BYTES) return@forEach
            total -= file.length()
            removable.add(entry)
        }
        if (removable.isNotEmpty()) removeEntriesWithoutVisibleListener(removable)
        if (total > MAX_IMAGE_CACHE_BYTES) {
            Log.w(TAG, "Pinned clipboard images exceed the ${MAX_IMAGE_CACHE_BYTES / (1024 * 1024)} MiB quota")
        }
    }

    private fun removeEntriesWithoutVisibleListener(entries: Collection<ClipboardHistoryEntry>) {
        if (entries.isEmpty()) return
        val ids = entries.joinToString(",") { it.id.toString() }
        cache.removeAll(entries.toSet())
        db.writableDatabase.delete(TABLE, "$COLUMN_ID IN ($ids)", null)
        deleteCachedImageFiles(entries)
    }

    private fun notifyClipInserted(position: Int) = notifyOnMain { it.onClipInserted(position) }

    private fun notifyClipsRemoved(position: Int, count: Int) = notifyOnMain {
        it.onClipsRemoved(position, count)
    }

    private fun notifyClipMoved(oldPosition: Int, newPosition: Int) = notifyOnMain {
        it.onClipMoved(oldPosition, newPosition)
    }

    private fun notifyOnMain(action: (Listener) -> Unit) {
        val currentListener = listener ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action(currentListener)
        } else {
            mainHandler.post {
                // A panel may have been closed/replaced while an IO task was in flight.
                listener?.let(action)
            }
        }
    }

    private fun cachedImageFile(uri: Uri): File? {
        val clipboardDir = clipboardCacheDir
        val file = when (uri.scheme) {
            "content" -> {
        if (uri.authority != fileProviderAuthority) return null
                val segments = uri.pathSegments
                if (segments.size < 3 || segments[0] != "cache" || segments[1] != "clipboard") return null
            File(clipboardCacheDir.parentFile, segments.drop(1).joinToString(File.separator))
            }
            "file" -> File(uri.path ?: return null)
            else -> return null
        }
        return try {
            val canonicalDir = clipboardDir.canonicalFile
            val canonicalFile = file.canonicalFile
            val dirPath = canonicalDir.path
            val filePath = canonicalFile.path
            if (filePath.startsWith("$dirPath${File.separator}")) canonicalFile else null
        } catch (e: Exception) {
            // Do not log a clipboard URI: its path can contain private provider data.
            Log.e(TAG, "Can't resolve a cached clipboard image", e)
            null
        }
    }

    companion object {
        private const val TAG = "ClipboardDao"
        private const val MAX_HISTORY_ENTRIES = 200
        private const val MAX_IMAGE_CACHE_BYTES = 100L * 1024L * 1024L

        /** Kept pure so the boundary behavior is covered without a database or user data. */
        internal fun pinnedQuotaStateFor(
            pinnedEntryCount: Int,
            pinnedImageBytes: Long,
        ): PinnedQuotaState = PinnedQuotaState(
            entryQuotaReached = pinnedEntryCount >= MAX_HISTORY_ENTRIES,
            imageQuotaReached = pinnedImageBytes >= MAX_IMAGE_CACHE_BYTES,
        )

        /** One process-wide lane for clipboard SQLite and cache-file work. */
        internal val storageDispatcher = Dispatchers.IO.limitedParallelism(1)
        private val maintenanceScope = CoroutineScope(SupervisorJob() + storageDispatcher)

        /**
         * Runs maintenance initiated outside a live IME (for example, Settings) on the same
         * serialized IO lane as the active ClipboardHistoryManager.
         */
        fun runSerialized(context: Context, operation: ClipboardDao.() -> Unit) {
            val applicationContext = context.applicationContext
            maintenanceScope.launch {
                getInstance(applicationContext)?.operation()
            }
        }

        private const val TABLE = "CLIPBOARD"
        // it's possible timestamp is not unique, so we use a separate ID
        // ID is generated and returned on insert, see https://sqlite.org/rowidtable.html
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_PINNED = "PINNED"
        private const val COLUMN_TEXT = "TEXT" // we could enforce unique text, but that's only necessary if we can drop the cache (later)
        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED TINYINT NOT NULL,
                $COLUMN_TEXT TEXT
            )
        """

        private var instance: ClipboardDao? = null

        /** Returns the instance or creates a new one. Returns null if instance can't be created (e.g. no access to db due to device being locked) */
        @Synchronized
        fun getInstance(context: Context): ClipboardDao? {
            if (instance == null)
                try {
                    instance = ClipboardDao(Database.getInstance(context), context)
                } catch (e: Throwable) {
                    Log.e(TAG, "can't create ClipboardDao", e)
                }
            return instance
        }
    }
}
