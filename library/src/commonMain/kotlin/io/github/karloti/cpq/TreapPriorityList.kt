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

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlin.random.Random

/**
 * Default implementation of [PersistentPriorityList] backed by a
 * [Treap](https://en.wikipedia.org/wiki/Treap) with subtree sizes for
 * O(log n) index computation and a [PersistentMap] for O(1) key lookup.
 *
 * This class is internal — obtain instances via [persistentPriorityListOf]
 * or [buildPersistentPriorityList].
 */
internal class TreapPriorityList<T, K> private constructor(
    internal val root: Node<T, K>?,
    internal val elementsByKey: PersistentMap<K, T>,
    private val comparator: Comparator<T>,
    private val keySelector: (T) -> K
) : PersistentPriorityList<T, K>, AbstractList<T>() {

    /**
     * Treap node with subtree size for O(log n) index calculations.
     * Stores key for efficient exact-match during removal.
     */
    internal class Node<T, K>(
        val element: T,
        val key: K,
        val priority: Long,
        val left: Node<T, K>? = null,
        val right: Node<T, K>? = null
    ) {
        val size: Int = 1 + (left?.size ?: 0) + (right?.size ?: 0)
        val leftSize: Int get() = left?.size ?: 0

        fun copy(
            left: Node<T, K>? = this.left,
            right: Node<T, K>? = this.right
        ): Node<T, K> = Node(element, key, priority, left, right)
    }

    // ========================= List implementation =========================

    override val size: Int get() = root?.size ?: 0

    override fun get(index: Int): T =
        getAt(index) ?: throw IndexOutOfBoundsException("Index: $index, Size: $size")

    override fun indexOf(element: T): Int {
        val key = keySelector(element)
        return indexOfKey(key)
    }

    override fun lastIndexOf(element: T): Int = indexOf(element)

    override fun contains(element: T): Boolean = containsKey(keySelector(element))

    override fun iterator(): Iterator<T> = TreapIterator(root)

    override fun listIterator(): ListIterator<T> = toList().listIterator()

    override fun listIterator(index: Int): ListIterator<T> = toList().listIterator(index)

    // ================ ImmutablePriorityList implementation =================

    override fun getByKey(key: K): T? = elementsByKey[key]

    override fun containsKey(key: K): Boolean = key in elementsByKey

    override fun indexOfKey(key: K): Int {
        val element = elementsByKey[key] ?: return -1
        return indexOfElement(root, element, key, 0)
    }

    override fun getAt(index: Int): T? {
        if (index !in indices) return null
        return getAtNode(root, index)
    }

    override fun first(): T? = root?.let { getFirst(it) }

    override fun last(): T? = root?.let { getLast(it) }

    // ============== PersistentPriorityList implementation ===================

    override fun add(element: T): TreapPriorityList<T, K> {
        val key = keySelector(element)
        val existing = elementsByKey[key]

        if (existing != null && comparator.compare(element, existing) >= 0) {
            return this
        }

        val afterRemoval = if (existing != null) removeByKey(key) else this

        val treapPriority = (element.hashCode().toLong() shl 32) or (Random.nextLong() and 0xFFFFFFFFL)
        val newRoot = insertNode(afterRemoval.root, element, key, treapPriority)
        val newMap = afterRemoval.elementsByKey.put(key, element)

        return TreapPriorityList(newRoot, newMap, comparator, keySelector)
    }

    override fun addAll(elements: Collection<T>): TreapPriorityList<T, K> {
        var result: TreapPriorityList<T, K> = this
        for (element in elements) {
            result = result.add(element)
        }
        return result
    }

    override fun remove(element: T): TreapPriorityList<T, K> =
        removeByKey(keySelector(element))

    override fun removeByKey(key: K): TreapPriorityList<T, K> {
        val element = elementsByKey[key] ?: return this
        val newRoot = removeNode(root, element, key)
        val newMap = elementsByKey.remove(key)
        return TreapPriorityList(newRoot, newMap, comparator, keySelector)
    }

    override fun removeAll(elements: Collection<T>): TreapPriorityList<T, K> {
        var result: TreapPriorityList<T, K> = this
        for (element in elements) {
            result = result.removeByKey(keySelector(element))
        }
        return result
    }

    override fun removeAll(predicate: (T) -> Boolean): TreapPriorityList<T, K> {
        val toRemove = elementsByKey.values.filter(predicate)
        return removeAll(toRemove)
    }

    override fun retainAll(elements: Collection<T>): TreapPriorityList<T, K> {
        val keysToRetain = elements.map { keySelector(it) }.toSet()
        val toRemove = elementsByKey.keys.filter { it !in keysToRetain }
        var result: TreapPriorityList<T, K> = this
        for (key in toRemove) {
            result = result.removeByKey(key)
        }
        return result
    }

    override fun clear(): TreapPriorityList<T, K> = empty(comparator, keySelector)

    override fun set(index: Int, element: T): TreapPriorityList<T, K> {
        val oldElement = getAt(index) ?: throw IndexOutOfBoundsException("Index: $index, Size: $size")
        return removeByKey(keySelector(oldElement)).add(element)
    }

    override fun removeAt(index: Int): TreapPriorityList<T, K> {
        val element = getAt(index) ?: throw IndexOutOfBoundsException("Index: $index, Size: $size")
        return removeByKey(keySelector(element))
    }

    override fun removeLast(): TreapPriorityList<T, K> {
        val lastElement = last() ?: return this
        return removeByKey(keySelector(lastElement))
    }

    override fun removeLastN(count: Int): TreapPriorityList<T, K> {
        if (count <= 0) return this
        if (count >= size) return empty(comparator, keySelector)

        var result: TreapPriorityList<T, K> = this
        repeat(count) {
            result = result.removeLast()
        }
        return result
    }

    // ============================== Builder ================================

    override fun builder(): PersistentPriorityList.Builder<T, K> =
        BuilderImpl(this)

    /**
     * Mutable builder backed by a [TreapPriorityList].
     *
     * Each mutation eagerly produces a new persistent snapshot internally.
     * Calling [build] simply returns the latest snapshot.
     */
    private class BuilderImpl<T, K>(
        private var current: TreapPriorityList<T, K>
    ) : PersistentPriorityList.Builder<T, K>, AbstractMutableCollection<T>() {

        override val size: Int get() = current.size

        override fun build(): PersistentPriorityList<T, K> = current

        override fun add(element: T): Boolean {
            val old = current
            current = current.add(element)
            return current !== old
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
                    @Suppress("UNCHECKED_CAST")
                    val e = lastReturned ?: throw IllegalStateException("next() not called")
                    current = current.remove(e)
                    lastReturned = null
                }
            }
        }

        override fun contains(element: T): Boolean = current.contains(element)

        override fun getByKey(key: K): T? = current.getByKey(key)

        override fun containsKey(key: K): Boolean = current.containsKey(key)

        override fun indexOfKey(key: K): Int = current.indexOfKey(key)

        override fun first(): T? = current.first()

        override fun last(): T? = current.last()

        override fun removeLast(): Boolean {
            val old = current
            current = current.removeLast()
            return current !== old
        }

        override fun removeAll(predicate: (T) -> Boolean): Boolean {
            val old = current
            current = current.removeAll(predicate)
            return current !== old
        }
    }

    // ========================= Optimized toList ============================

    /**
     * Returns the elements in sorted order as a [List].
     *
     * Uses an O(n) inorder traversal instead of the default O(n log n)
     * index-based iteration from [AbstractList].
     */
    fun toList(): List<T> {
        val result = ArrayList<T>(size)
        inorderTraversal(root, result)
        return result
    }

    private fun inorderTraversal(node: Node<T, K>?, result: MutableList<T>) {
        if (node == null) return
        inorderTraversal(node.left, result)
        result.add(node.element)
        inorderTraversal(node.right, result)
    }

    // ========================= Internal helpers ============================

    private fun getAtNode(node: Node<T, K>?, index: Int): T? {
        if (node == null) return null
        val leftSize = node.leftSize
        return when {
            index < leftSize -> getAtNode(node.left, index)
            index == leftSize -> node.element
            else -> getAtNode(node.right, index - leftSize - 1)
        }
    }

    private fun indexOfElement(node: Node<T, K>?, element: T, key: K, baseIndex: Int): Int {
        if (node == null) return -1

        val cmp = comparator.compare(element, node.element)
        return when {
            cmp < 0 -> indexOfElement(node.left, element, key, baseIndex)
            cmp > 0 -> indexOfElement(node.right, element, key, baseIndex + node.leftSize + 1)
            else -> {
                if (node.key == key) {
                    baseIndex + node.leftSize
                } else {
                    val leftResult = indexOfElement(node.left, element, key, baseIndex)
                    if (leftResult != -1) leftResult
                    else indexOfElement(node.right, element, key, baseIndex + node.leftSize + 1)
                }
            }
        }
    }

    private fun getFirst(node: Node<T, K>): T = node.left?.let { getFirst(it) } ?: node.element

    private fun getLast(node: Node<T, K>): T = node.right?.let { getLast(it) } ?: node.element

    private fun insertNode(node: Node<T, K>?, element: T, key: K, priority: Long): Node<T, K> {
        if (node == null) {
            return Node(element, key, priority)
        }

        val cmp = comparator.compare(element, node.element)

        return if (cmp <= 0) {
            val newLeft = insertNode(node.left, element, key, priority)
            val newNode = node.copy(left = newLeft)
            if (newLeft.priority > node.priority) rotateRight(newNode) else newNode
        } else {
            val newRight = insertNode(node.right, element, key, priority)
            val newNode = node.copy(right = newRight)
            if (newRight.priority > node.priority) rotateLeft(newNode) else newNode
        }
    }

    private fun rotateRight(node: Node<T, K>): Node<T, K> {
        val left = node.left ?: return node
        return Node(
            element = left.element,
            key = left.key,
            priority = left.priority,
            left = left.left,
            right = node.copy(left = left.right)
        )
    }

    private fun rotateLeft(node: Node<T, K>): Node<T, K> {
        val right = node.right ?: return node
        return Node(
            element = right.element,
            key = right.key,
            priority = right.priority,
            left = node.copy(right = right.left),
            right = right.right
        )
    }

    private fun removeNode(node: Node<T, K>?, element: T, key: K): Node<T, K>? {
        if (node == null) return null

        val cmp = comparator.compare(element, node.element)

        return when {
            cmp < 0 -> {
                val newLeft = removeNode(node.left, element, key)
                if (newLeft === node.left) node else node.copy(left = newLeft)
            }

            cmp > 0 -> {
                val newRight = removeNode(node.right, element, key)
                if (newRight === node.right) node else node.copy(right = newRight)
            }

            else -> {
                if (node.key == key) {
                    mergeChildren(node.left, node.right)
                } else {
                    val newLeft = removeNode(node.left, element, key)
                    if (newLeft !== node.left) {
                        return node.copy(left = newLeft)
                    }
                    val newRight = removeNode(node.right, element, key)
                    if (newRight !== node.right) {
                        return node.copy(right = newRight)
                    }
                    node
                }
            }
        }
    }

    private fun mergeChildren(left: Node<T, K>?, right: Node<T, K>?): Node<T, K>? {
        if (left == null) return right
        if (right == null) return left

        return if (left.priority > right.priority) {
            Node(left.element, left.key, left.priority, left.left, mergeChildren(left.right, right))
        } else {
            Node(right.element, right.key, right.priority, mergeChildren(left, right.left), right.right)
        }
    }

    /**
     * Efficient iterator that traverses the treap in-order without
     * creating an intermediate list.
     */
    private class TreapIterator<T, K>(root: Node<T, K>?) : Iterator<T> {
        private val stack = ArrayDeque<Node<T, K>>()

        init {
            pushLeftPath(root)
        }

        private fun pushLeftPath(node: Node<T, K>?) {
            var current = node
            while (current != null) {
                stack.addLast(current)
                current = current.left
            }
        }

        override fun hasNext(): Boolean = stack.isNotEmpty()

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            val node = stack.removeLast()
            pushLeftPath(node.right)
            return node.element
        }
    }

    companion object {
        fun <T, K> empty(
            comparator: Comparator<T>,
            keySelector: (T) -> K
        ): TreapPriorityList<T, K> {
            return TreapPriorityList(
                root = null,
                elementsByKey = persistentHashMapOf(),
                comparator = comparator,
                keySelector = keySelector
            )
        }
    }
}
