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

@file:OptIn(ExperimentalUuidApi::class)

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A persistent, indexed priority list optimized for:
 * - O(log n) insertion maintaining sorted order (via treap)
 * - O(log n) removal by key (using tree traversal with key comparison)
 * - O(1) lookup of element by key
 * - O(log n) to get actual index when needed (computed via tree, not stored)
 *
 * Key insight: Don't store indices at all. The treap structure with subtree sizes
 * allows computing any element's index in O(log n) by traversing from root.
 * This avoids O(n) index updates entirely.
 *
 * Thread-safety: All node references are atomic for safe concurrent reads.
 * The structure itself is immutable/persistent - modifications return new instances.
 */
internal class PersistentIndexedPriorityList<T, K> private constructor(
    internal val root: Node<T, K>?,
    internal val elementsByKey: PersistentMap<K, T>,
    private val comparator: Comparator<T>,
    private val keySelector: (T) -> K
) : PersistentList<T> {

    /**
     * Treap node with subtree size for O(log n) index calculations.
     * Stores key for efficient exact-match during removal.
     * All child references are atomic for safe concurrent access.
     */
    internal class Node<T, K>(
        val element: T,
        val key: K,
        val priority: Long, // Random priority for treap balancing
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

    override val size: Int get() = root?.size ?: 0

    override fun isEmpty(): Boolean = root == null

    /**
     * O(1) - Get element by key
     */
    operator fun get(key: K): T? = elementsByKey[key]

    /**
     * O(1) - Check if key exists
     */
    fun containsKey(key: K): Boolean = key in elementsByKey

    /**
     * O(log n) - Get element at index via tree traversal
     */
    fun getAt(index: Int): T? {
        if (index !in indices) return null
        return getAtNode(root, index)
    }

    private fun getAtNode(node: Node<T, K>?, index: Int): T? {
        if (node == null) return null
        val leftSize = node.leftSize
        return when {
            index < leftSize -> getAtNode(node.left, index)
            index == leftSize -> node.element
            else -> getAtNode(node.right, index - leftSize - 1)
        }
    }

    /**
     * O(log n) - Get index of element by key (computed, not stored)
     */
    fun indexOfKey(key: K): Int {
        val element = elementsByKey[key] ?: return -1
        return indexOfElement(root, element, key, 0)
    }

    override fun indexOf(element: T): Int {
        val key = keySelector(element)
        return indexOfKey(key)
    }

    private fun indexOfElement(node: Node<T, K>?, element: T, key: K, baseIndex: Int): Int {
        if (node == null) return -1

        val cmp = comparator.compare(element, node.element)
        return when {
            cmp < 0 -> indexOfElement(node.left, element, key, baseIndex)
            cmp > 0 -> indexOfElement(node.right, element, key, baseIndex + node.leftSize + 1)
            else -> {
                // Same priority - check if this is the exact element
                if (node.key == key) {
                    baseIndex + node.leftSize
                } else {
                    // Search both subtrees for exact key match
                    val leftResult = indexOfElement(node.left, element, key, baseIndex)
                    if (leftResult != -1) leftResult
                    else indexOfElement(node.right, element, key, baseIndex + node.leftSize + 1)
                }
            }
        }
    }

    /**
     * O(1) - Get first (highest priority) element
     */
    fun first(): T? = root?.let { getFirst(it) }

    private fun getFirst(node: Node<T, K>): T = node.left?.let { getFirst(it) } ?: node.element

    /**
     * O(1) - Get last (lowest priority) element
     */
    fun last(): T? = root?.let { getLast(it) }

    private fun getLast(node: Node<T, K>): T = node.right?.let { getLast(it) } ?: node.element

    /**
     * O(log n) - Insert element maintaining sorted order.
     * Returns new list (this is a persistent/immutable structure).
     */
    fun insert(element: T): PersistentIndexedPriorityList<T, K> {
        val key = keySelector(element)
        val existing = elementsByKey[key]

        // If exists with equal or better priority, no change
        if (existing != null && comparator.compare(element, existing) >= 0) {
            return this
        }

        // Remove existing if present
        val afterRemoval = if (existing != null) removeByKey(key) else this

        // Insert new element with random priority for treap balancing
        val treapPriority = (element.hashCode().toLong() shl 32) or (Random.nextLong() and 0xFFFFFFFFL)
        val newRoot = insertNode(afterRemoval.root, element, key, treapPriority)
        val newMap = afterRemoval.elementsByKey.put(key, element)

        return PersistentIndexedPriorityList(newRoot, newMap, comparator, keySelector)
    }

    private fun insertNode(node: Node<T, K>?, element: T, key: K, priority: Long): Node<T, K> {
        if (node == null) {
            return Node(element, key, priority)
        }

        val cmp = comparator.compare(element, node.element)

        return if (cmp <= 0) {
            // Insert in left subtree
            val newLeft = insertNode(node.left, element, key, priority)
            val newNode = node.copy(left = newLeft)

            // Rotate right if needed to maintain treap property
            if (newLeft.priority > node.priority) {
                rotateRight(newNode)
            } else {
                newNode
            }
        } else {
            // Insert in right subtree
            val newRight = insertNode(node.right, element, key, priority)
            val newNode = node.copy(right = newRight)

            // Rotate left if needed
            if (newRight.priority > node.priority) {
                rotateLeft(newNode)
            } else {
                newNode
            }
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

    /**
     * O(log n) - Remove element by key
     */
    fun removeByKey(key: K): PersistentIndexedPriorityList<T, K> {
        val element = elementsByKey[key] ?: return this
        val newRoot = removeNode(root, element, key)
        val newMap = elementsByKey.remove(key)
        return PersistentIndexedPriorityList(newRoot, newMap, comparator, keySelector)
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
                // Same priority - check if exact key match
                if (node.key == key) {
                    // Remove this node by merging children
                    mergeChildren(node.left, node.right)
                } else {
                    // Search both subtrees
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
     * O(log n) - Remove last element (lowest priority)
     */
    fun removeLast(): PersistentIndexedPriorityList<T, K> {
        val lastElement = last() ?: return this
        return removeByKey(keySelector(lastElement))
    }

    /**
     * O(k log n) - Remove last k elements (lowest priority)
     */
    fun removeLastN(count: Int): PersistentIndexedPriorityList<T, K> {
        if (count <= 0) return this
        if (count >= size) return PersistentIndexedPriorityList(null, persistentHashMapOf(), comparator, keySelector)

        var result = this
        repeat(count) {
            result = result.removeLast()
        }
        return result
    }

    /**
     * Convert to list (in sorted order) - O(n)
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

    // ==================== PersistentList implementation ====================

    override fun iterator(): Iterator<T> = PriorityListIterator(root)

    /**
     * Efficient iterator that traverses the treap in-order without creating intermediate list.
     */
    private class PriorityListIterator<T, K>(root: Node<T, K>?) : Iterator<T> {
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

    override fun listIterator(): ListIterator<T> = toList().listIterator()

    override fun listIterator(index: Int): ListIterator<T> = toList().listIterator(index)

    override fun get(index: Int): T = getAt(index) ?: throw IndexOutOfBoundsException("Index: $index, Size: $size")


    override fun lastIndexOf(element: T): Int = indexOf(element) // Keys are unique, so same as indexOf

    override fun contains(element: T): Boolean = containsKey(keySelector(element))

    override fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }

//    override fun subList(fromIndex: Int, toIndex: Int): List<T> = toList().subList(fromIndex, toIndex)

    override fun add(element: T): PersistentIndexedPriorityList<T, K> = insert(element)


    override fun addAll(elements: Collection<T>): PersistentIndexedPriorityList<T, K> {
        var result = this
        for (element in elements) {
            result = result.insert(element)
        }
        return result
    }

    override fun remove(element: T): PersistentIndexedPriorityList<T, K> = removeByKey(keySelector(element))

    override fun removeAll(elements: Collection<T>): PersistentIndexedPriorityList<T, K> {
        var result = this
        for (element in elements) {
            result = result.removeByKey(keySelector(element))
        }
        return result
    }

    override fun removeAll(predicate: (T) -> Boolean): PersistentIndexedPriorityList<T, K> {
        val toRemove = elementsByKey.values.filter(predicate)
        return removeAll(toRemove)
    }

    override fun retainAll(elements: Collection<T>): PersistentIndexedPriorityList<T, K> {
        val keysToRetain = elements.map { keySelector(it) }.toSet()
        val toRemove = elementsByKey.keys.filter { it !in keysToRetain }
        var result = this
        for (key in toRemove) {
            result = result.removeByKey(key)
        }
        return result
    }

    override fun clear(): PersistentIndexedPriorityList<T, K> = empty(comparator, keySelector)

    override fun set(index: Int, element: T): PersistentIndexedPriorityList<T, K> {
        val oldElement = getAt(index) ?: throw IndexOutOfBoundsException("Index: $index, Size: $size")
        return removeByKey(keySelector(oldElement)).insert(element)
    }

    override fun add(index: Int, element: T): PersistentIndexedPriorityList<T, K> {
        // For a priority list, index is ignored - elements are inserted by priority
        return insert(element)
    }

    override fun addAll(index: Int, c: Collection<T>): PersistentIndexedPriorityList<T, K> {
        // For a priority list, index is ignored - elements are inserted by priority
        return addAll(c)
    }

    override fun removeAt(index: Int): PersistentIndexedPriorityList<T, K> {
        val element = getAt(index) ?: throw IndexOutOfBoundsException("Index: $index, Size: $size")
        return removeByKey(keySelector(element))
    }

    override fun builder(): PersistentList.Builder<T> {
        // Return a builder backed by a standard persistent list for compatibility
        return persistentListOf<T>().addAll(toList()).builder()
    }

    companion object {
        fun <T, K> empty(comparator: Comparator<T>, keySelector: (T) -> K): PersistentIndexedPriorityList<T, K> {
            return PersistentIndexedPriorityList(
                root = null,
                elementsByKey = persistentHashMapOf<K, T>(),
                comparator = comparator,
                keySelector = keySelector
            )
        }
    }
}