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

package io.github.karloti.cpq

/**
 * Common base interface for mutable priority collection builders.
 *
 * This interface captures the shared contract between
 * [PersistentPriorityList.Builder] and [ConcurrentPriorityQueueBuilder],
 * extending [MutableCollection] with key-based lookup and priority-aware
 * access operations.
 *
 * Implementations maintain elements in sorted order defined by a comparator
 * and provide O(1) key-based lookups via an internal hash map.
 *
 * @param T the type of elements
 * @param K the type of keys used to identify elements
 * @see PersistentPriorityList.Builder
 * @see ConcurrentPriorityQueueBuilder
 */
interface PriorityBuilder<T, K> : MutableCollection<T> {

    /**
     * Returns the element associated with the given [key], or `null` if
     * no such element exists.
     *
     * @param key the key to look up
     * @return the element with the given key, or `null`
     */
    fun getByKey(key: K): T?

    /**
     * Returns `true` if this builder contains an element with the given [key].
     *
     * @param key the key to check
     * @return `true` if an element with the given key exists
     */
    fun containsKey(key: K): Boolean

    /**
     * Removes the element identified by the given [key].
     *
     * @param key the key of the element to remove
     * @return `true` if an element was removed
     */
    fun removeByKey(key: K): Boolean

    /**
     * Returns the first (highest-priority) element, or `null` if the
     * builder is empty.
     *
     * @return the first element, or `null`
     */
    fun first(): T?

    /**
     * Returns the last (lowest-priority) element, or `null` if the
     * builder is empty.
     *
     * @return the last element, or `null`
     */
    fun last(): T?
}
