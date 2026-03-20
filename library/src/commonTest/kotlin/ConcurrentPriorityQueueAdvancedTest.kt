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

@file:OptIn(ExperimentalCoroutinesApi::class)

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class ConcurrentPriorityQueueAdvancedTest {

    private data class Task(val id: String, val priority: Int)

    /**
     * Test 1: Verifies state consistency when updating elements with equal priorities.
     * When two elements have equal priority, updating one should not corrupt the other.
     */
    @Test
    fun `test state corruption on equal priority updates`() = runTest {
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = 5,
            comparator = compareByDescending { it.priority },
            uniqueKeySelector = { it.id },
        )

        // Add two different elements with EQUAL priority
        queue.add(Task("A", 10))
        queue.add(Task("B", 10))

        // Now update "B" priority to higher value
        queue.add(Task("B", 20))

        val items = queue.items.value

        // We expect both "A" and "B" in the list.
        // If there's a bug, "A" will be missing because binarySearch found and deleted it instead of old "B".
        val ids = items.map { it.id }
        assertTrue(ids.contains("A"), "CRITICAL BUG: Element 'A' was incorrectly deleted!")
        assertTrue(ids.contains("B"), "Element 'B' is missing!")
        assertEquals(2, items.size)
    }

    /**
     * Test 2: Stress test for memory and performance with large capacity.
     * Measures time taken for O(N) mutations inside CAS loop.
     */
    @Test
    fun `test performance and memory footprint with large capacity`() = runTest {
        val largeCapacity = 10_000
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = largeCapacity,
            comparator = compareBy { it.priority },
            uniqueKeySelector = { it.id }
        )

        val concurrencyLevel = 100
        val insertsPerThread = 100

        // JVM warm-up for more accurate metrics
        val warmupQueue = ConcurrentPriorityQueue<Task, String>(100, compareBy { it.priority }) { it.id }
        warmupQueue.add(Task("W", 1))

        val executionTime = measureTime {
            val jobs = (1..concurrencyLevel).map { threadId ->
                launch(Dispatchers.Default) {
                    for (i in 1..insertsPerThread) {
                        // Simulate random priorities and keys to force
                        // displacement in the middle of the array (O(N) shift)
                        val priority = (1..100_000).random()
                        queue.add(Task("T-$threadId-$i", priority))
                    }
                }
            }
            jobs.joinAll()
        }

        val currentItems = queue.items.value
        val resultSize = currentItems.size
        println("Completed ${concurrencyLevel * insertsPerThread} concurrent operations.")
        println("Execution time: $executionTime")
        println("Final elements in queue: $resultSize")

        // Validation
        assertTrue(resultSize <= largeCapacity)
        val isSorted = currentItems.zipWithNext { a, b -> a.priority <= b.priority }.all { it }
        assertTrue(isSorted, "Queue is not properly sorted after massive operations!")

        // Consistency check (Map vs List)
        // Since Map is not public, we check indirectly:
        // If we try to add same elements with worse priority, size should not change.
        val snapshot = currentItems
        snapshot.take(10).forEach { queue.add(it.copy(priority = it.priority + 1000)) }
        assertEquals(snapshot, currentItems, "Desynchronization between internal Map and List!")
    }

    /**
     * Test 3: Adding elements in completely reverse order (worst-case scenario for insertion).
     */
    @Test
    fun `test worst case sequential insertions`() = runTest {
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = 100,
            comparator = compareBy { it.priority }, // Най-малкото е с най-висок приоритет
            uniqueKeySelector = { it.id }
        )

        // Add from smallest to largest (insertion always at end - fastest)
        val timeFast = measureTime {
            for (i in 1..1000) queue.add(Task("F$i", i))
        }

        val queueReversed = ConcurrentPriorityQueue<Task, String>(
            maxSize = 100,
            comparator = compareBy { it.priority },
            uniqueKeySelector = { it.id }
        )

        // Add from largest to smallest (insertion always at index 0 - requires O(N) shift each time)
        val timeSlow = measureTime {
            for (i in 1000 downTo 1) queueReversed.add(Task("S$i", i))
        }

        println("Best-case insertion time: $timeFast")
        println("Worst-case insertion time: $timeSlow")
        assertEquals(100, queueReversed.items.value.size)
        assertEquals(1, queueReversed.items.value.first().priority)
    }

    @Test
    fun `test DETERMINISTIC corruption on equal priority updates`() = runTest {
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = 10,
            comparator = compareBy { it.priority }, // По-малкото число е с по-висок приоритет
            uniqueKeySelector = { it.id }
        )

        // 1. Create 3 tasks with ABSOLUTELY equal priority
        queue.add(Task("A", 50))
        queue.add(Task("B", 50))
        queue.add(Task("C", 50))

        // At this moment internal list is: [C(50), B(50), A(50)]
        // Because with equal priority binarySearch returns index 0 and shifts others.

        // 2. Update "A" with worse priority (e.g. 100)
        // Logic should remove A(50) and add A(100).
        queue.add(Task("A", 100))

        val items = queue.items.value
        val idsInList = items.map { it.id }

        println("Current list state after update: $items")

        // 3. Validation - this test WILL FAIL if there's a bug
        assertTrue(
            idsInList.contains("B"),
            "CRITICAL BUG: Element 'B' was incorrectly deleted instead of 'A', because binarySearch got confused!"
        )

        assertEquals(
            1,
            idsInList.count { it == "A" },
            "CRITICAL BUG: Element 'A' appears more than once in the list! (Old A(50) was not deleted)"
        )

        assertEquals(3, items.size, "List size is incorrect!")
    }

    @Test
    fun `test DETERMINISTIC corruption on BETTER priority update`() = runTest {
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = 10,
            comparator = compareBy { it.priority },
            uniqueKeySelector = { it.id }
        )

        // 1. Add 3 tasks with equal priority
        queue.add(Task("A", 50))
        queue.add(Task("B", 50))
        queue.add(Task("C", 50))

        // 2. Update "A" with BETTER priority (e.g. 10).
        // This skips fast-path check and forces removal of old element.
        queue.add(Task("A", 10))

        val items = queue.items.value
        val idsInList = items.map { it.id }

        println("Current state: $items")

        // 3. Validation
        assertTrue(
            idsInList.contains("B"),
            "CRITICAL BUG: 'B' was deleted instead of 'A', because binarySearch found its index!"
        )
        assertTrue(
            idsInList.contains("C"),
            "CRITICAL BUG: 'C' is missing!"
        )
        assertEquals(
            1,
            idsInList.count { it == "A" },
            "CRITICAL BUG: 'A' is duplicated in the list (old A(50) was not deleted)!"
        )
    }

    @Test
    fun `test memory leak and desynchronization when exceeding maxSize`() = runTest {
        // Initialize queue with very small capacity (maxSize = 2)
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = 2,
            comparator = compareBy { it.priority }, // Smaller number is better priority
            uniqueKeySelector = { it.id }
        )

        // 1. Fill queue to capacity
        queue.add(Task("A", 10))
        queue.add(Task("B", 20))

        // At this moment: List = [A(10), B(20)], Map = {A, B}

        // 2. Add new, better element that will push out the weakest ("B") from list
        queue.add(Task("C", 5))

        // Get direct access to internal state
        val state = queue.items.value

        println("List state: $state")
        println("Queue size: ${queue.size}")

        // 3. Check list (This will pass successfully)
        assertEquals(2, state.size, "List must contain exactly 2 elements.")
        assertTrue(state.none { it.id == "B" }, "Element 'B' must be deleted from list.")
        assertEquals("C", state[0].id)
        assertEquals("A", state[1].id)

        // 4. Check that there's no memory leak - "B" should not exist in queue
        assertEquals(
            false,
            queue.containsKey("B"),
            "CRITICAL BUG (Memory Leak): 'B' is removed from list, but still kept in Map!"
        )

        assertEquals(
            2,
            queue.size,
            "CRITICAL BUG: Queue size (${queue.size}) is larger than expected (${state.size})!"
        )
    }
}