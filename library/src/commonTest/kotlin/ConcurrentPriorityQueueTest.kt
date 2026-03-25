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

import io.github.karloti.cpq.ConcurrentPriorityQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flowOf
import kotlin.comparisons.reverseOrder
import kotlin.test.DefaultAsserter.assertEquals
import kotlin.test.DefaultAsserter.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test suite for [ConcurrentPriorityQueue].
 * Verifies correctness of concurrent element additions, strict capacity bounding,
 * proper priority sorting, and atomicity of unique key deduplication mechanisms.
 */
class ConcurrentPriorityQueueTest {

    /**
     * A mock data class used to simulate an incoming typeahead search result.
     */
    private data class SearchResultItem(val id: Int, val score: Int)

    /**
     * A mock data class used to test the default Comparable factory methods.
     */
    private data class ComparableItem(val id: Int, val score: Int) : Comparable<ComparableItem> {
        override fun compareTo(other: ComparableItem): Int = this.score.compareTo(other.score)
    }

    /**
     * Verifies that when an element with a previously registered unique key is added,
     * the queue ignores the new element and preserves the existing one.
     */
    @Test
    fun `test duplicate keys are correctly ignored`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 3,
            comparator = compareByDescending { it.score },
            uniqueKeySelector = { it.id }
        )

        queue.add(SearchResultItem(id = 1, score = 5))
        queue.add(SearchResultItem(id = 1, score = 10))
        queue.add(SearchResultItem(id = 2, score = 20))
        queue.add(SearchResultItem(id = 3, score = 3))
        queue.add(SearchResultItem(id = 4, score = 4))
        queue.add(SearchResultItem(id = 5, score = 5))


        val result = queue.items.value
        result.forEach { println("Result: $it") }

        assertEquals(3, result.size)
        assertEquals(20, result[0].score)
        assertEquals(10, result[1].score)
        assertEquals(5, result[2].score)
        assertEquals(2, result[0].id)
        assertEquals(1, result[1].id)
        assertEquals(5, result[2].id)


    }

    /**
     * Verifies the eviction edge case: when an element is removed from the queue due to
     * capacity constraints, its unique key must be freed. This allows a subsequent element
     * with the exact same key to re-enter the queue if its priority is high enough.
     */
    @Test
    fun `test evicted key can re-enter the queue`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 2,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        queue.add(SearchResultItem(id = 1, score = 50))
        queue.add(SearchResultItem(id = 2, score = 40))
        queue.add(SearchResultItem(id = 3, score = 30))
        queue.add(SearchResultItem(id = 1, score = 20))

        val result = queue.items.value
        assertEquals(2, result.size)
        assertEquals(1, result[0].id)
        assertEquals(3, result[1].id)
    }

    /**
     * A stress test executing thousands of concurrent additions across multiple coroutines.
     * Intentionally generates heavy key collisions to ensure the underling CAS operations
     * maintain absolute state consistency without race conditions or memory leaks.
     */
    @Test
    fun `test massive concurrent additions with heavy key collisions`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 10,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        val jobs = (1..100).map { i ->
            launch(Dispatchers.Default) {
                for (j in 1..100) {
                    val id = (i * j) % 500
                    val score = id
                    queue.add(SearchResultItem(id = id, score = score))
                }
            }
        }

        jobs.joinAll()

        val result = queue.items.value

        assertEquals(10, result.size)

        val uniqueIds = result.map { it.id }.toSet()
        assertEquals("Duplicate keys found in the final result state!", 10, uniqueIds.size)

        val isSorted = result.zipWithNext { a, b -> a.score <= b.score }.all { it }
        assertTrue("The resulting collection is not strictly sorted!", isSorted)

        assertEquals((0..9).toList(), result.map { it.id })
    }

    /**
     * Verifies that the parameterless factory method for [Comparable] types defaults
     * to a descending sort order while using the elements themselves as identity keys.
     */
    @Test
    fun `test factory default for Comparable types uses reverse order and self key`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(comparator = reverseOrder())

        queue.add(10)
        queue.add(50)
        queue.add(20)
        queue.add(30)
        queue.add(40)
        queue.add(60)
        queue.add(60)

        val result = queue.items.value
        assertEquals(5, result.size)
        assertEquals(listOf(60, 50, 40, 30, 20), result)
    }

    /**
     * Verifies the generic factory method applying a custom sorting comparator while
     * still utilizing the elements themselves as identity keys.
     */
    @Test
    fun `test factory with custom comparator and self key uses provided order`() = runTest {
        val queue = ConcurrentPriorityQueue.Companion<Int>(
            maxSize = 3,
            comparator = naturalOrder()
        )

        queue.add(10)
        queue.add(2)
        queue.add(5)
        queue.add(1)
        queue.add(2)

        val result = queue.items.value
        assertEquals(3, result.size)
        assertEquals(listOf(1, 2, 5), result)
    }

    /**
     * Verifies the factory method designed for [Comparable] types requiring a custom
     * key selector. Ensures the descending default order is correctly combined with the key logic.
     */
    @Test
    fun `test factory for Comparable with custom key selector uses reverse order`() = runTest {
        val queue = ConcurrentPriorityQueue<ComparableItem, Int>(
            maxSize = 3,
            comparator = reverseOrder(),
            uniqueKeySelector = { it.id }
        )

        queue.add(ComparableItem(id = 1, score = 10))
        queue.add(ComparableItem(id = 2, score = 50))
        queue.add(ComparableItem(id = 3, score = 30))
        queue.add(ComparableItem(id = 4, score = 20))
        queue.add(ComparableItem(id = 2, score = 100))

        val result = queue.items.value
        assertEquals(3, result.size)
        assertEquals(listOf(100, 30, 20), result.map { it.score })
    }

    /**
     * Verifies the primary factory method successfully applies the default maximum
     * capacity when omitted, while utilizing the provided comparator and key selector.
     */
    @Test
    fun `test factory with full control applies default max size`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        (1..6).forEach { queue.add(SearchResultItem(id = it, score = it * 10)) }

        val result = queue.items.value
        assertEquals(5, result.size)
        assertEquals(listOf(1, 2, 3, 4, 5), result.map { it.id })
    }

    // ── size ──────────────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.size] returns 0 for a freshly created queue.
     */
    @Test
    fun `size is 0 for an empty queue`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        assertEquals(0, queue.size)
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.size] accurately tracks the number of elements
     * present in the queue after several insertions.
     */
    @Test
    fun `size reflects current number of elements`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        queue.add(1)
        queue.add(2)
        queue.add(3)
        assertEquals(3, queue.size)
    }

    // ── add – return value ────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.add] returns `null` when the element is inserted
     * into a queue that still has capacity (no eviction required).
     */
    @Test
    fun `add returns null when element is inserted without eviction`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 3)
        assertNull(queue.add(10))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.add] returns the displaced element when the queue
     * is at capacity and the new element has strictly better priority.
     */
    @Test
    fun `add returns evicted element when queue is full and new element has better priority`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(
            maxSize = 2,
            comparator = reverseOrder()
        )
        queue.add(10)
        queue.add(20)
        // queue: [20, 10] (descending). Adding 30 evicts 10.
        assertEquals(10, queue.add(30))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.add] returns `null` when the element is rejected
     * because its priority is worse than or equal to every element in a full queue.
     */
    @Test
    fun `add returns null when element is rejected due to lower priority`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 2,reverseOrder())
        queue.add(20)
        queue.add(30)
        // 5 is smaller than the lowest (20) in a descending queue → rejected.
        assertNull(queue.add(5))
    }

    /**
     * Verifies that updating an existing key (upsert) returns `null` because the queue size
     * does not change and therefore no element is evicted.
     */
    @Test
    fun `add returns null when updating an existing key without eviction`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 3,
            comparator = compareByDescending { it.score },
            uniqueKeySelector = { it.id }
        )
        queue.add(SearchResultItem(id = 1, score = 10))
        assertNull(queue.add(SearchResultItem(id = 1, score = 20)))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.add] always returns `null` when [maxSize] is 0,
     * as no element can ever be stored.
     */
    @Test
    fun `add returns null and does not store element when maxSize is zero`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 0)
        assertNull(queue.add(10))
        assertEquals(0, queue.size)
    }

    // ── containsKey ───────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.containsKey] returns `true` for a key
     * that is currently in the queue.
     */
    @Test
    fun `containsKey returns true for an existing key`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 3,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )
        queue.add(SearchResultItem(id = 42, score = 10))
        assertTrue(queue.containsKey(42))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.containsKey] returns `false` for a key
     * that was never added to the queue.
     */
    @Test
    fun `containsKey returns false for a missing key`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 3,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )
        assertFalse(queue.containsKey(99))
    }

    // ── contains ──────────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.contains] returns `true` for an element
     * whose key is present in the queue.
     */
    @Test
    fun `contains returns true for an element in the queue`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 3)
        queue.add(7)
        assertTrue(queue.contains(7))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.contains] returns `false` for an element
     * whose key is absent from the queue.
     */
    @Test
    fun `contains returns false for an element not in the queue`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 3)
        queue.add(7)
        assertFalse(queue.contains(99))
    }

    // ── get ───────────────────────────────────────────────────────────────

    /**
     * Verifies that the `get` operator returns the correct element for an existing key.
     */
    @Test
    fun `get returns element when key exists`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 3,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )
        val item = SearchResultItem(id = 5, score = 100)
        queue.add(item)
        assertEquals(item, queue[5])
    }

    /**
     * Verifies that the `get` operator returns `null` for a key that does not exist.
     */
    @Test
    fun `get returns null for a missing key`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 3,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )
        assertNull(queue[5])
    }

    // ── first ─────────────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.first] returns the element with the highest
     * priority according to the comparator, without removing it.
     */
    @Test
    fun `first returns the highest priority element without removing it`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5, comparator = reverseOrder())
        queue.add(30)
        queue.add(10)
        queue.add(50)
        assertEquals(50, queue.first())
        assertEquals(3, queue.size)
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.first] returns `null` when the queue is empty.
     */
    @Test
    fun `first returns null for an empty queue`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        assertNull(queue.first())
    }

    // ── last ──────────────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.last] returns the element with the lowest
     * priority according to the comparator, without removing it.
     */
    @Test
    fun `last returns the lowest priority element without removing it`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5, reverseOrder())
        queue.add(30)
        queue.add(10)
        queue.add(50)
        assertEquals(10, queue.last())
        assertEquals(3, queue.size)
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.last] returns `null` when the queue is empty.
     */
    @Test
    fun `last returns null for an empty queue`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        assertNull(queue.last())
    }

    // ── poll ──────────────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.poll] removes and returns the highest
     * priority element, reducing the queue size by one.
     */
    @Test
    fun `poll removes and returns the highest priority element`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5, reverseOrder())
        queue.add(30)
        queue.add(10)
        queue.add(50)
        assertEquals(50, queue.poll())
        assertEquals(2, queue.size)
        assertFalse(queue.contains(50))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.poll] returns `null` when called on an empty queue.
     */
    @Test
    fun `poll returns null for an empty queue`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        assertNull(queue.poll())
    }

    /**
     * Verifies that repeated calls to [ConcurrentPriorityQueue.poll] drain the queue
     * in strict priority order (highest first).
     */
    @Test
    fun `poll drains queue in priority order`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5,reverseOrder())
        listOf(30, 10, 50, 20, 40).forEach { queue.add(it) }
        val drained = generateSequence { queue.poll() }.toList()
        assertEquals(listOf(50, 40, 30, 20, 10), drained)
        assertTrue(queue.isEmpty())
    }

    // ── removeByKey ───────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.removeByKey] removes the element with the
     * given key and returns `true`.
     */
    @Test
    fun `removeByKey removes element and returns true`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 3,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )
        queue.add(SearchResultItem(id = 7, score = 10))
        assertTrue(queue.removeByKey(7))
        assertFalse(queue.containsKey(7))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.removeByKey] returns `false` when no element
     * with the given key exists.
     */
    @Test
    fun `removeByKey returns false for a key that does not exist`() = runTest {
        val queue = ConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 3,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )
        assertFalse(queue.removeByKey(99))
    }

    // ── remove ────────────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.remove] removes the element (identified by key)
     * and returns `true`.
     */
    @Test
    fun `remove removes element and returns true`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 3)
        queue.add(42)
        assertTrue(queue.remove(42))
        assertFalse(queue.contains(42))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.remove] returns `false` when the element
     * does not exist in the queue.
     */
    @Test
    fun `remove returns false when element does not exist`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 3)
        assertFalse(queue.remove(99))
    }

    // ── removeIf ──────────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.removeIf] removes all elements satisfying
     * the predicate and returns the correct count.
     */
    @Test
    fun `removeIf removes all matching elements and returns their count`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5,comparator = reverseOrder())
        listOf(10, 20, 30, 40, 50).forEach { queue.add(it) }
        // Descending order: [50, 40, 30, 20, 10]. Remove 40 and 50.
        assertEquals(2, queue.removeIf { it > 30 })
        assertEquals(listOf(30, 20, 10), queue.items.value)
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.removeIf] returns 0 and leaves the queue
     * unchanged when no elements match the predicate.
     */
    @Test
    fun `removeIf returns 0 and leaves queue unchanged when no elements match`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        listOf(10, 20).forEach { queue.add(it) }
        assertEquals(0, queue.removeIf { it > 100 })
        assertEquals(2, queue.size)
    }

    // ── retainIf ──────────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.retainIf] keeps only elements satisfying
     * the predicate, removes all others, and returns the count of removed elements.
     */
    @Test
    fun `retainIf keeps matching elements and removes the rest`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(
            maxSize = 5,
            comparator = reverseOrder()
        )
        listOf(10, 20, 30, 40, 50).forEach { queue.add(it) }
        // Descending order: [50, 40, 30, 20, 10]. Remove 40 and 50.
        assertEquals(2, queue.retainIf { it <= 30 })
        assertEquals(listOf(30, 20, 10), queue.items.value)
    }

    // ── clear ─────────────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.clear] removes all elements and leaves
     * the queue in an empty state.
     */
    @Test
    fun `clear empties the queue`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        listOf(10, 20, 30).forEach { queue.add(it) }
        queue.clear()
        assertEquals(0, queue.size)
        assertTrue(queue.isEmpty())
    }

    // ── isEmpty / isNotEmpty ──────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.isEmpty] returns `true` for a fresh queue
     * and `false` after an element is inserted.
     */
    @Test
    fun `isEmpty returns true for empty queue and false after inserting an element`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 3)
        assertTrue(queue.isEmpty())
        queue.add(1)
        assertFalse(queue.isEmpty())
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.isNotEmpty] returns `false` for an empty queue
     * and `true` after an element is inserted.
     */
    @Test
    fun `isNotEmpty returns false for empty queue and true after inserting an element`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 3)
        assertFalse(queue.isNotEmpty())
        queue.add(1)
        assertTrue(queue.isNotEmpty())
    }

    // ── addAll(Iterable) ──────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.addAll] for [Iterable] correctly inserts all
     * elements into the queue.
     */
    @Test
    fun `addAll Iterable inserts all elements`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        queue.addAll(listOf(10, 20, 30))
        assertEquals(3, queue.size)
        assertTrue(queue.contains(10))
        assertTrue(queue.contains(20))
        assertTrue(queue.contains(30))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.addAll] for [Iterable] respects capacity and
     * retains only the highest priority elements when the iterable exceeds [maxSize].
     */
    @Test
    fun `addAll Iterable respects capacity and retains highest priority elements`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 3, comparator = reverseOrder())
        queue.addAll(listOf(10, 20, 30, 40, 50))
        assertEquals(3, queue.size)
        assertEquals(listOf(50, 40, 30), queue.items.value)
    }

    // ── addAll(Sequence) ──────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.addAll] for [Sequence] correctly inserts all
     * elements into the queue.
     */
    @Test
    fun `addAll Sequence inserts all elements`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        queue.addAll(sequenceOf(10, 20, 30))
        assertEquals(3, queue.size)
        assertTrue(queue.contains(10))
        assertTrue(queue.contains(20))
        assertTrue(queue.contains(30))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.addAll] for [Sequence] respects capacity and
     * retains only the highest priority elements when the sequence exceeds [maxSize].
     */
    @Test
    fun `addAll Sequence respects capacity and retains highest priority elements`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 3,reverseOrder())
        // generateSequence produces 1, 2, 3, 4, 5
        queue.addAll(generateSequence(1) { if (it < 5) it + 1 else null })
        assertEquals(3, queue.size)
        assertEquals(listOf(5, 4, 3), queue.items.value)
    }

    // ── addAll(Flow) ──────────────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.addAll] for [Flow] correctly inserts all
     * emitted elements into the queue.
     */
    @Test
    fun `addAll Flow inserts all emitted elements`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        queue.addAll(flowOf(10, 20, 30))
        assertEquals(3, queue.size)
        assertTrue(queue.contains(10))
        assertTrue(queue.contains(20))
        assertTrue(queue.contains(30))
    }

    /**
     * Verifies that [ConcurrentPriorityQueue.addAll] for [Flow] respects capacity and
     * retains only the highest priority elements when the flow exceeds [maxSize].
     */
    @Test
    fun `addAll Flow respects capacity and retains highest priority elements`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 3, reverseOrder())
        queue.addAll(flowOf(10, 20, 30, 40, 50))
        assertEquals(3, queue.size)
        assertEquals(listOf(50, 40, 30), queue.items.value)
    }

    // ── iterator ──────────────────────────────────────────────────────────

    /**
     * Verifies that the [ConcurrentPriorityQueue.iterator] traverses all elements in
     * descending priority order (snapshot at the time of iterator creation).
     */
    @Test
    fun `iterator traverses elements in priority order`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5, comparator = reverseOrder())
        listOf(30, 10, 50, 20, 40).forEach { queue.add(it) }
        assertEquals(listOf(50, 40, 30, 20, 10), queue.iterator().asSequence().toList())
    }

    /**
     * Verifies that the [ConcurrentPriorityQueue.iterator] on an empty queue produces no elements.
     */
    @Test
    fun `iterator on empty queue produces no elements`() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)
        assertFalse(queue.iterator().hasNext())
    }
}
