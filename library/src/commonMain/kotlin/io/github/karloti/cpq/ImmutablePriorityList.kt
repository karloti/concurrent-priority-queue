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
 * An immutable, indexed priority list that maintains elements in sorted order
 * defined by a [Comparator], with O(1) key-based lookup.
 *
 * This interface extends [List] and provides additional priority-aware
 * and key-based access operations. Elements are ordered by priority (as
 * determined by the comparator), and each element is uniquely identified
 * by a key extracted via a key selector function.
 *
 * Implementations guarantee that the collection cannot be mutated through
 * its public API. All structural modifications produce new instances.
 *
 * @param T the type of elements in this list
 * @param K the type of keys used to identify elements
 * @see PersistentPriorityList
 */
interface ImmutablePriorityList<T, K> : List<T> {

    /**
     * Returns the element associated with the given [key], or `null` if
     * no such element exists.
     *
     * This operation is O(1).
     *
     * @param key the key to look up
     * @return the element with the given key, or `null`
     */
    fun getByKey(key: K): T?

    /**
     * Returns `true` if this list contains an element with the given [key].
     *
     * This operation is O(1).
     *
     * @param key the key to check
     * @return `true` if an element with the given key exists
     */
    fun containsKey(key: K): Boolean

    /**
     * Returns the index of the element with the given [key] in the sorted order,
     * or `-1` if no such element exists.
     *
     * This operation is O(log n).
     *
     * @param key the key to look up
     * @return the index of the element, or `-1`
     */
    fun indexOfKey(key: K): Int

    /**
     * Returns the element at the given [index] in the sorted order,
     * or `null` if the index is out of bounds.
     *
     * This operation is O(log n).
     *
     * @param index the zero-based index
     * @return the element at the given index, or `null`
     */
    fun getAt(index: Int): T?

    /**
     * Returns the first (highest-priority) element, or `null` if the list is empty.
     *
     * This operation is O(log n).
     *
     * @return the first element, or `null`
     */
    fun first(): T?

    /**
     * Returns the last (lowest-priority) element, or `null` if the list is empty.
     *
     * This operation is O(log n).
     *
     * @return the last element, or `null`
     */
    fun last(): T?
}
