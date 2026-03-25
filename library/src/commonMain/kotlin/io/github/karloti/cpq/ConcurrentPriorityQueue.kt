/*
 * Copyright 2026 Kaloyan Karaivanov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(
    ExperimentalForInheritanceCoroutinesApi::class, ExperimentalCoroutinesApi::class,
    ExperimentalAtomicApi::class, FlowPreview::class
)
@file:Suppress("UNUSED")

package io.github.karloti.cpq

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * A concurrent, lock-free, bounded priority queue for Kotlin Multiplatform.
 *
 * This queue is backed by a persistent [Treap](https://en.wikipedia.org/wiki/Treap) data structure
 * combined with [MutableStateFlow] for thread-safe atomic updates using CAS (Compare-And-Swap).
 *
 * ## Key Features
 *
 * - **Lock-free concurrency**: Uses `MutableStateFlow.update()` with optimistic CAS for non-blocking updates
 * - **Persistent data structure**: Structural sharing enables efficient copy-on-write semantics
 * - **Bounded capacity**: Automatically evicts lowest-priority elements when [maxSize] is exceeded
 * - **Built-in deduplication**: Elements with the same key are automatically deduplicated (upsert behavior)
 * - **Reactive state**: Exposes [items] as `StateFlow<List<T>>` for seamless UI binding
 * - **O(log n) operations**: Insert, remove, and access operations are logarithmic
 * - **O(1) key lookup**: Fast element retrieval by key via internal hash map
 *
 * ## Thread Safety
 *
 * All public methods are thread-safe and can be called concurrently from multiple coroutines
 * or threads. The queue uses optimistic locking - concurrent modifications may cause retries
 * but never block.
 *
 * ## Example Usage
 *
 * ```kotlin
 * data class Task(val id: String, val priority: Int)
 *
 * val queue = ConcurrentPriorityQueue<Task, String>(
 *     maxSize = 10,
 *     comparator = compareBy { it.priority },  // Lower = higher priority
 *     uniqueKeySelector = { it.id }
 * )
 *
 * queue.add(Task("A", 5))
 * queue.add(Task("B", 1))
 * queue.add(Task("A", 3))  // Updates "A" with better priority
 *
 * println(queue.first())  // Task(id=B, priority=1)
 * ```
 *
 * @param T The type of elements held in the queue.
 * @param K The type of the unique identity key for deduplication.
 * @property maxSize Maximum queue capacity. Elements beyond this are evicted. Must be > 0.
 * @property comparator Defines priority order. First element in sorted order has highest priority.
 * @property uniqueKeySelector Extracts unique identity key from an element for deduplication.
 *
 * @see PersistentPriorityList The underlying persistent treap implementation
 * @see StateFlow For reactive state observation
 */
class ConcurrentPriorityQueue<T, K>(
    private val maxSize: Int = 5,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val comparator: Comparator<T>,
    private val uniqueKeySelector: (T) -> K
) : BoundedPriorityQueue<T, K> {
    /**
     * Internal state holder using [MutableStateFlow] for atomic CAS updates.
     * The state is a persistent (immutable) treap that supports structural sharing.
     */
    internal val queueState = MutableStateFlow(
        TreapPriorityList.empty(comparator, uniqueKeySelector)
    )

    /**
     * A reactive [StateFlow] containing the current queue elements as an immutable sorted list.
     *
     * The list is sorted according to the [comparator] - the first element has the highest priority.
     * Each access returns a snapshot that is safe to iterate without synchronization.
     *
     * ## Usage with Jetpack Compose
     *
     * ```kotlin
     * @Composable
     * fun LeaderboardScreen(queue: ConcurrentPriorityQueue<Score, String>) {
     *     val scores by queue.items.collectAsState()
     *     LazyColumn {
     *         items(scores) { score -> ScoreRow(score) }
     *     }
     * }
     * ```
     *
     * @return A [StateFlow] that emits the current list whenever the queue changes.
     */
    override val items: StateFlow<List<T>> = object : StateFlow<List<T>> {
        override val replayCache: List<List<T>> get() = listOf(value)
        override val value: List<T> get() = queueState.value.toList()
        override suspend fun collect(collector: FlowCollector<List<T>>): Nothing {
            queueState.collect { collector.emit(it.toList()) }
        }
    }

    /**
     * The current number of elements in the queue.
     *
     * This is an O(1) operation that returns a snapshot of the size at the moment of access.
     *
     * @return The number of elements currently in the queue (0 to [maxSize]).
     */
    override val size: Int get() = queueState.value.size

    /**
     * Attempts to add an element to the queue.
     *
     * This method is lock-free and uses optimistic CAS updates. It implements "upsert" semantics:
     *
     * - If the key doesn't exist and the queue has space: the element is added, `null` is returned.
     * - If the key doesn't exist but the queue is full: the element replaces the lowest-priority
     *   element if it has strictly better priority; the evicted element is returned. Otherwise the
     *   element is rejected and `null` is returned.
     * - If the key exists with worse priority: the element replaces the existing one, `null` is
     *   returned (no eviction occurs because the size does not change).
     * - If the key exists with equal or better priority: the element is rejected, `null` is returned.
     *
     * ## Complexity
     *
     * - **Time**: O(log n) for treap insert/remove operations
     * - **Space**: O(log n) new nodes due to structural sharing
     *
     * ## Example
     *
     * ```kotlin
     * val queue = ConcurrentPriorityQueue<Int>(maxSize = 3)
     * queue.add(10)  // null  - added, no eviction
     * queue.add(20)  // null  - added, no eviction
     * queue.add(5)   // null  - added, no eviction
     * queue.add(1)   // null  - rejected (worse than all, queue full)
     * queue.add(15)  // 5     - added, evicts the lowest-priority element (5)
     * ```
     *
     * @param element The element to add to the queue.
     * @return The evicted element if a lower-priority element was displaced to make room for
     *   [element]; `null` if the element was added or updated without eviction, or was rejected.
     */
    override fun add(element: T): T? {
        if (maxSize <= 0) return null

        val key = uniqueKeySelector(element)
        var removed: T? = null

        queueState.update { currentState ->
            // Fast-path: Queue is completely empty
            if (currentState.isEmpty()) {
                return@update currentState.add(element)
            }

            val existingElement = currentState.getByKey(key)

            // Fast-path: Element exists but has equal or better priority already
            if (existingElement != null && comparator.compare(element, existingElement) >= 0) {
                return@update currentState
            }

            // Fast-path: Queue is full and the new element is worse than or equal to the lowest priority element
            if (currentState.size >= maxSize && existingElement == null) {
                val lowestPriorityElement = currentState.last()
                if (lowestPriorityElement != null && comparator.compare(element, lowestPriorityElement) >= 0) {
                    return@update currentState
                }
            }

            // Insert the element (handles removal of existing key automatically)
            var updated = currentState.add(element)

            // Evict the lowest priority element if the capacity is exceeded
            if (updated.size > maxSize) {
                removed = updated.last()
                updated = updated.removeLast()
            }

            updated
        }

        return removed
    }

    /**
     * Checks if the queue contains an element with the specified key.
     *
     * This is an O(1) operation using the internal hash map.
     *
     * @param key The unique key to search for.
     * @return `true` if an element with this key exists, `false` otherwise.
     */
    override fun containsKey(key: K): Boolean = queueState.value.containsKey(key)

    /**
     * Checks if the queue contains the specified element.
     *
     * Lookup is performed by extracting the key using [uniqueKeySelector].
     * This is an O(1) operation.
     *
     * @param element The element to search for.
     * @return `true` if the element exists in the queue, `false` otherwise.
     */
    override fun contains(element: T): Boolean = containsKey(uniqueKeySelector(element))

    /**
     * Retrieves an element by its unique key.
     *
     * This is an O(1) operation using the internal hash map.
     *
     * ## Example
     *
     * ```kotlin
     * val queue = ConcurrentPriorityQueue<Task, String>(...) { it.id }
     * queue.add(Task("task-1", 10))
     * val task = queue["task-1"]  // Task(id=task-1, priority=10)
     * ```
     *
     * @param key The unique key of the element to retrieve.
     * @return The element if found, or `null` if no element with this key exists.
     */
    override operator fun get(key: K): T? = queueState.value.getByKey(key)

    /**
     * Returns the highest priority element without removing it.
     *
     * The "highest priority" element is the first element according to the [comparator].
     * This is an O(log n) operation (traverses to leftmost node in treap).
     *
     * @return The highest priority element, or `null` if the queue is empty.
     */
    override fun first(): T? = queueState.value.first()

    /**
     * Returns the lowest priority element without removing it.
     *
     * The "lowest priority" element is the last element according to the [comparator].
     * This is an O(log n) operation (traverses to rightmost node in treap).
     *
     * @return The lowest priority element, or `null` if the queue is empty.
     */
    override fun last(): T? = queueState.value.last()

    /**
     * Removes and returns the highest priority element.
     *
     * This is equivalent to `first()` followed by `removeByKey()`.
     * The operation is O(log n) and thread-safe.
     *
     * ## Example
     *
     * ```kotlin
     * val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
     * queue.add(10)
     * queue.add(20)
     * val top = queue.poll()  // 20 (if descending order)
     * ```
     *
     * @return The removed highest priority element, or `null` if the queue is empty.
     */
    override fun poll(): T? {
        var result: T? = null
        queueState.update { currentState ->
            val first = currentState.first() ?: return@update currentState
            result = first
            currentState.removeByKey(uniqueKeySelector(first))
        }
        return result
    }

    /**
     * Removes an element by its unique key.
     *
     * This is an O(log n) operation and thread-safe.
     *
     * @param key The unique key of the element to remove.
     * @return `true` if an element was removed, `false` if no element with this key existed.
     */
    override fun removeByKey(key: K): Boolean {
        var removed = false
        queueState.update { currentState ->
            if (!currentState.containsKey(key)) return@update currentState
            removed = true
            currentState.removeByKey(key)
        }
        return removed
    }

    /**
     * Removes a specific element from the queue.
     *
     * The element is identified by its key (extracted via [uniqueKeySelector]).
     * This is an O(log n) operation.
     *
     * @param element The element to remove.
     * @return `true` if the element was removed, `false` if it didn't exist.
     */
    override fun remove(element: T): Boolean = removeByKey(uniqueKeySelector(element))

    /**
     * Removes all elements that match the given predicate.
     *
     * This operation is atomic - either all matching elements are removed or none
     * (in case of concurrent modification, the operation retries).
     *
     * ## Complexity
     *
     * - **Time**: O(k × log n) where k is the number of elements matching the predicate
     *
     * ## Example
     *
     * ```kotlin
     * val queue = ConcurrentPriorityQueue<Task, String>(...) { it.id }
     * queue.add(Task("a", 10))
     * queue.add(Task("b", 20))
     * queue.add(Task("c", 5))
     *
     * // Remove all tasks with priority > 10
     * val removed = queue.removeIf { it.priority > 10 }  // 1
     * ```
     *
     * @param predicate A function that returns `true` for elements to be removed.
     * @return The number of elements removed.
     */
    override fun removeIf(predicate: (T) -> Boolean): Int {
        var removedCount = 0
        queueState.update { currentState ->
            val toRemove = currentState.filter(predicate)
            removedCount = toRemove.size
            if (toRemove.isEmpty()) {
                return@update currentState
            }
            currentState.removeAll(toRemove)
        }
        return removedCount
    }

    /**
     * Retains only the elements that match the given predicate, removing all others.
     *
     * This is the opposite of [removeIf] - elements for which the predicate returns `false`
     * are removed.
     *
     * ## Example
     *
     * ```kotlin
     * val queue = ConcurrentPriorityQueue<Task, String>(...) { it.id }
     * // ... add tasks ...
     *
     * // Keep only high-priority tasks
     * queue.retainIf { it.priority <= 10 }
     * ```
     *
     * @param predicate A function that returns `true` for elements to keep.
     * @return The number of elements removed.
     */
    override fun retainIf(predicate: (T) -> Boolean): Int {
        return removeIf { !predicate(it) }
    }

    /**
     * Removes all elements from the queue, leaving it empty.
     *
     * This operation is atomic and O(1).
     *
     * ## Example
     *
     * ```kotlin
     * queue.clear()
     * assert(queue.isEmpty())
     * assert(queue.size == 0)
     * ```
     */
    override fun clear() {
        queueState.update { currentState ->
            currentState.clear()
        }
    }

    /**
     * Checks if the queue contains no elements.
     *
     * This is an O(1) operation.
     *
     * @return `true` if the queue is empty, `false` otherwise.
     */
    override fun isEmpty(): Boolean = queueState.value.isEmpty()

    /**
     * Adds all elements from the given iterable collection to the queue.
     *
     * Elements are added one by one, following the same rules as [add]:
     * - Duplicate keys are handled via upsert semantics
     * - Lowest priority elements are evicted if capacity is exceeded
     *
     * ## Complexity
     *
     * - **Time**: O(m × log n) where m is the number of elements to add
     *
     * @param elements The iterable collection of elements to add.
     * @return A snapshot of the current queue contents as a [List] after all elements have been processed.
     */
    override fun addAll(elements: Iterable<T>): List<T> {
        for (element in elements) {
            add(element)
        }
        return queueState.value.toList()
    }

    /**
     * Adds all elements from the given sequence to the queue.
     *
     * Elements are added one by one, following the same rules as [add]:
     * - Duplicate keys are handled via upsert semantics
     * - Lowest priority elements are evicted if capacity is exceeded
     *
     * ## Complexity
     *
     * - **Time**: O(m × log n) where m is the number of elements to add
     *
     * @param elements The sequence of elements to add.
     * @return The number of elements processed without causing an eviction. This includes
     *   elements that were successfully added, updated (upsert), or rejected — any case
     *   where [add] returned `null`.
     */
    override fun addAll(elements: Sequence<T>): Int {
        var addedCount = 0
        for (element in elements) {
            if (add(element) == null) {
                addedCount++
            }
        }
        return addedCount
    }

    /**
     * Adds all elements from the given flow to the queue.
     *
     * Elements are added one by one as they are emitted by the flow, following the same rules as [add].
     * This method suspends until the flow is fully collected.
     *
     * ## Complexity
     *
     * - **Time**: O(m × log n) where m is the number of elements to add
     *
     * @param elements The flow of elements to add.
     * @return The number of elements whose insertion caused an eviction — i.e., the number
     *   of times [add] returned a non-null displaced element.
     */
    override suspend fun addAll(elements: Flow<T>): Int {
        var addedCount = 0
        elements.collect { element ->
            if (add(element) != null) {
                addedCount++
            }
        }
        return addedCount
    }

    /**
     * Adds elements from a flow to the queue with parallel processing and transformation.
     *
     * This method transforms elements from the source flow [elements] using the provided
     * suspending [transform] function with a specified level of [parallelism], then adds
     * the resulting elements to the queue. Multiple transformations can execute concurrently,
     * improving throughput when the transform function performs I/O or other async operations.
     *
     * The operation uses [kotlinx.coroutines.flow.flatMapMerge] internally to achieve
     * parallel processing while maintaining ordered collection into the queue.
     *
     * ## Thread Safety
     *
     * This method is thread-safe and can be called concurrently. The parallel transformations
     * run on the queue's [dispatcher] (typically [Dispatchers.Default]).
     *
     * ## Complexity
     *
     * - **Time**: O(m × log n) for queue operations, where m is the number of elements.
     *   The transformation overhead depends on the [transform] function.
     * - **Concurrency**: Up to [parallelism] transformations execute simultaneously
     *
     * ## Example
     *
     * ```kotlin
     * data class Task(val id: String, val priority: Int)
     * val queue = ConcurrentPriorityQueue<Task, String>(maxSize = 100) { it.id }
     *
     * val urls = flowOf("url1", "url2", "url3", "url4", "url5")
     * val evictedCount = queue.addAll(urls) { url ->
     *     fetchTaskFromNetwork(url) // Suspending network call
     * }
     * println("$evictedCount tasks caused evictions")
     * ```
     *
     * @param S The type of elements in the source flow.
     * @param elements The source flow to be processed and added to the queue.
     * @param parallelism The maximum number of concurrent [transform] invocations.
     *   Defaults to [DEFAULT_CONCURRENCY] (16). Must be positive.
     * @param transform A suspending function to convert source elements of type [S]
     *   to queue elements of type [T].
     * @return The number of elements whose insertion caused an eviction — i.e., the number
     *   of times [add] returned a non-null displaced element.
     */
    override suspend fun <S> addAll(
        elements: Flow<S>,
        parallelism: Int,
        transform: suspend (S) -> T
    ): Int = withContext(dispatcher) {
        val addedCount = AtomicInt(0)
        elements
            .flatMapMerge(parallelism) { source ->
                flow { emit(transform(source)) }
            }
            .collect { element ->
                if (add(element) != null) addedCount.incrementAndFetch()
            }
        addedCount.load()
    }


    /**
     * Returns an iterator over the elements in priority order (highest first).
     *
     * The iterator operates on a snapshot of the queue at the time of creation.
     * Modifications to the queue after iterator creation are not reflected.
     *
     * @return An iterator over the queue elements.
     */
    override operator fun iterator(): Iterator<T> = items.value.iterator()

    // ============================== Builder ================================

    /**
     * Returns a [ConcurrentPriorityQueueBuilder] initialized with the current
     * contents and configuration (maxSize, comparator, keySelector) of this queue.
     *
     * The builder allows efficient batch modifications without CAS overhead.
     * Once all changes have been applied, call [ConcurrentPriorityQueueBuilder.build]
     * to produce a new [ConcurrentPriorityQueue] reflecting the mutations.
     *
     * ```kotlin
     * val updated = queue.builder().apply {
     *     add(element1)
     *     add(element2)
     *     removeByKey(oldKey)
     * }.build()
     * ```
     *
     * @return a new [ConcurrentPriorityQueueBuilder] pre-populated with this queue's elements
     */
    fun builder(): ConcurrentPriorityQueueBuilder<T, K> = ConcurrentPriorityQueueBuilderImpl(
        maxSize = maxSize,
        comparator = comparator,
        keySelector = uniqueKeySelector,
        initial = queueState.value
    )

    /**
     * Applies [mutator] to a [ConcurrentPriorityQueueBuilder] pre-populated
     * with the contents of this queue and returns the resulting [ConcurrentPriorityQueue].
     *
     * This is a convenience wrapper around [builder] and [ConcurrentPriorityQueueBuilder.build]:
     *
     * ```kotlin
     * val updated = queue.mutate {
     *     add(element1)
     *     removeIf { it.score < 10 }
     *     poll()
     * }
     * ```
     *
     * @param mutator the block of mutations to apply
     * @return a new [ConcurrentPriorityQueue] reflecting all mutations
     */
    fun mutate(mutator: ConcurrentPriorityQueueBuilder<T, K>.() -> Unit): ConcurrentPriorityQueue<T, K> {
        return builder().apply(mutator).build()
    }

    companion object {
        /**
         * Creates a queue for [Comparable] types with a custom identity key.
         *
         * Uses **descending order** by default (higher values = higher priority).
         *
         * ## Example
         *
         * ```kotlin
         * data class Score(val odId: String, val points: Int) : Comparable<Score> {
         *     override fun compareTo(other: Score) = points.compareTo(other.points)
         * }
         *
         * val leaderboard = ConcurrentPriorityQueue<Score, String>(maxSize = 10) { it.odId }
         * ```
         *
         * @param T Element type (must implement [Comparable]).
         * @param K Key type for deduplication.
         * @param maxSize Maximum queue capacity. Defaults to 5.
         * @param uniqueKeySelector Function to extract unique key from element.
         * @return A new [ConcurrentPriorityQueue] instance.
         */
        operator fun <T : Comparable<T>, K> invoke(
            maxSize: Int = 5,
            comparator: Comparator<T> = naturalOrder(),
            uniqueKeySelector: (T) -> K
        ): ConcurrentPriorityQueue<T, K> {
            return ConcurrentPriorityQueue(
                maxSize = maxSize,
                comparator = comparator,
                uniqueKeySelector = uniqueKeySelector
            )
        }

        /**
         * Creates a queue with a custom comparator where elements are their own keys.
         *
         * Use this when elements don't need separate identity keys (no duplicates by value).
         *
         * ## Example
         *
         * ```kotlin
         * // Min-heap of integers (smallest first)
         * val minHeap = ConcurrentPriorityQueue<Int>(maxSize = 10, compareBy { it })
         *
         * // Max-heap of strings by length
         * val byLength = ConcurrentPriorityQueue<String>(maxSize = 5, compareByDescending { it.length })
         * ```
         *
         * @param T Element type (also used as key type).
         * @param maxSize Maximum queue capacity. Defaults to 5.
         * @param comparator Comparator defining priority order.
         * @return A new [ConcurrentPriorityQueue] instance.
         */
        operator fun <T> invoke(
            maxSize: Int = 5,
            comparator: Comparator<T>
        ): ConcurrentPriorityQueue<T, T> {
            return ConcurrentPriorityQueue(maxSize = maxSize, comparator = comparator) { it }
        }

        /**
         * Creates a queue for [Comparable] types where elements are their own keys.
         *
         * Uses **descending order** by default (higher values = higher priority).
         *
         * ## Example
         *
         * ```kotlin
         * // Top 5 highest integers
         * val top5 = ConcurrentPriorityQueue<Int>(maxSize = 5)
         * top5.add(10)
         * top5.add(50)
         * top5.add(30)
         * println(top5.items.value)  // [50, 30, 10]
         * ```
         *
         * @param T Element type (must implement [Comparable], also used as key type).
         * @param maxSize Maximum queue capacity. Defaults to 5.
         * @return A new [ConcurrentPriorityQueue] instance.
         */
        operator fun <T : Comparable<T>> invoke(
            maxSize: Int = 5,
            comparator: Comparator<T> = naturalOrder()
        ): ConcurrentPriorityQueue<T, T> {
            return ConcurrentPriorityQueue(maxSize = maxSize, comparator = comparator) { it }
        }
    }
}
