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

@file:OptIn(FlowPreview::class)

package io.github.karloti.cpq

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A bounded, mutable priority queue with key-based deduplication.
 *
 * Elements are maintained in sorted order defined by a [Comparator]. Each element
 * is uniquely identified by a key; inserting an element whose key already exists
 * performs an **upsert** — the existing element is replaced only if the new one
 * has strictly higher priority.
 *
 * When the queue reaches its maximum capacity, lower-priority elements are
 * automatically evicted to make room for higher-priority insertions.
 *
 * ### Reactive State
 *
 * The current contents are observable via [items], which exposes a [StateFlow]
 * of [List] suitable for UI binding (e.g., Jetpack Compose `collectAsState()`).
 *
 * ### Example
 *
 * ```kotlin
 * val queue: BoundedPriorityQueue<Task, String> = ConcurrentPriorityQueue(maxSize = 10, ...)
 * queue.add(Task(id = "1", priority = 5))
 *
 * // Observe changes in Compose
 * val tasks by queue.items.collectAsState()
 * ```
 *
 * @param T the type of elements held in the queue
 * @param K the type of the unique identity key for deduplication
 * @see ConcurrentPriorityQueue
 */
interface BoundedPriorityQueue<T, K> {

    /**
     * A reactive [StateFlow] containing the current queue elements as an
     * immutable sorted list.
     *
     * The list is sorted according to the comparator — the first element has
     * the highest priority. Each emission is a snapshot safe to iterate without
     * synchronization.
     */
    val items: StateFlow<List<T>>

    /**
     * The current number of elements in the queue.
     */
    val size: Int

    /**
     * Attempts to add an element to the queue with upsert semantics.
     *
     * - If the key doesn't exist and the queue has space: the element is added,
     *   `null` is returned.
     * - If the key doesn't exist but the queue is full: the element replaces the
     *   lowest-priority element if it has strictly better priority; the evicted
     *   element is returned. Otherwise, the element is rejected and `null` is
     *   returned.
     * - If the key exists with worse priority: the element replaces the existing
     *   one, `null` is returned.
     * - If the key exists with equal or better priority: the element is rejected,
     *   `null` is returned.
     *
     * @param element the element to add
     * @return the evicted element if a displacement occurred; `null` otherwise
     */
    fun add(element: T): T?

    /**
     * Checks if the queue contains an element with the specified key.
     *
     * @param key the unique key to search for
     * @return `true` if an element with this key exists
     */
    fun containsKey(key: K): Boolean

    /**
     * Checks if the queue contains the specified element (by key).
     *
     * @param element the element to search for
     * @return `true` if the element exists in the queue
     */
    fun contains(element: T): Boolean

    /**
     * Retrieves an element by its unique key.
     *
     * @param key the unique key of the element to retrieve
     * @return the element if found, or `null`
     */
    operator fun get(key: K): T?

    /**
     * Returns the highest-priority element without removing it.
     *
     * @return the highest-priority element, or `null` if the queue is empty
     */
    fun first(): T?

    /**
     * Returns the lowest-priority element without removing it.
     *
     * @return the lowest-priority element, or `null` if the queue is empty
     */
    fun last(): T?

    /**
     * Removes and returns the highest-priority element.
     *
     * @return the removed element, or `null` if the queue is empty
     */
    fun poll(): T?

    /**
     * Removes an element by its unique key.
     *
     * @param key the unique key of the element to remove
     * @return `true` if an element was removed
     */
    fun removeByKey(key: K): Boolean

    /**
     * Removes a specific element from the queue (identified by its key).
     *
     * @param element the element to remove
     * @return `true` if the element was removed
     */
    fun remove(element: T): Boolean

    /**
     * Removes all elements matching the given [predicate].
     *
     * @param predicate returns `true` for elements to remove
     * @return the number of elements removed
     */
    fun removeIf(predicate: (T) -> Boolean): Int

    /**
     * Retains only elements matching the given [predicate], removing all others.
     *
     * @param predicate returns `true` for elements to keep
     * @return the number of elements removed
     */
    fun retainIf(predicate: (T) -> Boolean): Int {
        return removeIf { !predicate(it) }
    }

    /**
     * Removes all elements from the queue.
     */
    fun clear()

    /**
     * Returns `true` if the queue contains no elements.
     */
    fun isEmpty(): Boolean = size == 0

    /**
     * Returns `true` if the queue contains at least one element.
     */
    fun isNotEmpty(): Boolean = !isEmpty()

    /**
     * Adds all elements from the given iterable to the queue.
     *
     * @param elements the elements to add
     * @return a snapshot of the queue contents after all elements have been processed
     */
    fun addAll(elements: Iterable<T>): List<T>

    /**
     * Transforms and adds all elements from the given iterable to the queue.
     *
     * @param S the type of elements in the source collection
     * @param elements the source elements to transform and add
     * @param transform a function that converts source elements to queue elements
     * @return the number of elements whose insertion caused an eviction
     */
    fun <S> addAll(elements: Iterable<S>, transform: (S) -> T): Int

    /**
     * Adds all elements from the given sequence to the queue.
     *
     * @param elements the sequence of elements to add
     * @return the number of elements whose insertion caused an eviction
     */
    fun addAll(elements: Sequence<T>): Int

    /**
     * Transforms and adds all elements from the given sequence to the queue.
     *
     * @param S the type of elements in the source sequence
     * @param elements the source elements to transform and add
     * @param transform a function that converts source elements to queue elements
     * @return the number of elements whose insertion caused an eviction
     */
    fun <S> addAll(elements: Sequence<S>, transform: (S) -> T): Int

    /**
     * Adds all elements from the given flow to the queue.
     *
     * Suspends until the flow is fully collected.
     *
     * @param elements the flow of elements to add
     * @return the number of elements whose insertion caused an eviction
     */
    suspend fun addAll(elements: Flow<T>): Int

    /**
     * Transforms and adds all elements from the given flow to the queue.
     *
     * Suspends until the flow is fully collected.
     *
     * @param S the type of elements in the source flow
     * @param elements the source elements to transform and add
     * @param transform a suspending function that converts source elements to queue elements
     * @return the number of elements whose insertion caused an eviction
     */
    suspend fun <S> addAll(elements: Flow<S>, transform: suspend (S) -> T): Int

    /**
     * Returns an iterator over the elements in priority order (highest first).
     *
     * The iterator operates on a snapshot; modifications after creation are
     * not reflected.
     */
    operator fun iterator(): Iterator<T>
}
