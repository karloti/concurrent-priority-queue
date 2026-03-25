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
 * A mutable builder for [ConcurrentPriorityQueue].
 *
 * Obtain an instance via [ConcurrentPriorityQueue.builder]. The builder
 * accumulates mutations without CAS overhead and produces a new queue
 * on [build].
 *
 * The builder respects the same bounded capacity and upsert semantics
 * as [ConcurrentPriorityQueue]. It extends [PriorityBuilder] so that
 * common priority-aware operations and standard [MutableCollection]
 * operations (`add`, `remove`, `clear`, etc.) are available.
 *
 * @param T the type of elements
 * @param K the type of keys
 * @see ConcurrentPriorityQueue.builder
 * @see ConcurrentPriorityQueue.mutate
 */
interface ConcurrentPriorityQueueBuilder<T, K> : PriorityBuilder<T, K> {

    /**
     * Builds and returns a new [ConcurrentPriorityQueue] containing
     * all elements currently in this builder.
     *
     * The builder can still be used after calling this method.
     *
     * @return a new [ConcurrentPriorityQueue]
     */
    fun build(): ConcurrentPriorityQueue<T, K>

    /**
     * Removes and returns the highest-priority element, or `null` if
     * the builder is empty.
     *
     * @return the removed element, or `null`
     */
    fun poll(): T?

    /**
     * Removes all elements matching the given [predicate].
     *
     * @param predicate the predicate used to determine which elements to remove
     * @return the number of elements removed
     */
    fun removeIf(predicate: (T) -> Boolean): Int

    /**
     * Retains only elements matching the given [predicate], removing all others.
     *
     * @param predicate the predicate used to determine which elements to keep
     * @return the number of elements removed
     */
    fun retainIf(predicate: (T) -> Boolean): Int

    /**
     * Returns the current elements as a sorted list snapshot.
     *
     * @return a [List] of elements in priority order
     */
    fun toList(): List<T>
}

/**
 * Internal [ConcurrentPriorityQueueBuilder] implementation backed by a [TreapPriorityList].
 *
 * Each mutation eagerly produces a new persistent snapshot internally.
 * Calling [build] constructs a new [ConcurrentPriorityQueue] from the latest snapshot.
 */
internal class ConcurrentPriorityQueueBuilderImpl<T, K>(
    private val maxSize: Int,
    private val comparator: Comparator<T>,
    private val keySelector: (T) -> K,
    initial: TreapPriorityList<T, K>
) : ConcurrentPriorityQueueBuilder<T, K>, AbstractMutableCollection<T>() {

    private var current: TreapPriorityList<T, K> = initial

    override val size: Int get() = current.size

    override fun build(): ConcurrentPriorityQueue<T, K> {
        val queue = ConcurrentPriorityQueue(
            maxSize = maxSize,
            comparator = comparator,
            uniqueKeySelector = keySelector
        )
        queue.queueState.value = current
        return queue
    }

    override fun add(element: T): Boolean {
        if (maxSize <= 0) return false

        val key = keySelector(element)
        val existing = current.getByKey(key)

        // Reject if existing element has equal or better priority
        if (existing != null && comparator.compare(element, existing) >= 0) {
            return false
        }

        // Reject if full and worse than the lowest-priority element
        if (current.size >= maxSize && existing == null) {
            val lowest = current.last()
            if (lowest != null && comparator.compare(element, lowest) >= 0) {
                return false
            }
        }

        current = current.add(element)

        // Evict if over capacity
        if (current.size > maxSize) {
            current = current.removeLast()
        }

        return true
    }

    override fun remove(element: T): Boolean {
        val old = current
        current = current.remove(element)
        return current !== old
    }

    override fun removeByKey(key: K): Boolean {
        val old = current
        current = current.removeByKey(key)
        return current !== old
    }

    override fun clear() {
        current = current.clear()
    }

    override fun iterator(): MutableIterator<T> {
        val elements = current.toList().toMutableList()
        val delegate = elements.iterator()
        var lastReturned: T? = null

        return object : MutableIterator<T> {
            override fun hasNext(): Boolean = delegate.hasNext()

            override fun next(): T {
                val e = delegate.next()
                lastReturned = e
                return e
            }

            override fun remove() {
                val e = lastReturned ?: throw IllegalStateException("next() not called")
                current = current.remove(e)
                lastReturned = null
            }
        }
    }

    override fun contains(element: T): Boolean = current.contains(element)

    override fun getByKey(key: K): T? = current.getByKey(key)

    override fun containsKey(key: K): Boolean = current.containsKey(key)

    override fun first(): T? = current.first()

    override fun last(): T? = current.last()

    override fun poll(): T? {
        val element = current.first() ?: return null
        current = current.removeByKey(keySelector(element))
        return element
    }

    override fun removeIf(predicate: (T) -> Boolean): Int {
        val toRemove = current.filter(predicate)
        if (toRemove.isEmpty()) return 0
        current = current.removeAll(toRemove)
        return toRemove.size
    }

    override fun retainIf(predicate: (T) -> Boolean): Int {
        return removeIf { !predicate(it) }
    }

    override fun toList(): List<T> = current.toList()
}
