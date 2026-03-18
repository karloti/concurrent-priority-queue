/*
 * Copyright 2026 Kaloyan Karaivanov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)

import kotlinx.collections.immutable.*
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A lock-free, thread-safe bounded priority queue that maintains a sorted list of top elements
 * while guaranteeing uniqueness based on a specific identity key.
 *
 * It utilizes atomic Compare-And-Swap (CAS) operations via [MutableStateFlow] to maintain
 * high performance across multiple concurrent writers and readers without blocking threads.
 *
 * @param T The type of elements held in this queue.
 * @param K The type of the unique key used to identify elements and prevent duplicates.
 * @property maxSize The maximum number of elements the queue can hold.
 * @property priorityComparator Defines the sorting order of the elements. Elements that sort smaller
 * (i.e., appear earlier in the order) are considered to have a higher priority.
 * @property uniqueKeySelector A lambda function that extracts a unique identity key from an element [T].
 * If an element with an identical key is already present in the queue, the new element is ignored.
 */
class ConcurrentPriorityQueue<T, K>(
    private val maxSize: Int = 5,
    private val priorityComparator: Comparator<T>,
    private val uniqueKeySelector: (T) -> K,
) {
    /**
     * Represents the internal atomic state of the queue, keeping the sorted list
     * and the set of unique keys perfectly synchronized.
     */
    internal data class QueueState<T, K>(
        val items: PersistentList<T> = persistentListOf(),
        val persistentMap: PersistentMap<K, T> = persistentMapOf()
    )

    internal val _state = MutableStateFlow(QueueState<T, K>())

    private val writeMutex = Mutex()

    /**
     * A highly optimized, read-only [StateFlow] exposing the current top elements.
     * It strictly guarantees immutability for downstream consumers (e.g., UI components)
     * preventing accidental modifications or the need for defensive copying.
     */
    val items: StateFlow<ImmutableList<T>> = object : StateFlow<ImmutableList<T>> {
        override val replayCache: List<ImmutableList<T>> get() = listOf(_state.value.items)
        override val value: ImmutableList<T> get() = _state.value.items
        override suspend fun collect(collector: FlowCollector<ImmutableList<T>>): Nothing {
            _state.collect { collector.emit(it.items) }
        }
    }

    /**
     * Attempts to add a new item to the bounded priority queue concurrently.
     *
     * The insertion process follows these rules:
     * 1. If an item with the same unique key already exists, the new item is discarded.
     * 2. If the queue is at maximum capacity and the new item has a lower or equal priority
     * compared to the lowest-priority item currently in the queue, it is discarded.
     * 3. If the item qualifies, it is inserted at the correct sorted position. If this insertion
     * exceeds the [maxSize], the lowest-priority item is evicted, and its unique key is freed.
     *
     * @param item The element to be evaluated and potentially added to the queue.
     */
    suspend fun add(item: T) {
        if (maxSize <= 0) return
        val itemKey = uniqueKeySelector(item)

        // Използваме Mutex вместо _state.update
        writeMutex.withLock {
            val currentState = _state.value
            val existingItem = currentState.persistentMap[itemKey]

            // Fast path 1
            if (existingItem != null && priorityComparator.compare(item, existingItem) >= 0) return

            val last = currentState.items.lastOrNull()
            val compare = last?.let { priorityComparator.compare(item, last) }

            // Fast path 2
            if (currentState.items.size >= maxSize && compare != null && compare >= 0) {
                if (existingItem == null) return
            }

            var evictedItem: T? = null

            // Persistent мутациите - сега те ще се изпълнят точно ВЕДНЪЖ за всеки елемент,
            // без никакви CAS повторения и излишни алокации!
            val newItems = currentState.items.mutate { mutableList ->
                if (existingItem != null) {
                    mutableList.remove(existingItem)
                }

                val searchResult = mutableList.binarySearch(item, priorityComparator)
                var insertIndex = if (searchResult < 0) -(searchResult + 1) else searchResult

                while (insertIndex < mutableList.size && priorityComparator.compare(
                        mutableList[insertIndex],
                        item
                    ) == 0
                ) {
                    insertIndex++
                }

                mutableList.add(insertIndex, item)

                if (mutableList.size > maxSize) {
                    evictedItem = mutableList.removeLast()
                }
            }

            val newPersistentMap = currentState.persistentMap.mutate { mutableMap ->
                mutableMap[itemKey] = item
                evictedItem?.let { evicted ->
                    mutableMap.remove(uniqueKeySelector(evicted))
                }
            }

            // Записваме новото състояние (Readers продължават да четат без блокиране!)
            _state.value = QueueState(newItems, newPersistentMap)
        }
    }

    companion object {
        /**
         * Creates a [ConcurrentPriorityQueue] providing full control over the element type, sorting, and its unique key.
         *
         * @param maxSize The maximum capacity of the queue. Defaults to 5.
         * @param priorityComparator Defines the sorting order of the elements.
         * @param uniqueKeySelector A lambda function that extracts a unique identity key from an element [T].
         */
        operator fun <T, K> invoke(
            maxSize: Int = 5,
            priorityComparator: Comparator<T>,
            uniqueKeySelector: (T) -> K
        ): ConcurrentPriorityQueue<T, K> {
            return ConcurrentPriorityQueue(maxSize, priorityComparator, uniqueKeySelector)
        }

        /**
         * Creates a [ConcurrentPriorityQueue] for [Comparable] types with a custom identity key.
         * Uses a descending sorting order by default.
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
         * Uses a descending sorting order by default.
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