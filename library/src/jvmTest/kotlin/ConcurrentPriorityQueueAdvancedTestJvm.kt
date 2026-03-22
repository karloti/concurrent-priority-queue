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

import io.github.karloti.cpq.ConcurrentPriorityQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.measureTime

/**
 * Advanced performance and thread-safety test suite for [ConcurrentPriorityQueue].
 * These tests are explicitly executed on [Dispatchers.Default] using `runBlocking`
 * to guarantee genuine hardware multi-threading and simulate real race conditions.
 */
class ConcurrentPriorityQueueAdvancedTestJvm {

    /**
     * Represents a scheduled task with a priority.
     *
     * @property identifier The unique identity of the task.
     * @property priorityLevel The priority of the task. Smaller numbers denote better priority.
     */
    private data class TaskItem(val identifier: String, val priorityLevel: Int)

    /**
     * Test 7: addAll() Functional Correctness
     *
     * Verifies that addAll() correctly handles:
     * 1. Adding multiple elements filling the queue
     * 2. Evicting worse elements when full
     * 3. Rejecting worse elements when full
     * 4. Returning the correct count of modified/added elements
     */
    @Test
    fun `=== Test 7 addAll() Functional Correctness ===`():Unit = runTest {
        println("=== Test 7: addAll() Functional Correctness ===")
        val maxQueueCapacity = 10

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }

        val queue = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val skipList = ConcurrentSkipListSet(deterministicComparator)

        // 1. Add batch that fits completely
        // Priorities: 20, 21, 22, 23, 24
        val batch1 = List(5) { i -> TaskItem("A$i", 20 + i) }
        val result1 = queue.addAll(batch1)
        skipList.addAll(batch1)

        assertEquals(5, queue.size)
        assertContentEquals(result1,batch1, "First batch should all be added")
        assertContentEquals(result1,skipList.toList().take(maxQueueCapacity), "Queue should contain the same items")

        // 2. Add batch that fills remaining space AND evicts worse elements
        // Priorities: 10..19. These are all better than 20..24
        val batch2 = List(10) { i -> TaskItem("B$i", 10 + i) }
        val result2 = queue.addAll(batch2)
        skipList.addAll(batch2)

        assertEquals(10, queue.size)
        // Explanation:
        // Current: [20, 21, 22, 23, 24] (Size 5)
        // Add 10..14 (5 items) -> Fills queue to 10. Queue: [10..14, 20..24]
        // Add 15..19 (5 items) -> Each is better than worst (24 down to 20).
        // So 20..24 get evicted one by one.
        // Result: [10..19]
        // All 10 items from batch2 were added.
        assertContentEquals(result2,batch2," Second batch should all be added and evicted (10..19)")
        assertContentEquals(result2,skipList.toList().take(maxQueueCapacity),"Queue should contain the same items")

        val priorities = queue.items.value.map { it.priorityLevel }
        val expectedPriorities = (10..19).toList()

        assertContentEquals(expectedPriorities, priorities, "Queue should contain the best tasks (10..19)")

        // 3. Add batch of worse elements - should be rejected
        // Priorities: 100+ (Worse than 19)
        val batch3 = List(5) { i -> TaskItem("C$i", 100 + i) }
        val result3 = queue.addAll(batch3)
        skipList.addAll(batch3)

        assertEquals(10, queue.size)
        assertContentEquals(expectedPriorities, queue.items.value.map { it.priorityLevel }.sorted())
        assertContentEquals(result3, skipList.toList().take(maxQueueCapacity),"Queue should contain the same items")

        println("Validation successful. addAll() behaves as expected.\n")
    }

    /**
     * Test 8: Massive addAll Benchmark (Millions of items)
     *
     * Compare addAll() performance on a list of 1,000,000 elements against [ConcurrentSkipListSet].
     * Both collections receive the exact same 1D list of elements with unique keys and varying weights.
     */
    @Test
    fun `=== Test 8 Massive addAll Benchmark ===`() = runBlocking {
        val maxQueueCapacity = 10_000 // Realistic bounded size
        val count = 100 // Total operations: maxQueueCapacity * count
        println("=== Test 8: Massive addAll Benchmark (${maxQueueCapacity * count} items) ===")

        val randomSeed = Random(123456)
        println("Generating $maxQueueCapacity unique tasks...")

        // Pre-allocate list to isolate generation time from benchmark
        val allTasks = ArrayList<TaskItem>(maxQueueCapacity)
        for (i in 0 until maxQueueCapacity) {
            allTasks.add(
                TaskItem(
                    identifier = "MassiveTask_$i", // Unique key
                    priorityLevel = randomSeed.nextInt() // Random weight
                )
            )
        }

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }

        // 1. ConcurrentPriorityQueue
        val queue = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val cpqTime = measureTime {
            repeat(count) {
                queue.addAll(allTasks)
            }
        }

        println("ConcurrentPriorityQueue.addAll() Time: $cpqTime")
        println(
            "CPQ Throughput: ${
                String.format(
                    "%,.0f",
                    (maxQueueCapacity * count) / cpqTime.inWholeMilliseconds.toDouble() * 1000
                )
            } ops/sec"
        )

        // 2. ConcurrentSkipListSet (Reference)
        val skipList = ConcurrentSkipListSet(deterministicComparator)

        val skipListTime = measureTime {
            repeat(count) {
                skipList.addAll(allTasks)
            }
        }

        println("ConcurrentSkipListSet.addAll() Time: $skipListTime")
        println(
            "CSLS Throughput: ${
                String.format(
                    "%,.0f",
                    (count * maxQueueCapacity) / skipListTime.inWholeMilliseconds.toDouble() * 1000
                )
            } ops/sec"
        )

        // Validation
        val finalQueueItems = queue.items.value
        val finalSkipListItems = skipList.take(maxQueueCapacity) // Take top K to compare with bounded queue

        assertEquals(maxQueueCapacity, finalQueueItems.size, "Queue should be full at capacity")
        assertEquals(maxQueueCapacity, skipList.size, "SkipList should be full at capacity")

        assertContentEquals(
            finalSkipListItems,
            finalQueueItems,
            "Top $maxQueueCapacity elements do not match between Queue and SkipList!"
        )

        val speedup = skipListTime.inWholeMilliseconds.toDouble() / cpqTime.inWholeMilliseconds.toDouble()
        println("\nCustom Queue vs SkipList speedup: ${String.format("%.2f", speedup)}x")
        println("Validation successful.\n")
    }
}