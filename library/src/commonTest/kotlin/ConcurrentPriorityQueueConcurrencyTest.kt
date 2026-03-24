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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * Thread-safety test suite for [ConcurrentPriorityQueue].
 *
 * Every test launches multiple coroutines on [Dispatchers.Default] to exercise
 * the underlying CAS-based [MutableStateFlow.update] from genuinely concurrent
 * threads. The tests verify that no elements are lost, duplicated, or corrupted
 * under contention, and that all structural invariants hold after the concurrent
 * operations complete.
 *
 * ## Test Coverage
 *
 * - **concurrent adds from many coroutines maintain capacity and uniqueness**:
 *   20 coroutines × 100 adds each. Verifies capacity bound, uniqueness of keys, and sort order.
 *
 * - **concurrent upserts with overlapping keys converge to best priorities**:
 *   10 coroutines writing to overlapping keys (0..99). Ensures best-priority-wins semantics.
 *
 * - **concurrent adds and removes maintain structural invariants**:
 *   10 adder + 10 remover coroutines racing on same keys. Verifies no corruption or lost elements.
 *
 * - **concurrent add and poll never return corrupted elements**:
 *   10 producer + 5 consumer coroutines. Validates every polled element for consistency.
 *
 * - **concurrent add and removeIf maintain consistency**:
 *   4 adders + 4 removeIf coroutines. Ensures removeIf predicate behavior under contention.
 *
 * - **concurrent add and clear never corrupt the queue**:
 *   10 adders + 3 clearers. Verifies queue never enters invalid state despite concurrent clear.
 *
 * - **concurrent add and removeByKey leave no ghost entries**:
 *   Verifies `containsKey` and `get` consistency after concurrent add + removeByKey operations.
 *
 * - **concurrent add and retainIf maintain valid state**:
 *   5 adders + 3 retainIf coroutines. Validates retain semantics under contention.
 *
 * - **mixed concurrent mutations never corrupt queue state**:
 *   Stress test using all mutation methods simultaneously: add, poll, removeIf, retainIf,
 *   removeByKey, and clear. Only guarantee: queue never violates size bounds, key uniqueness, or sort order.
 *
 * - **items snapshot is consistent under concurrent mutations**:
 *   5 mutators + 5 readers. Verifies snapshot isolation: every snapshot read is internally consistent.
 */
class ConcurrentPriorityQueueConcurrencyTest {

    private data class Item(val id: Int, val score: Int)

    // ── concurrent add ───────────────────────────────────────────────────

    /**
     * Many coroutines add distinct elements simultaneously.
     * After all coroutines finish, the queue must contain exactly [maxSize]
     * elements, all unique, sorted by priority.
     */
    @Test
    fun `concurrent adds from many coroutines maintain capacity and uniqueness`() = runTest {
        val maxSize = if (TestConfig.LOCAL) 50 else 20
        val coroutines = if (TestConfig.LOCAL) 20 else 5
        val elementsPerCoroutine = if (TestConfig.LOCAL) 100 else 20

        val queue = ConcurrentPriorityQueue<Item, Int>(
            maxSize = maxSize,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        coroutineScope {
            (0 until coroutines).map { c ->
                launch(Dispatchers.Default) {
                    repeat(elementsPerCoroutine) { i ->
                        val id = c * elementsPerCoroutine + i
                        queue.add(Item(id = id, score = id))
                    }
                }
            }.joinAll()
        }

        val result = queue.items.value
        assertEquals(maxSize, result.size, "Queue must be bounded to maxSize")

        val ids = result.map { it.id }.toSet()
        assertEquals(maxSize, ids.size, "All elements must have unique keys")

        val sorted = result.zipWithNext { a, b -> a.score <= b.score }.all { it }
        assertTrue(sorted, "Elements must be in sorted order")

        // lowest 50 scores (0..49) should survive in an ascending queue
        val expectedIds = (0 until maxSize).toSet()
        assertEquals(expectedIds, ids, "Queue should contain the top-priority elements")
    }

    // ── concurrent add with key collisions ───────────────────────────────

    /**
     * Multiple coroutines attempt to insert elements with overlapping keys.
     * The upsert logic must handle CAS retries without losing updates.
     */
    @Test
    fun `concurrent upserts with overlapping keys converge to best priorities`() = runTest {
        val maxSize = 100
        val coroutines = 10
        val keysPerCoroutine = 100 // all coroutines write to keys 0..99

        val queue = ConcurrentPriorityQueue<Item, Int>(
            maxSize = maxSize,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        coroutineScope {
            (0 until coroutines).map { c ->
                launch(Dispatchers.Default) {
                    repeat(keysPerCoroutine) { key ->
                        // Each coroutine uses a different score for the same key.
                        // Score = coroutineIndex * 100 + key; lower is better.
                        queue.add(Item(id = key, score = c * 100 + key))
                    }
                }
            }.joinAll()
        }

        val result = queue.items.value
        assertEquals(maxSize, result.size)

        // Coroutine 0 writes the lowest scores (0..99), so those should win.
        for (item in result) {
            assertEquals(
                item.id, item.score,
                "Key ${item.id} should hold score ${item.id} (from coroutine 0), got ${item.score}"
            )
        }
    }

    // ── concurrent add + remove ──────────────────────────────────────────

    /**
     * Half the coroutines add elements while the other half remove them.
     * After completion, the queue must satisfy all structural invariants:
     * bounded size, unique keys, sorted order.
     */
    @Test
    fun `concurrent adds and removes maintain structural invariants`() = runTest {
        val maxSize = 30
        val queue = ConcurrentPriorityQueue<Item, Int>(
            maxSize = maxSize,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        coroutineScope {
            // Adders: insert keys 0..499
            val adders = (0 until 10).map { c ->
                launch(Dispatchers.Default) {
                    repeat(50) { i ->
                        val id = c * 50 + i
                        queue.add(Item(id = id, score = id))
                    }
                }
            }

            // Removers: remove keys 0..499 (race with adders)
            val removers = (0 until 10).map { c ->
                launch(Dispatchers.Default) {
                    repeat(50) { i ->
                        val id = c * 50 + i
                        queue.remove(Item(id = id, score = id))
                    }
                }
            }

            (adders + removers).joinAll()
        }

        val result = queue.items.value
        assertTrue(result.size <= maxSize, "Queue must not exceed maxSize")

        val ids = result.map { it.id }.toSet()
        assertEquals(result.size, ids.size, "No duplicate keys")

        val sorted = result.zipWithNext { a, b -> a.score <= b.score }.all { it }
        assertTrue(sorted, "Elements must be in sorted order")
    }

    // ── concurrent add + poll ────────────────────────────────────────────

    /**
     * Producers add elements while consumers poll them concurrently.
     * Verifies that poll never returns a corrupted element and that the
     * queue is drainable to empty after all coroutines finish.
     */
    @Test
    fun `concurrent add and poll never return corrupted elements`() = runTest {
        val maxSize = 100
        val queue = ConcurrentPriorityQueue<Item, Int>(
            maxSize = maxSize,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        var pollCount = 0

        coroutineScope {
            // Producers: add items with ids 0..999
            val producers = (0 until 10).map { c ->
                launch(Dispatchers.Default) {
                    repeat(100) { i ->
                        val id = c * 100 + i
                        queue.add(Item(id = id, score = id))
                    }
                }
            }

            // Consumers: poll and verify each item is valid
            val consumers = (0 until 5).map {
                launch(Dispatchers.Default) {
                    repeat(100) {
                        val item = queue.poll()
                        if (item != null) {
                            // Verify the item is self-consistent
                            assertEquals(item.id, item.score, "Polled item is corrupted")
                            pollCount++
                        }
                        yield()
                    }
                }
            }

            (producers + consumers).joinAll()
        }

        // Drain remaining
        var remaining = 0
        generateSequence { queue.poll() }.forEach {
            assertEquals(it.id, it.score, "Drained item is corrupted")
            remaining++
        }

        assertTrue(queue.isEmpty(), "Queue should be empty after full drain")
        assertTrue(pollCount + remaining > 0, "At least some elements should have been processed")
    }

    // ── concurrent add + removeIf ────────────────────────────────────────

    /**
     * One set of coroutines adds elements while another concurrently calls
     * [ConcurrentPriorityQueue.removeIf]. The queue must remain consistent.
     */
    @Test
    fun `concurrent add and removeIf maintain consistency`() = runTest {
        val maxSize = 50
        val queue = ConcurrentPriorityQueue<Item, Int>(
            maxSize = maxSize,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        coroutineScope {
            // Adders: insert elements with scores 0..199
            val adders = (0 until 4).map { c ->
                launch(Dispatchers.Default) {
                    repeat(50) { i ->
                        val id = c * 50 + i
                        queue.add(Item(id = id, score = id))
                    }
                }
            }

            // Removers: periodically remove elements with even scores
            val removers = (0 until 4).map {
                launch(Dispatchers.Default) {
                    repeat(10) {
                        queue.removeIf { it.score % 2 == 0 }
                        yield()
                    }
                }
            }

            (adders + removers).joinAll()
        }

        val result = queue.items.value
        assertTrue(result.size <= maxSize, "Queue must not exceed maxSize")

        val ids = result.map { it.id }.toSet()
        assertEquals(result.size, ids.size, "No duplicate keys after concurrent removeIf")

        val sorted = result.zipWithNext { a, b -> a.score <= b.score }.all { it }
        assertTrue(sorted, "Elements must be in sorted order")
    }

    // ── concurrent add + clear ───────────────────────────────────────────

    /**
     * Coroutines add elements while others repeatedly clear the queue.
     * The queue must never be in an inconsistent state: either empty or
     * containing valid, sorted, unique elements within the capacity bound.
     */
    @Test
    fun `concurrent add and clear never corrupt the queue`() = runTest {
        val maxSize = 20
        val queue = ConcurrentPriorityQueue<Item, Int>(
            maxSize = maxSize,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        coroutineScope {
            val adders = (0 until 10).map { c ->
                launch(Dispatchers.Default) {
                    repeat(100) { i ->
                        val id = c * 100 + i
                        queue.add(Item(id = id, score = id))
                    }
                }
            }

            val clearers = (0 until 3).map {
                launch(Dispatchers.Default) {
                    repeat(20) {
                        queue.clear()
                        yield()
                    }
                }
            }

            (adders + clearers).joinAll()
        }

        val result = queue.items.value
        assertTrue(result.size <= maxSize, "Queue must not exceed maxSize")

        val ids = result.map { it.id }.toSet()
        assertEquals(result.size, ids.size, "No duplicate keys after concurrent clear")

        val sorted = result.zipWithNext { a, b -> a.score <= b.score }.all { it }
        assertTrue(sorted, "Elements must be in sorted order after concurrent clear")
    }

    // ── concurrent add + removeByKey ─────────────────────────────────────

    /**
     * Coroutines add elements with known keys while others remove specific keys.
     * Verifies that removeByKey under contention does not leave ghost entries.
     */
    @Test
    fun `concurrent add and removeByKey leave no ghost entries`() = runTest {
        val maxSize = 100
        val queue = ConcurrentPriorityQueue<Item, Int>(
            maxSize = maxSize,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        coroutineScope {
            // Add keys 0..99
            val adders = (0 until 10).map { c ->
                launch(Dispatchers.Default) {
                    repeat(10) { i ->
                        val id = c * 10 + i
                        queue.add(Item(id = id, score = id))
                    }
                }
            }

            // Remove even keys
            val removers = (0 until 5).map { c ->
                launch(Dispatchers.Default) {
                    repeat(10) { i ->
                        val id = (c * 10 + i) * 2 // even keys 0,2,4,...
                        if (id < 100) queue.removeByKey(id)
                    }
                }
            }

            (adders + removers).joinAll()
        }

        val result = queue.items.value
        for (item in result) {
            assertTrue(
                queue.containsKey(item.id),
                "containsKey must agree with items for key ${item.id}"
            )
            assertEquals(
                item, queue[item.id],
                "get[key] must return the same element present in items"
            )
        }

        // No element that is NOT in items should be found via containsKey
        val presentIds = result.map { it.id }.toSet()
        for (id in 0 until 100) {
            if (id !in presentIds) {
                assertFalse(
                    queue.containsKey(id),
                    "Key $id is not in items but containsKey returned true"
                )
            }
        }
    }

    // ── concurrent add + retainIf ────────────────────────────────────────

    /**
     * Coroutines add elements while others call [ConcurrentPriorityQueue.retainIf].
     * The queue must only contain elements that match the retain predicate
     * (unless they were added after the retain call).
     */
    @Test
    fun `concurrent add and retainIf maintain valid state`() = runTest {
        val maxSize = 50
        val queue = ConcurrentPriorityQueue<Item, Int>(
            maxSize = maxSize,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        coroutineScope {
            val adders = (0 until 5).map { c ->
                launch(Dispatchers.Default) {
                    repeat(100) { i ->
                        val id = c * 100 + i
                        queue.add(Item(id = id, score = id))
                    }
                }
            }

            val retainers = (0 until 3).map {
                launch(Dispatchers.Default) {
                    repeat(10) {
                        queue.retainIf { it.score < 250 }
                        yield()
                    }
                }
            }

            (adders + retainers).joinAll()
        }

        val result = queue.items.value
        assertTrue(result.size <= maxSize, "Queue must not exceed maxSize")

        val ids = result.map { it.id }.toSet()
        assertEquals(result.size, ids.size, "No duplicate keys after concurrent retainIf")

        val sorted = result.zipWithNext { a, b -> a.score <= b.score }.all { it }
        assertTrue(sorted, "Elements must be in sorted order")
    }

    // ── mixed mutation storm ─────────────────────────────────────────────

    /**
     * A stress test that concurrently exercises **all** mutation methods at once:
     * add, remove, removeByKey, removeIf, retainIf, poll, and clear.
     *
     * The only guarantee is that the queue never enters an invalid state:
     * size within bounds, unique keys, and sorted order.
     */
    @Test
    fun `mixed concurrent mutations never corrupt queue state`() = runTest {
        val maxSize = 30
        val queue = ConcurrentPriorityQueue<Item, Int>(
            maxSize = maxSize,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        coroutineScope {
            // Adders
            val adders = (0 until 5).map { c ->
                launch(Dispatchers.Default) {
                    repeat(200) { i ->
                        queue.add(Item(id = (c * 200 + i) % 500, score = (c * 200 + i) % 500))
                    }
                }
            }

            // Pollers
            val pollers = (0 until 3).map {
                launch(Dispatchers.Default) {
                    repeat(50) {
                        queue.poll()
                        yield()
                    }
                }
            }

            // RemoveIf
            val removeIfJobs = (0 until 2).map {
                launch(Dispatchers.Default) {
                    repeat(10) {
                        queue.removeIf { it.score % 7 == 0 }
                        yield()
                    }
                }
            }

            // RetainIf
            val retainIfJobs = (0 until 2).map {
                launch(Dispatchers.Default) {
                    repeat(5) {
                        queue.retainIf { it.score < 400 }
                        yield()
                    }
                }
            }

            // Clear
            val clearJob = launch(Dispatchers.Default) {
                repeat(3) {
                    queue.clear()
                    yield()
                }
            }

            // RemoveByKey
            val removeByKeyJobs = (0 until 3).map { c ->
                launch(Dispatchers.Default) {
                    repeat(30) { i ->
                        queue.removeByKey((c * 30 + i) % 500)
                        yield()
                    }
                }
            }

            (adders + pollers + removeIfJobs + retainIfJobs + removeByKeyJobs + listOf(clearJob)).joinAll()
        }

        val result = queue.items.value
        assertTrue(result.size <= maxSize, "Queue must not exceed maxSize ($maxSize), was ${result.size}")

        val ids = result.map { it.id }.toSet()
        assertEquals(result.size, ids.size, "No duplicate keys after mixed concurrent mutations")

        val sorted = result.zipWithNext { a, b -> a.score <= b.score }.all { it }
        assertTrue(sorted, "Elements must be in sorted order after mixed concurrent mutations")

        // Verify containsKey consistency
        for (item in result) {
            assertTrue(queue.containsKey(item.id), "containsKey inconsistency for key ${item.id}")
        }
    }

    // ── snapshot isolation ───────────────────────────────────────────────

    /**
     * Verifies that [ConcurrentPriorityQueue.items] provides snapshot isolation:
     * reading `items.value` while mutations occur on other coroutines must return
     * a consistent, non-corrupted list.
     */
    @Test
    fun `items snapshot is consistent under concurrent mutations`() = runTest {
        val maxSize = 50
        val queue = ConcurrentPriorityQueue<Item, Int>(
            maxSize = maxSize,
            comparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        // Pre-fill
        repeat(maxSize) { queue.add(Item(id = it, score = it)) }

        coroutineScope {
            // Mutators
            val mutators = (0 until 5).map { c ->
                launch(Dispatchers.Default) {
                    repeat(200) { i ->
                        val id = (c * 200 + i) % 200
                        queue.add(Item(id = id, score = id))
                        if (i % 3 == 0) queue.removeByKey(id)
                    }
                }
            }

            // Readers: take snapshots and validate each one
            val readers = (0 until 5).map {
                launch(Dispatchers.Default) {
                    repeat(100) {
                        val snapshot = queue.items.value

                        assertTrue(
                            snapshot.size <= maxSize,
                            "Snapshot size ${snapshot.size} exceeds maxSize $maxSize"
                        )

                        val snapshotIds = snapshot.map { it.id }.toSet()
                        assertEquals(
                            snapshot.size, snapshotIds.size,
                            "Snapshot has duplicate keys"
                        )

                        val snapshotSorted = snapshot.zipWithNext { a, b -> a.score <= b.score }.all { it }
                        assertTrue(snapshotSorted, "Snapshot is not sorted")

                        yield()
                    }
                }
            }

            (mutators + readers).joinAll()
        }
    }
}
