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
    ExperimentalForInheritanceCoroutinesApi::class,
    ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class, FlowPreview::class
)
@file:Suppress("UNUSED")

package io.github.karloti.cpq

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * A concurrent, lock-free, bounded priority queue for Kotlin Multiplatform.
 *
 * This queue is backed by a persistent [Treap](https://en.wikipedia.org/wiki/Treap) data structure
 * combined with [MutableStateFlow] for thread-safe atomic updates using CAS (Compare-And-Swap).
 *
 * ## Key Features
 *
 * - **Lock-free concurrency**: Uses `MutableStateFlow.update()` with optimistic CAS for non-blocking updates.
 * - **Persistent data structure**: Structural sharing enables efficient copy-on-write semantics.
 * - **Bounded capacity**: Automatically evicts lowest-priority elements when [maxSize] is exceeded.
 * - **Built-in deduplication**: Elements with the same key are automatically updated (upsert behavior).
 * - **Reactive state**: Exposes [items] as `StateFlow<List<T>>` for seamless UI binding.
 * - **O(log n) operations**: Insert, remove, and access operations are logarithmic.
 * - **O(1) key lookup**: Fast element retrieval by key via an internal hash map.
 *
 * ## Thread Safety
 *
 * All public methods are thread-safe and can be called concurrently from multiple coroutines
 * or threads. The queue uses optimistic locking — concurrent modifications may cause retries
 * but never block execution.
 *
 * ## Example Usage
 *
 * ```kotlin
 * data class Task(val id: String, val priority: Int)
 *
 * val queue = ConcurrentPriorityQueue<Task, String>(
 *     maxSize = 10,
 *     comparator = compareBy { it.priority },  // Lower priority value = higher priority (min-heap style)
 *     uniqueKeySelector = { it.id }
 * )
 *
 * queue.add(Task("A", 5))
 * queue.add(Task("B", 1))
 * queue.add(Task("A", 3))  // Updates "A" with better (lower) priority
 *
 * println(queue.first())  // Task(id=B, priority=1)
 * ```
 *
 * @param T The type of elements held in the queue.
 * @param K The type of the unique identity key for deduplication.
 * @property maxSize Maximum queue capacity. Elements beyond this are evicted. Must be > 0.
 * @param typeT Reflection type of elements.
 * @param typeK Reflection type of keys.
 * @property comparator Defines priority order. The first element in sorted order has the highest priority.
 * @property keySelector Extracts a unique identity key from an element for deduplication.
 *
 * @see TreapPriorityList The underlying persistent treap implementation.
 * @see StateFlow For reactive state observation.
 */
class ConcurrentPriorityQueue<T, K> @PublishedApi internal constructor(
    private val maxSize: Int = 5,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val typeT: KType,
    private val typeK: KType,
    private val comparator: Comparator<T>,
    private val keySelector: (T) -> K,
) : BoundedPriorityQueue<T, K> {

    init {
        require(maxSize > 0) { "maxSize must be positive" }
        require(typeT.classifier != null) { "Type T must be a concrete type" }
        require(typeK.classifier != null) { "Type K must be a concrete type" }
    }

    /**
     * Internal state holder using [MutableStateFlow] for atomic CAS updates.
     * The state is a persistent (immutable) treap that supports structural sharing.
     */
    internal val queueState = MutableStateFlow(
        TreapPriorityList.empty(comparator, keySelector)
    )

    /**
     * A reactive [StateFlow] containing the current queue elements as an immutable sorted list.
     *
     * The list is sorted according to the [comparator] — the first element has the highest priority.
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
     * The value ranges from 0 to [maxSize].
     */
    override val size: Int get() = queueState.value.size

    /**
     * Attempts to add an element to the queue.
     *
     * This method is lock-free and uses optimistic CAS updates. It implements "upsert" semantics:
     *
     * - **New key, space available**: The element is added, returns `null`.
     * - **New key, queue full**: The element replaces the lowest-priority element (the last one)
     *   if it has strictly better priority; returns the evicted element. Otherwise, the element is
     *   rejected and returns `null`.
     * - **Existing key, better priority**: The element replaces the existing one; returns `null`.
     * - **Existing key, worse or equal priority**: The element is rejected; returns `null`.
     *
     * ## Complexity
     *
     * - **Time**: O(log n) for treap insert/remove operations.
     * - **Space**: O(log n) new nodes due to structural sharing.
     *
     * ## Example
     *
     * ```kotlin
     * // Min-priority queue (smaller values first)
     * val queue = ConcurrentPriorityQueue<Int>(maxSize = 3)
     * queue.add(10)  // null  - added [10]
     * queue.add(20)  // null  - added [10, 20]
     * queue.add(5)   // null  - added [5, 10, 20]
     * queue.add(25)  // null  - rejected (worse than 20, queue full)
     * queue.add(15)  // 20    - added, evicts the lowest-priority element (20) -> [5, 10, 15]
     * ```
     *
     * @param element The element to add to the queue.
     * @return The evicted element if a lower-priority element was displaced; `null` if the
     *   element was added without eviction, updated, or rejected.
     */
    override fun add(element: T): T? {
        if (maxSize <= 0) return null

        val key = keySelector(element)
        var removed: T? = null

        queueState.update { currentState ->
            // Fast-path: Queue is completely empty
            if (currentState.isEmpty()) {
                removed = null
                return@update currentState.add(element)
            }

            val existingElement = currentState.getByKey(key)

            // Fast-path: Element exists but has equal or better priority already
            if (existingElement != null && comparator.compare(element, existingElement) >= 0) {
                removed = null
                return@update currentState
            }

            // Fast-path: Queue is full and the new element is worse than or equal to the lowest priority element
            if (currentState.size >= maxSize && existingElement == null) {
                val lowestPriorityElement = currentState.last()
                if (lowestPriorityElement != null && comparator.compare(element, lowestPriorityElement) >= 0) {
                    removed = null
                    return@update currentState
                }
            }

            // Insert the element (handles removal of existing key automatically)
            var updated = currentState.add(element)

            // Evict the lowest priority element if the capacity is exceeded
            if (updated.size > maxSize) {
                removed = updated.last()
                updated = updated.removeLast()
            } else {
                removed = null
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
     * Lookup is performed by extracting the key using [keySelector].
     * This is an O(1) operation.
     *
     * @param element The element to search for.
     * @return `true` if the element exists in the queue, `false` otherwise.
     */
    override fun contains(element: T): Boolean = containsKey(keySelector(element))

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
     * This is an O(log n) operation.
     *
     * @return The highest priority element, or `null` if the queue is empty.
     */
    override fun first(): T? = queueState.value.first()

    /**
     * Returns the lowest priority element without removing it.
     *
     * The "lowest priority" element is the last element according to the [comparator].
     * This is an O(log n) operation.
     *
     * @return The lowest priority element, or `null` if the queue is empty.
     */
    override fun last(): T? = queueState.value.last()

    /**
     * Removes and returns the highest priority element.
     *
     * This operation is O(log n) and thread-safe.
     *
     * ## Example
     *
     * ```kotlin
     * val queue = ConcurrentPriorityQueue<Int>(maxSize = 5) // Defaults to ascending (min-priority)
     * queue.add(20)
     * queue.add(10)
     * val top = queue.poll()  // 10
     * ```
     *
     * @return The removed highest priority element, or `null` if the queue is empty.
     */
    override fun poll(): T? {
        var result: T? = null
        queueState.update { currentState ->
            val first = currentState.first() ?: return@update currentState
            result = first
            currentState.removeByKey(keySelector(first))
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
     * The element is identified by its key (extracted via [keySelector]).
     * This is an O(log n) operation.
     *
     * @param element The element to remove.
     * @return `true` if the element was removed, `false` if it didn't exist.
     */
    override fun remove(element: T): Boolean = removeByKey(keySelector(element))

    /**
     * Removes all elements that match the given predicate.
     *
     * This operation is atomic — either all matching elements are removed or none.
     * In case of concurrent modification, the operation retries automatically.
     *
     * ## Complexity
     *
     * - **Time**: O(k × log n) where k is the number of elements matching the predicate.
     *
     * ## Example
     *
     * ```kotlin
     * val queue = ConcurrentPriorityQueue<Task, String>(...) { it.id }
     * queue.add(Task("a", 10))
     * queue.add(Task("b", 20))
     * queue.add(Task("c", 5))
     *
     * // Remove all tasks with priority > 15
     * val removedCount = queue.removeIf { it.priority > 15 }  // 1 ("b" removed)
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
     * This is the opposite of [removeIf].
     *
     * @param predicate A function that returns `true` for elements to keep.
     * @return The number of elements removed.
     */
    override fun retainIf(predicate: (T) -> Boolean): Int {
        return removeIf { !predicate(it) }
    }

    /**
     * Removes all elements from the queue.
     *
     * This operation is atomic and O(1).
     *
     * ## Example
     *
     * ```kotlin
     * queue.add(1)
     * queue.clear()
     * println(queue.isEmpty()) // true
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
     */
    override fun isEmpty(): Boolean = queueState.value.isEmpty()

    /**
     * Adds all elements from the given iterable collection to the queue.
     *
     * Elements are added one by one following the same rules as [add].
     *
     * @param elements The collection of elements to add.
     * @return A snapshot of the queue contents after all elements have been processed.
     */
    override fun addAll(elements: Iterable<T>): List<T> {
        for (element in elements) {
            add(element)
        }
        return queueState.value.toList()
    }

    /**
     * Adds all elements from the given iterable collection after applying a transformation.
     *
     * @param S The type of elements in the source collection.
     * @param elements The source collection to be transformed and added.
     * @param transform A function to convert source elements to type [T].
     * @return The number of elements whose insertion caused an eviction.
     */
    override fun <S> addAll(elements: Iterable<S>, transform: (S) -> T): Int {
        var evictionCount = 0
        for (element in elements) {
            if (add(transform(element)) != null) {
                evictionCount++
            }
        }
        return evictionCount
    }

    /**
     * Adds all elements from the given sequence to the queue.
     *
     * @param elements The sequence of elements to add.
     * @return The number of elements processed without causing an eviction.
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
     * Adds all elements from the given sequence after applying a transformation.
     *
     * @param S The type of elements in the source sequence.
     * @param elements The source sequence to be transformed and added.
     * @param transform A function to convert source elements to type [T].
     * @return The number of elements whose insertion caused an eviction.
     */
    override fun <S> addAll(elements: Sequence<S>, transform: (S) -> T): Int {
        var evictionCount = 0
        for (element in elements) {
            if (add(transform(element)) != null) {
                evictionCount++
            }
        }
        return evictionCount
    }

    /**
     * Adds all elements from the given flow to the queue.
     *
     * This method suspends until the flow is fully collected.
     *
     * @param elements The flow of elements to add.
     * @return The number of elements whose insertion caused an eviction.
     */
    override suspend fun addAll(elements: Flow<T>): Int {
        var evictionCount = 0
        elements.collect { element ->
            if (add(element) != null) {
                evictionCount++
            }
        }
        return evictionCount
    }

    /**
     * Adds elements from a flow to the queue with parallel transformations.
     *
     * This method transforms elements from [elements] using the provided [transform] function,
     * then adds them to the queue. Transformations execute concurrently, which is ideal
     * for I/O-bound operations.
     *
     * ## Thread Safety
     *
     * This method is thread-safe and runs transformations on the queue's [dispatcher].
     *
     * @param S The type of elements in the source flow.
     * @param elements The source flow to be processed.
     * @param transform A suspending function to convert source elements to type [T].
     * @return The number of elements whose insertion caused an eviction.
     */
    override suspend fun <S> addAll(
        elements: Flow<S>,
        transform: suspend (S) -> T
    ): Int = withContext(dispatcher) {
        val evictionCount = AtomicInt(0)
        val batchSize = WORKER_BATCH_SIZE
        val sourceChannel = Channel<S>(capacity = Channel.BUFFERED)
        coroutineScope {
            launch {
                elements.collect { sourceChannel.send(it) }
                sourceChannel.close()
            }

            repeat(DEFAULT_CONCURRENCY) {
                launch {
                    while (true) {
                        val first = sourceChannel.receiveCatching().getOrNull() ?: break
                        val batch = ArrayList<S>(batchSize)
                        batch.add(first)
                        while (batch.size < batchSize) {
                            sourceChannel.tryReceive().getOrNull()?.let { batch.add(it) } ?: break
                        }

                        if (batch.size <= DEFAULT_CONCURRENCY) {
                            for (source in batch) {
                                val element = transform(source)
                                if (add(element) != null) evictionCount.incrementAndFetch()
                            }
                        } else {
                            coroutineScope {
                                for (source in batch) {
                                    launch {
                                        val element = transform(source)
                                        if (add(element) != null) evictionCount.incrementAndFetch()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        evictionCount.load()
    }

    /**
     * Returns an iterator over the elements in priority order (highest first).
     *
     * The iterator operates on a snapshot of the queue at the time of creation.
     */
    override operator fun iterator(): Iterator<T> = items.value.iterator()

    // ============================== Builder ================================

    /**
     * Returns a [ConcurrentPriorityQueueBuilder] initialized with the current
     * contents and configuration of this queue.
     *
     * The builder allows efficient batch modifications without CAS overhead.
     *
     * ```kotlin
     * val updatedQueue = queue.builder().apply {
     *     add(element1)
     *     add(element2)
     *     removeByKey(oldKey)
     * }.build()
     * ```
     */
    fun builder(): ConcurrentPriorityQueueBuilder<T, K> {
        return ConcurrentPriorityQueueBuilderImpl(
            maxSize = maxSize,
            typeT = typeT,
            typeK = typeK,
            comparator = comparator,
            keySelector = keySelector,
            initial = queueState.value
        )
    }

    /**
     * Applies [mutator] to a builder pre-populated with this queue's contents
     * and returns a new [ConcurrentPriorityQueue] with the results.
     *
     * ```kotlin
     * val updated = queue.mutate {
     *     add(element1)
     *     removeIf { it.priority > 100 }
     *     poll()
     * }
     * ```
     */
    fun mutate(mutator: ConcurrentPriorityQueueBuilder<T, K>.() -> Unit): ConcurrentPriorityQueue<T, K> {
        return builder().apply(mutator).build()
    }

    companion object {
        private const val WORKER_BATCH_SIZE = 512

        /**
         * Creates a queue for [Comparable] types with a custom identity key.
         *
         * Uses **ascending order** by default (lower values = higher priority).
         *
         * ## Example
         *
         * ```kotlin
         * data class Task(val id: String, val deadline: Long) : Comparable<Task> {
         *     override fun compareTo(other: Task) = deadline.compareTo(other.deadline)
         * }
         *
         * // Tasks with earlier deadlines have higher priority
         * val scheduler = ConcurrentPriorityQueue<Task, String>(maxSize = 10) { it.id }
         * ```
         *
         * @param T Element type (must implement [Comparable]).
         * @param K Key type for deduplication.
         * @param maxSize Maximum queue capacity. Defaults to 5.
         * @param uniqueKeySelector Function to extract unique key from element.
         * @return A new [ConcurrentPriorityQueue] instance.
         */
        inline operator fun <reified T : Comparable<T>, reified K> invoke(
            maxSize: Int = 5,
            noinline uniqueKeySelector: (T) -> K
        ): ConcurrentPriorityQueue<T, K> {
            return ConcurrentPriorityQueue(
                maxSize = maxSize,
                typeT = typeOf<T>(),
                typeK = typeOf<K>(),
                comparator = compareBy { it },
                keySelector = uniqueKeySelector
            )
        }

        /**
         * Creates a queue with a custom comparator and identity key.
         *
         * @param T Element type.
         * @param K Key type for deduplication.
         * @param maxSize Maximum queue capacity. Defaults to 5.
         * @param comparator Defines priority order.
         * @param uniqueKeySelector Function to extract unique key from element.
         * @return A new [ConcurrentPriorityQueue] instance.
         */
        inline operator fun <reified T, reified K> invoke(
            maxSize: Int = 5,
            comparator: Comparator<T>,
            noinline uniqueKeySelector: (T) -> K
        ): ConcurrentPriorityQueue<T, K> {
            return ConcurrentPriorityQueue(
                maxSize = maxSize,
                typeT = typeOf<T>(),
                typeK = typeOf<K>(),
                comparator = comparator,
                keySelector = uniqueKeySelector
            )
        }

        /**
         * Creates a queue with a custom comparator where elements are their own keys.
         *
         * Use this when elements don't need separate identity keys.
         *
         * ## Example
         *
         * ```kotlin
         * // Max-heap of strings by length (longer strings first)
         * val byLength = ConcurrentPriorityQueue<String>(maxSize = 5, compareByDescending { it.length })
         * ```
         *
         * @param T Element type (also used as key type).
         * @param maxSize Maximum queue capacity. Defaults to 5.
         * @param comparator Comparator defining priority order.
         * @return A new [ConcurrentPriorityQueue] instance.
         */
        inline operator fun <reified T> invoke(
            maxSize: Int = 5,
            comparator: Comparator<T>
        ): ConcurrentPriorityQueue<T, T> {
            return ConcurrentPriorityQueue(
                maxSize = maxSize,
                typeT = typeOf<T>(),
                typeK = typeOf<T>(),
                comparator = comparator
            ) { it }
        }

        /**
         * Creates a queue for [Comparable] types where elements are their own keys.
         *
         * Uses **ascending order** by default (lower values = higher priority).
         *
         * ## Example
         *
         * ```kotlin
         * // Keep the 5 smallest integers
         * val minHeap = ConcurrentPriorityQueue<Int>(maxSize = 5)
         * minHeap.addAll(listOf(10, 50, 30, 5, 1))
         * println(minHeap.items.value)  // [1, 5, 10, 30, 50]
         * ```
         *
         * @param T Element type (must implement [Comparable], also used as key type).
         * @param maxSize Maximum queue capacity. Defaults to 5.
         * @return A new [ConcurrentPriorityQueue] instance.
         */
        inline operator fun <reified T : Comparable<T>> invoke(
            maxSize: Int = 5,
        ): ConcurrentPriorityQueue<T, T> {
            return ConcurrentPriorityQueue(
                maxSize = maxSize,
                typeT = typeOf<T>(),
                typeK = typeOf<T>(),
                comparator = compareBy { it }
            ) { it }
        }
    }
}
