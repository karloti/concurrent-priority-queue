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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Wrapper that forces Reference Equality.
 * This saves millions of .equals() calls inside StateFlow, making it extremely fast.
 */
private class PendingBuffer<T>(val items: PersistentList<T>)

/**
 * A concurrent priority queue implementation that is lock-free and non-blocking for producers.
 * It uses a serialized consumer approach (using a spin-lock) to safely update the internal state.
 *
 * @param T The type of elements in the queue.
 * @param K The type of the unique key used for deduplication.
 * @property maxSize The maximum capacity of the queue. Defaults to 5.
 * @property priorityComparator The comparator to determine the order of elements.
 * @property uniqueKeySelector A function to extract a unique key from an element.
 */
class ConcurrentPriorityQueue<T, K>(
    private val maxSize: Int = 5,
    private val priorityComparator: Comparator<T>,
    private val uniqueKeySelector: (T) -> K
) {
    // 1. Atomic Lock-Free buffer
    private val pendingBuffer = MutableStateFlow(PendingBuffer<T>(persistentListOf()))

    // 2. Atomic Spin-Lock (Controls who processes data without sleeping threads)
    private val isProcessing = MutableStateFlow(false)

    // 3. Internal state - exposed for tests
    internal var persistentMap: PersistentMap<K, T> = persistentMapOf()
    private var persistentList: PersistentList<T> = persistentListOf()

    private val _items = MutableStateFlow<List<T>>(persistentListOf())
    
    /**
     * A [StateFlow] exposing the current list of items in the queue, sorted by priority.
     */
    val items: StateFlow<List<T>> = _items.asStateFlow()

    /**
     * Adds an item to the queue.
     * 
     * This method is fully Lock-Free, Non-Blocking, and truly asynchronous.
     * It can be called from any thread instantly without suspension.
     *
     * @param item The item to add.
     */
    fun add(item: T) {
        if (maxSize <= 0) return

        // O(1) addition to the transit buffer
        pendingBuffer.update { PendingBuffer(it.items.add(item)) }

        // Try to drain and process the buffer
        drain()
    }

    private fun drain() {
        var keepDraining = true
        while (keepDraining) {
            // Elect a "Leader" (Processor). Only one thread wins!
            if (isProcessing.compareAndSet(expect = false, update = true)) {
                try {
                    while (true) {
                        val batch = extractBatch()
                        if (batch.isEmpty()) break
                        processBatch(batch)
                    }
                } finally {
                    isProcessing.value = false
                }

                // Race Condition Protection:
                // If someone added an element exactly while we were unlocking,
                // we loop again to ensure we don't leave it hanging.
                if (pendingBuffer.value.items.isEmpty()) {
                    keepDraining = false
                }
            } else {
                // If another thread is already the Leader and processing data,
                // our coroutine simply FINISHES IMMEDIATELY (Fire-and-forget).
                // We don't wait, we don't block the processor!
                keepDraining = false
            }
        }
    }

    private fun extractBatch(): PersistentList<T> {
        while (true) {
            val current = pendingBuffer.value
            if (current.items.isEmpty()) return current.items

            val empty = PendingBuffer<T>(persistentListOf())
            // Atomically empty the buffer and take the list
            if (pendingBuffer.compareAndSet(current, empty)) {
                return current.items
            }
        }
    }

    private fun processBatch(batch: PersistentList<T>) {
        persistentMap = persistentMap.mutate { mutableMap ->
            persistentList = persistentList.mutate { mutableList ->

                for (item in batch) {
                    val itemKey = uniqueKeySelector(item)
                    val existingItem = mutableMap[itemKey]

                    if (existingItem != null && priorityComparator.compare(item, existingItem) >= 0) continue
                    if (mutableList.size >= maxSize && existingItem == null) {
                        val last = mutableList.lastOrNull()
                        if (last != null && priorityComparator.compare(item, last) >= 0) continue
                    }

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
                    mutableMap[itemKey] = item

                    if (mutableList.size > maxSize) {
                        val evictedItem = mutableList.removeLast()
                        mutableMap.remove(uniqueKeySelector(evictedItem))
                    }
                }
            }
        }
        _items.value = persistentList
    }

    companion object {
        /**
         * Creates a [ConcurrentPriorityQueue] for [Comparable] types with a custom identity key.
         * Uses a descending sorting order by default (higher values have higher priority).
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
