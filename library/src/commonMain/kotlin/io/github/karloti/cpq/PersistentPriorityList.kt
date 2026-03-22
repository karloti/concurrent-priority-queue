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
 * A persistent, indexed priority list that maintains elements in sorted order
 * defined by a [Comparator], with O(1) key-based lookup and O(log n)
 * structural modifications via structural sharing.
 *
 * This interface extends [ImmutablePriorityList] and adds copy-on-write
 * mutation operations. Every modification (add, remove, set, clear) returns a
 * new [PersistentPriorityList] instance, leaving the original unchanged.
 * Internally, the old and new instances share most of their structure (a
 * [Treap](https://en.wikipedia.org/wiki/Treap)), making copies efficient.
 *
 * ### Ordering and deduplication
 *
 * Elements are ordered by a comparator supplied at construction time.
 * Each element is uniquely identified by a key (extracted by a key selector).
 * Inserting an element whose key already exists performs an **upsert** — the
 * existing element is replaced only if the new one has strictly higher priority
 * (i.e., the comparator considers it smaller).
 *
 * ### Builder
 *
 * For batch modifications use [builder] to obtain a [Builder], apply changes
 * mutably, and call [Builder.build] to produce a new persistent instance.
 * Alternatively, use the [mutate] convenience method:
 *
 * ```kotlin
 * val updated = list.mutate { add(newElement) }
 * ```
 *
 * @param T the type of elements in this list
 * @param K the type of keys used to identify elements
 * @see ImmutablePriorityList
 * @see Builder
 */
interface PersistentPriorityList<T, K> : ImmutablePriorityList<T, K> {

    // ========================= Persistent mutation =========================

    /**
     * Returns a new list with the given [element] inserted at its correct
     * position according to the comparator.
     *
     * If an element with the same key already exists and the new element
     * has strictly higher priority, the old element is replaced. Otherwise
     * the list is returned unchanged.
     *
     * This operation is O(log n).
     *
     * @param element the element to insert
     * @return a new [PersistentPriorityList] containing the element
     */
    fun add(element: T): PersistentPriorityList<T, K>

    /**
     * Returns a new list with all elements from the given [elements] collection
     * inserted at their correct positions according to the comparator.
     *
     * This operation is O(k log n) where k is the number of elements to add.
     *
     * @param elements the elements to insert
     * @return a new [PersistentPriorityList] containing all elements
     */
    fun addAll(elements: Collection<T>): PersistentPriorityList<T, K>

    /**
     * Returns a new list with the given [element] removed.
     *
     * If the element is not present, the list is returned unchanged.
     *
     * This operation is O(log n).
     *
     * @param element the element to remove
     * @return a new [PersistentPriorityList] without the element
     */
    fun remove(element: T): PersistentPriorityList<T, K>

    /**
     * Returns a new list with the element identified by the given [key] removed.
     *
     * If no element with the key exists, the list is returned unchanged.
     *
     * This operation is O(log n).
     *
     * @param key the key of the element to remove
     * @return a new [PersistentPriorityList] without the element
     */
    fun removeByKey(key: K): PersistentPriorityList<T, K>

    /**
     * Returns a new list with all elements from the given [elements] collection removed.
     *
     * This operation is O(k log n) where k is the number of elements to remove.
     *
     * @param elements the elements to remove
     * @return a new [PersistentPriorityList] without the specified elements
     */
    fun removeAll(elements: Collection<T>): PersistentPriorityList<T, K>

    /**
     * Returns a new list with all elements matching the given [predicate] removed.
     *
     * @param predicate the predicate used to determine which elements to remove
     * @return a new [PersistentPriorityList] without the matching elements
     */
    fun removeAll(predicate: (T) -> Boolean): PersistentPriorityList<T, K>

    /**
     * Returns a new list containing only elements that are also present in
     * the given [elements] collection (by key).
     *
     * @param elements the elements to retain
     * @return a new [PersistentPriorityList] containing only the retained elements
     */
    fun retainAll(elements: Collection<T>): PersistentPriorityList<T, K>

    /**
     * Returns an empty list with the same comparator and key selector.
     *
     * @return an empty [PersistentPriorityList]
     */
    fun clear(): PersistentPriorityList<T, K>

    /**
     * Returns a new list with the element at the given [index] replaced by
     * [element]. The old element is removed and the new element is inserted
     * at its correct priority position (which may differ from [index]).
     *
     * @param index the index of the element to replace
     * @param element the new element
     * @return a new [PersistentPriorityList] with the replacement applied
     * @throws IndexOutOfBoundsException if [index] is out of bounds
     */
    fun set(index: Int, element: T): PersistentPriorityList<T, K>

    /**
     * Returns a new list with the element at the given [index] removed.
     *
     * @param index the index of the element to remove
     * @return a new [PersistentPriorityList] without the element
     * @throws IndexOutOfBoundsException if [index] is out of bounds
     */
    fun removeAt(index: Int): PersistentPriorityList<T, K>

    /**
     * Returns a new list with the last (lowest-priority) element removed.
     *
     * If the list is empty, it is returned unchanged.
     *
     * @return a new [PersistentPriorityList] without the last element
     */
    fun removeLast(): PersistentPriorityList<T, K>

    /**
     * Returns a new list with the last [count] lowest-priority elements removed.
     *
     * If [count] is greater than or equal to the size, an empty list is returned.
     *
     * This operation is O(k log n).
     *
     * @param count the number of elements to remove from the end
     * @return a new [PersistentPriorityList] with the elements removed
     */
    fun removeLastN(count: Int): PersistentPriorityList<T, K>

    // ============================== Builder ================================

    /**
     * Returns a [Builder] initialized with the contents of this list.
     *
     * The builder allows efficient batch modifications. Once all changes
     * have been applied, call [Builder.build] to produce a new persistent list.
     *
     * @return a new [Builder] pre-populated with this list's elements
     */
    fun builder(): Builder<T, K>

    /**
     * Applies [mutator] to a [Builder] pre-populated with the contents of
     * this list and returns the resulting persistent list.
     *
     * This is a convenience wrapper around [builder] and [Builder.build]:
     *
     * ```kotlin
     * val updated = list.mutate {
     *     add(element1)
     *     add(element2)
     *     removeByKey(oldKey)
     * }
     * ```
     *
     * @param mutator the block of mutations to apply
     * @return a new [PersistentPriorityList] reflecting all mutations
     */
    fun mutate(mutator: Builder<T, K>.() -> Unit): PersistentPriorityList<T, K> {
        return builder().apply(mutator).build()
    }

    /**
     * A mutable builder for [PersistentPriorityList].
     *
     * Obtain an instance via [PersistentPriorityList.builder] or the
     * top-level [buildPersistentPriorityList] function. The builder
     * accumulates mutations and produces a new persistent list on [build].
     *
     * The builder extends [MutableCollection] so that standard collection
     * operations (`add`, `remove`, `clear`, etc.) are available. Index-based
     * insertion methods (`add(index, element)`) are **not** supported because
     * element position is determined by the comparator.
     *
     * @param T the type of elements
     * @param K the type of keys
     */
    interface Builder<T, K> : PriorityBuilder<T, K> {

        /**
         * Builds and returns a new [PersistentPriorityList] containing
         * all elements currently in this builder.
         *
         * The builder can still be used after calling this method.
         *
         * @return a new [PersistentPriorityList]
         */
        fun build(): PersistentPriorityList<T, K>

        /**
         * Returns the index of the element with the given [key] in the sorted
         * order, or `-1` if no such element exists.
         *
         * @param key the key to look up
         * @return the index of the element, or `-1`
         */
        fun indexOfKey(key: K): Int

        /**
         * Removes the last (lowest-priority) element.
         *
         * @return `true` if an element was removed
         */
        fun removeLast(): Boolean

        /**
         * Removes all elements matching the given [predicate].
         *
         * @param predicate the predicate used to determine which elements to remove
         * @return `true` if any elements were removed
         */
        fun removeAll(predicate: (T) -> Boolean): Boolean
    }
}

/**
 * Returns an empty [PersistentPriorityList] with the given [comparator]
 * and [keySelector].
 *
 * @param T the type of elements
 * @param K the type of keys
 * @param comparator the comparator defining the priority order
 * @param keySelector the function extracting a unique key from each element
 * @return an empty [PersistentPriorityList]
 */
fun <T, K> persistentPriorityListOf(
    comparator: Comparator<T>,
    keySelector: (T) -> K
): PersistentPriorityList<T, K> {
    return TreapPriorityList.empty(comparator, keySelector)
}

/**
 * Builds a [PersistentPriorityList] by applying [builderAction] to
 * a new [PersistentPriorityList.Builder].
 *
 * ```kotlin
 * val list = buildPersistentPriorityList(compareBy { it.score }, { it.id }) {
 *     add(Item(id = "a", score = 10))
 *     add(Item(id = "b", score = 20))
 * }
 * ```
 *
 * @param T the type of elements
 * @param K the type of keys
 * @param comparator the comparator defining the priority order
 * @param keySelector the function extracting a unique key from each element
 * @param builderAction the block of mutations to apply
 * @return a new [PersistentPriorityList]
 */
fun <T, K> buildPersistentPriorityList(
    comparator: Comparator<T>,
    keySelector: (T) -> K,
    builderAction: PersistentPriorityList.Builder<T, K>.() -> Unit
): PersistentPriorityList<T, K> {
    return persistentPriorityListOf(comparator, keySelector)
        .builder()
        .apply(builderAction)
        .build()
}
