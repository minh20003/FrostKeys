// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

/**
 * Main-thread state machine for a Klipy search.
 *
 * Klipy responses can complete in a different order from the requests that created them.  Keeping
 * the query, tab and a monotonically increasing generation together prevents an old result from
 * replacing the visible page or being appended to a newer search.
 */
internal class KlipyRequestState {
    data class Key(
        val query: String,
        val tab: String,
        val generation: Long,
    )

    data class PageRequest(
        val key: Key,
        val page: Int,
    )

    private var generation = 0L
    private var activeKey: Key? = null
    private var committedPage = 0
    private var hasMorePages = false

    /** Starts a completely new search. Page one is not considered loaded until [accept] succeeds. */
    fun begin(query: String, tab: String): PageRequest {
        val key = Key(query = query, tab = tab, generation = ++generation)
        activeKey = key
        committedPage = 0
        hasMorePages = true
        return PageRequest(key, page = 1)
    }

    /** Invalidates every in-flight response without changing the current UI by itself. */
    fun invalidate() {
        generation++
        activeKey = null
        committedPage = 0
        hasMorePages = false
    }

    fun isCurrent(request: PageRequest): Boolean = activeKey == request.key

    fun isCurrent(key: Key): Boolean = activeKey == key

    /** Returns the next page only after the preceding page was successfully committed. */
    fun nextPage(): PageRequest? {
        val key = activeKey ?: return null
        if (!hasMorePages) return null
        return PageRequest(key, committedPage + 1)
    }

    /**
     * Commits a successful response. Failed, cancelled, stale and out-of-order responses must not
     * call this method, which means the retry keeps the same page number.
     */
    fun accept(request: PageRequest, hasMore: Boolean): Boolean {
        if (activeKey != request.key || request.page != committedPage + 1) return false
        committedPage = request.page
        hasMorePages = hasMore
        return true
    }

    internal fun committedPageForTest(): Int = committedPage
}
