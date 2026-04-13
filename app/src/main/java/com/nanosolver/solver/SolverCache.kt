package com.nanosolver.solver

/**
 * SolverCache — Phase 4
 *
 * An in-memory LRU (Least Recently Used) cache that maps a cleaned math
 * expression string to its computed answer.
 *
 * WHY CACHE AT ALL?
 *   Matiks Sprint mode shows ~30–60 problems per minute. Many problems repeat
 *   across sessions (there are only so many "easy" sums). A cache hit costs
 *   ~0.01ms (HashMap lookup) vs ~120ms for OCR + parsing. For repeated problems
 *   this eliminates the entire solve step.
 *
 * WHY LRU AND NOT A PLAIN HASHMAP?
 *   A plain HashMap grows unboundedly. After a long session the cache could hold
 *   thousands of entries. LRU bounds memory use while keeping the most recently
 *   seen problems — exactly the ones most likely to appear again soon.
 *
 * HOW LinkedHashMap IMPLEMENTS LRU:
 *   LinkedHashMap(capacity, loadFactor, accessOrder = true) maintains entries
 *   in access order: get() and put() move the accessed entry to the "tail".
 *   The "head" is always the least recently used entry.
 *   Overriding removeEldestEntry() lets us auto-evict the head when size exceeds
 *   MAX_ENTRIES — one line of code for a complete LRU cache.
 *
 * THREAD SAFETY:
 *   All public methods are @Synchronized. The capture pipeline calls solve() on
 *   Dispatchers.Default (a thread pool), so we need the lock.
 */
object SolverCache {

    private const val MAX_ENTRIES = 200

    private val cache = object : LinkedHashMap<String, Long>(
        MAX_ENTRIES + 1,   // initial capacity slightly above max to avoid a resize at the limit
        0.75f,
        true               // accessOrder = true → LRU ordering
    ) {
        // Called by put() after each insertion. If we've exceeded the limit,
        // LinkedHashMap automatically removes the eldest (least recently used) entry.
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>): Boolean =
            size > MAX_ENTRIES
    }

    /** Returns the cached answer for [expression], or null if not cached. */
    @Synchronized
    fun get(expression: String): Long? = cache[expression]

    /** Stores [answer] for [expression]. Evicts the LRU entry if cache is full. */
    @Synchronized
    fun put(expression: String, answer: Long) {
        cache[expression] = answer
    }

    /** Current number of cached entries. */
    @Synchronized
    val size: Int get() = cache.size

    /** Clears all entries (useful for testing). */
    @Synchronized
    fun clear() = cache.clear()
}
