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

@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * A concurrent, lock-free priority queue implementation.
 * * It uses a serialized consumer approach via an atomic reference (spin-lock)
 * to safely and asynchronously update its internal state without blocking producers.
 *
 * Uses a persistent treap internally for O(log n) insert/remove operations
 * without the O(n) index update overhead of list-based approaches.
 *
 * @param T The type of elements held in the queue.
 * @param K The type of the unique identity key for the elements.
 * @property maxSize The maximum allowed capacity of the queue. Defaults to 5.
 * @property comparator The comparator used to determine the priority order of elements.
 * @property uniqueKeySelector A selector function to extract a unique identity key from an element.
 */
class ConcurrentPriorityQueue<T, K>(
    private val maxSize: Int = 5,
    private val comparator: Comparator<T>,
    private val uniqueKeySelector: (T) -> K
) {
    internal val queueState = MutableStateFlow(
        PersistentIndexedPriorityList.empty(comparator, uniqueKeySelector)
    )

    /**
     * Returns a thread-safe, immutable view of the current items in the queue.
     */
    val items: StateFlow<List<T>> = object : StateFlow<List<T>> {
        override val replayCache: List<List<T>> get() = listOf(value)
        override val value: List<T> get() = queueState.value.toList()
        override suspend fun collect(collector: FlowCollector<List<T>>): Nothing {
            queueState.map { it.toList() }.collect(collector)
            error("StateFlow collection should never end")
        }
    }

    /**
     * Attempts to add an element to the queue.
     *
     * This method is fully lock-free, non-blocking, and truly asynchronous.
     * It efficiently handles deduplication by replacing existing elements if the new element
     * has a strictly better priority, and discards elements that fall outside the [maxSize] bounds.
     *
     * All operations are O(log n) thanks to the underlying treap structure.
     *
     * @param element The item to evaluate and potentially add to the queue.
     */
    fun add(element: T) {
        if (maxSize <= 0) return

        queueState.update { currentState ->
            val elementKey = uniqueKeySelector(element)

            // Fast-path: Queue is completely empty
            if (currentState.isEmpty()) {
                return@update currentState.insert(element)
            }

            val existingElement = currentState[elementKey]

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
            var updated = currentState.insert(element)

            // Evict the lowest priority element if the capacity is exceeded
            if (updated.size > maxSize) {
                updated = updated.removeLast()
            }

            updated
        }
    }

    /**
     * Batch adds multiple elements to the queue in a single atomic operation.
     *
     * This is more efficient than calling [add] multiple times in concurrent scenarios because:
     * 1. Single CAS operation instead of N separate ones (reduces contention)
     * 2. Pre-filters duplicates keeping only best priority per key within the batch
     * 3. All changes are applied atomically
     *
     * All deduplication and priority comparison rules from [add] apply.
     *
     * @param elements The items to evaluate and potentially add to the queue.
     */
    fun addAll(elements: Iterable<T>) {
        if (maxSize <= 0) return

        val elementsList = when (elements) {
            is List<T> -> elements
            is Collection<T> -> elements.toList()
            else -> elements.toList()
        }
        if (elementsList.isEmpty()) return

        queueState.update { currentState ->
            // Pre-filter: keep only best priority per key within the batch
            val bestByKey = HashMap<K, T>(minOf(elementsList.size, maxSize * 2))
            for (element in elementsList) {
                val key = uniqueKeySelector(element)
                val existing = bestByKey[key]
                if (existing == null || comparator.compare(element, existing) < 0) {
                    bestByKey[key] = element
                }
            }

            var updated = currentState

            // Insert each element using the same logic as add()
            for ((key, element) in bestByKey) {
                val existingElement = updated[key]

                // Skip if existing has equal or better priority
                if (existingElement != null && comparator.compare(element, existingElement) >= 0) {
                    continue
                }

                // Skip if queue is full and element is worse than worst
                if (updated.size >= maxSize && existingElement == null) {
                    val lowestPriorityElement = updated.last()
                    if (lowestPriorityElement != null && comparator.compare(element, lowestPriorityElement) >= 0) {
                        continue
                    }
                }

                // Insert the element
                updated = updated.insert(element)

                // Evict if needed
                if (updated.size > maxSize) {
                    updated = updated.removeLast()
                }
            }

            updated
        }
    }

    /**
     * Asynchronously batch adds multiple elements using parallel processing across CPU cores.
     *
     * This suspend function parallelizes the pre-filtering phase across multiple cores,
     * then applies the filtered results in a single atomic CAS operation.
     *
     * **When to use:**
     * - Large batches (10,000+ elements) where pre-filtering overhead is significant
     * - When caller is already in a coroutine context
     * - CPU-bound filtering with complex key extraction or comparison
     *
     * **Trade-offs:**
     * - Additional overhead for small batches due to coroutine creation
     * - Requires suspend context
     * - Best performance with Default dispatcher
     *
     * @param elements The items to evaluate and potentially add to the queue.
     * @param parallelism Number of parallel chunks to process. Defaults to 4 (reasonable for most systems).
     */
    suspend fun addAllAsync(
        elements: Iterable<T>,
        parallelism: Int = DEFAULT_PARALLELISM
    ) {
        if (maxSize <= 0) return

        val elementsList = when (elements) {
            is List<T> -> elements
            is Collection<T> -> elements.toList()
            else -> elements.toList()
        }
        if (elementsList.isEmpty()) return

        // For small batches, use synchronous addAll
        if (elementsList.size < 1000 || parallelism <= 1) {
            addAll(elementsList)
            return
        }

        // Phase 1: Parallel pre-filtering - find best priority per key in each chunk
        val chunkSize = (elementsList.size + parallelism - 1) / parallelism
        val chunks = elementsList.chunked(chunkSize)

        val partialResults = coroutineScope {
            chunks.map { chunk ->
                async(Dispatchers.Default) {
                    val localBest = HashMap<K, T>(chunk.size / 2 + 1)
                    for (element in chunk) {
                        val key = uniqueKeySelector(element)
                        val existing = localBest[key]
                        if (existing == null || comparator.compare(element, existing) < 0) {
                            localBest[key] = element
                        }
                    }
                    localBest
                }
            }.awaitAll()
        }

        // Phase 2: Merge partial results (sequential - typically small after dedup)
        val mergedBest = HashMap<K, T>(partialResults.sumOf { it.size })
        for (partial in partialResults) {
            for ((key, element) in partial) {
                val existing = mergedBest[key]
                if (existing == null || comparator.compare(element, existing) < 0) {
                    mergedBest[key] = element
                }
            }
        }

        // Phase 3: Single atomic CAS update
        queueState.update { currentState ->
            var updated = currentState

            for ((key, element) in mergedBest) {
                val existingElement = updated[key]

                // Skip if existing has equal or better priority
                if (existingElement != null && comparator.compare(element, existingElement) >= 0) {
                    continue
                }

                // Skip if queue is full and element is worse than worst
                if (updated.size >= maxSize && existingElement == null) {
                    val lowestPriorityElement = updated.last()
                    if (lowestPriorityElement != null && comparator.compare(element, lowestPriorityElement) >= 0) {
                        continue
                    }
                }

                // Insert the element
                updated = updated.insert(element)

                // Evict if needed
                if (updated.size > maxSize) {
                    updated = updated.removeLast()
                }
            }

            updated
        }
    }

    companion object {
        /** Default parallelism for async operations */
        const val DEFAULT_PARALLELISM = 4

        /**
         * Creates a [ConcurrentPriorityQueue] for [Comparable] types with a custom identity key.
         * By default, it uses a descending sorting order (higher values imply higher priority).
         *
         * @param maxSize The maximum capacity of the queue. Defaults to 5.
         * @param uniqueKeySelector A lambda function that extracts a unique identity key from an element [T].
         */
        operator fun <T : Comparable<T>, K> invoke(
            maxSize: Int = 5,
            uniqueKeySelector: (T) -> K
        ): ConcurrentPriorityQueue<T, K> {
            return ConcurrentPriorityQueue(maxSize, reverseOrder(), uniqueKeySelector)
        }

        /**
         * Creates a [ConcurrentPriorityQueue] where the elements themselves act as their own unique identity keys.
         *
         * @param maxSize The maximum capacity of the queue. Defaults to 5.
         * @param priorityComparator Defines the sorting order of the elements.
         */
        operator fun <T> invoke(
            maxSize: Int = 5,
            priorityComparator: Comparator<T>
        ): ConcurrentPriorityQueue<T, T> {
            return ConcurrentPriorityQueue(maxSize, priorityComparator) { it }
        }

        /**
         * Creates a [ConcurrentPriorityQueue] for [Comparable] types where the elements themselves act as their own unique identity keys.
         * By default, it uses a descending sorting order.
         *
         * @param maxSize The maximum capacity of the queue. Defaults to 5.
         */
        operator fun <T : Comparable<T>> invoke(
            maxSize: Int = 5
        ): ConcurrentPriorityQueue<T, T> {
            return ConcurrentPriorityQueue(maxSize, reverseOrder()) { it }
        }
    }
}