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

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A concurrent, lock-free priority queue implementation.
 * * It uses a serialized consumer approach via an atomic reference (spin-lock)
 * to safely and asynchronously update its internal state without blocking producers.
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
    /**
     * Holds the immutable snapshot of the queue's state at any given time.
     * * @property elements The sorted list of elements based on priority.
     * @property elementsByKey A map for O(1) lookups of existing elements by their unique keys.
     */
    internal class Snapshot<T, K>(
        val elements: PersistentList<T>,
        val elementsByKey: PersistentMap<K, T>,
    )

    internal val queueState = MutableStateFlow<Snapshot<T, K>>(Snapshot(persistentListOf(), persistentMapOf()))

    /**
     * Returns a thread-safe, immutable view of the current items in the queue.
     */
    val items: List<T> get() = queueState.value.elements

    /**
     * Attempts to add an element to the queue.
     *
     * This method is fully lock-free, non-blocking, and truly asynchronous.
     * It efficiently handles deduplication by replacing existing elements if the new element
     * has a strictly better priority, and discards elements that fall outside the [maxSize] bounds.
     *
     * @param element The item to evaluate and potentially add to the queue.
     */
    fun add(element: T) {
        if (maxSize <= 0) return

        queueState.update { currentState ->
            val elementKey = uniqueKeySelector(element)

            // Fast-path: Queue is completely empty
            if (currentState.elements.isEmpty()) {
                return@update Snapshot(
                    elements = persistentListOf(element),
                    elementsByKey = persistentMapOf(elementKey to element)
                )
            }

            var updatedElements = currentState.elements
            var updatedElementsByKey = currentState.elementsByKey

            val existingElement = updatedElementsByKey[elementKey]

            // Fast-path: Element exists but has equal or better priority already
            if (existingElement != null && comparator.compare(element, existingElement) >= 0) {
                return@update currentState
            }

            // Fast-path: Queue is full and the new element is worse than or equal to the lowest priority element
            if (updatedElements.size >= maxSize && existingElement == null) {
                val lowestPriorityElement = updatedElements.lastOrNull()
                if (lowestPriorityElement != null && comparator.compare(element, lowestPriorityElement) >= 0) {
                    return@update currentState
                }
            }

            // Remove the existing element if we are updating it with a better priority
            if (existingElement != null) {
                var removalIndex = updatedElements.binarySearch(existingElement, comparator)
                require(removalIndex >= 0) { "State corruption: Existing element not found in the internal list." }

                // Handle priority ties: binarySearch might find a different element with the exact same priority
                if (uniqueKeySelector(updatedElements[removalIndex]) != elementKey) {
                    var isExactElementFound = false

                    // Scan left for the exact element
                    for (i in removalIndex - 1 downTo 0) {
                        if (comparator.compare(updatedElements[i], existingElement) != 0) break
                        if (uniqueKeySelector(updatedElements[i]) == elementKey) {
                            removalIndex = i
                            isExactElementFound = true
                            break
                        }
                    }

                    // Scan right for the exact element
                    if (!isExactElementFound) {
                        for (i in removalIndex + 1 until updatedElements.size) {
                            if (comparator.compare(updatedElements[i], existingElement) != 0) break
                            if (uniqueKeySelector(updatedElements[i]) == elementKey) {
                                removalIndex = i
                                isExactElementFound = true
                                break
                            }
                        }
                    }
                    require(isExactElementFound) { "State corruption: Exact element not found despite priority match." }
                }

                updatedElements = updatedElements.removeAt(removalIndex)
            }

            // Determine the exact insertion point for the new element
            val insertionIndex = updatedElements.binarySearch(element, comparator).let { index ->
                if (index < 0) -(index + 1) else index
            }

            // Insert the new element and update the lookup map
            updatedElements = updatedElements.add(insertionIndex, element)
            updatedElementsByKey = updatedElementsByKey.put(elementKey, element)

            // Evict the lowest priority element if the capacity is exceeded
            if (updatedElements.size > maxSize) {
                val evictedElement = updatedElements.last()
                updatedElements = updatedElements.removeAt(updatedElements.lastIndex)
                updatedElementsByKey = updatedElementsByKey.remove(uniqueKeySelector(evictedElement))
            }

            Snapshot(updatedElements, updatedElementsByKey)
        }
    }

    companion object {
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