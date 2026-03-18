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

// Обвивка (Wrapper), която форсира Reference Equality.
// Това спестява милиони извиквания на .equals() вътре в StateFlow, правейки го мълниеносен.
private class PendingBuffer<T>(val items: PersistentList<T>)

class ConcurrentPriorityQueue<T, K>(
    private val maxSize: Int = 5,
    private val priorityComparator: Comparator<T>,
    private val uniqueKeySelector: (T) -> K
) {
    // 1. Атомарен Lock-Free буфер
    private val pendingBuffer = MutableStateFlow(PendingBuffer<T>(persistentListOf()))

    // 2. Атомарен Spin-Lock (Контролира кой обработва данните без да приспива нишки)
    private val isProcessing = MutableStateFlow(false)

    // 3. Вътрешно състояние - експозирано за тестовете
    internal var persistentMap: PersistentMap<K, T> = persistentMapOf()
    private var persistentList: PersistentList<T> = persistentListOf()

    private val _items = MutableStateFlow<List<T>>(persistentListOf())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    /**
     * Напълно Lock-Free, Non-Blocking, истински асинхронна функция.
     * Вече не е suspend! Можеш да я викаш отвсякъде мигновено.
     */
    fun add(item: T) {
        if (maxSize <= 0) return

        // O(1) добавяне в транзитния буфер
        pendingBuffer.update { PendingBuffer(it.items.add(item)) }

        // Опитваме да източим и обработим буфера
        drain()
    }

    private fun drain() {
        var keepDraining = true
        while (keepDraining) {
            // Избираме "Лидер" (Processor). Само една нишка печели!
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

                // Защита от Race Condition:
                // Ако някой е добавил елемент точно докато сме отключвали,
                // завъртаме цикъла отново, за да не го оставим висящ.
                if (pendingBuffer.value.items.isEmpty()) {
                    keepDraining = false
                }
            } else {
                // Ако друга нишка вече е Лидер и обработва данните,
                // нашата корутина просто ПРИКЛЮЧВА ВЕДНАГА (Fire-and-forget).
                // Не чакаме, не блокираме процесора!
                keepDraining = false
            }
        }
    }

    private fun extractBatch(): PersistentList<T> {
        while (true) {
            val current = pendingBuffer.value
            if (current.items.isEmpty()) return current.items

            val empty = PendingBuffer<T>(persistentListOf())
            // Изпразваме буфера атомарно и вземаме списъка
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