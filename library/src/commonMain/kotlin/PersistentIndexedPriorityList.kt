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

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf

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
 */
class PersistentIndexedPriorityList<T, K> private constructor(
    internal val root: Node<T, K>?,
    internal val elementsByKey: PersistentMap<K, T>,
    private val comparator: Comparator<T>,
    private val keySelector: (T) -> K
) : Iterable<T> {

    /**
     * Treap node with subtree size for O(log n) index calculations.
     * Stores key for efficient exact-match during removal.
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

    val size: Int get() = root?.size ?: 0

    fun isEmpty(): Boolean = root == null

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
        if (index < 0 || index >= size) return null
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
    fun indexOf(key: K): Int {
        val element = elementsByKey[key] ?: return -1
        return indexOfElement(root, element, key, 0)
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
        val treapPriority = (element.hashCode().toLong() shl 32) or (kotlin.random.Random.nextLong() and 0xFFFFFFFFL)
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
        return Node(left.element, left.key, left.priority, left.left, node.copy(left = left.right))
    }

    private fun rotateLeft(node: Node<T, K>): Node<T, K> {
        val right = node.right ?: return node
        return Node(right.element, right.key, right.priority, node.copy(right = right.left), right.right)
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
     * O(m log(n+m)) - Batch insert multiple elements.
     * More efficient than calling insert() m times because:
     * 1. Pre-filters duplicates and keeps only best priority per key
     * 2. Single map rebuild via builder instead of m separate rebuilds
     * 3. Reduced CAS contention (single atomic update vs m updates)
     *
     * @param elements The elements to insert
     * @return New list with all valid elements inserted
     */
    fun insertAll(elements: Iterable<T>): PersistentIndexedPriorityList<T, K> {
        val inputList = when (elements) {
            is List -> elements
            is Collection -> elements.toList()
            else -> elements.toList()
        }
        if (inputList.isEmpty()) return this

        // Step 1: Group by key and keep only the best priority for each key
        val bestByKey = HashMap<K, T>(minOf(inputList.size, 10000))
        for (element in inputList) {
            val key = keySelector(element)
            val existing = bestByKey[key]
            if (existing == null || comparator.compare(element, existing) < 0) {
                bestByKey[key] = element
            }
        }

        // Step 2: Filter against existing elements and prepare insertions
        var currentRoot = root
        var mapBuilder = elementsByKey.builder()
        var hasChanges = false

        for ((key, element) in bestByKey) {
            val existingInTree = mapBuilder[key]
            
            if (existingInTree == null) {
                // New element - insert directly
                val treapPriority = (element.hashCode().toLong() shl 32) or (kotlin.random.Random.nextLong() and 0xFFFFFFFFL)
                currentRoot = insertNode(currentRoot, element, key, treapPriority)
                mapBuilder[key] = element
                hasChanges = true
            } else if (comparator.compare(element, existingInTree) < 0) {
                // New element is better - remove old, insert new
                currentRoot = removeNode(currentRoot, existingInTree, key)
                val treapPriority = (element.hashCode().toLong() shl 32) or (kotlin.random.Random.nextLong() and 0xFFFFFFFFL)
                currentRoot = insertNode(currentRoot, element, key, treapPriority)
                mapBuilder[key] = element
                hasChanges = true
            }
            // If existing is equal or better, skip
        }

        if (!hasChanges) return this

        return PersistentIndexedPriorityList(currentRoot, mapBuilder.build(), comparator, keySelector)
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

    override fun iterator(): Iterator<T> = toList().iterator()

    companion object {
        fun <T, K> empty(comparator: Comparator<T>, keySelector: (T) -> K): PersistentIndexedPriorityList<T, K> {
            return PersistentIndexedPriorityList(null, persistentHashMapOf(), comparator, keySelector)
        }
    }
}
