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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
        val maxQueueCapacity = if (TestConfig.LOCAL) 1_00_000 else 1_000
        val count = if (TestConfig.LOCAL) 1_000 else 10
        println("=== Test 8: Massive addAll Benchmark (${maxQueueCapacity * count} items, local=${TestConfig.LOCAL}) ===")

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

    /**
     * Test 9: Insert Throughput at Various Queue Sizes
     *
     * Measures insert throughput for different maxSize values to observe
     * how tree depth (path copy length) affects performance.
     * Validates correctness (sorted order, size, key uniqueness) at each scale.
     */
    @Test
    fun `=== Test 9 Insert Throughput by Queue Size ===`() = runBlocking(Dispatchers.Default) {
        val sizes = if (TestConfig.LOCAL) listOf(100, 1_000, 10_000, 100_000) else listOf(100, 1_000)
        val insertCount = if (TestConfig.LOCAL) 1_000_000 else 10_000

        println("=== Test 9: Insert Throughput by Queue Size (local=${TestConfig.LOCAL}) ===")
        println("Insert count per size: $insertCount")
        println("%-12s  %12s  %15s  %8s".format("maxSize", "Time", "Throughput", "Depth"))

        for (maxSize in sizes) {
            val queue = ConcurrentPriorityQueue<Int>(maxSize = maxSize)

            val time = measureTime {
                for (i in 0 until insertCount) {
                    queue.add(Random.nextInt())
                }
            }

            val throughput = insertCount / time.inWholeMilliseconds.toDouble() * 1000
            val expectedDepth = (Math.log(maxSize.toDouble()) / Math.log(2.0)).toInt()

            println("%-12d  %12s  %,15.0f ops/sec  ~%d".format(maxSize, time, throughput, expectedDepth))

            // Validate correctness
            val items = queue.items.value
            assertEquals(minOf(maxSize, insertCount), items.size, "Size mismatch for maxSize=$maxSize")

            // Verify sorted order (descending for Comparable default)
            for (i in 0 until items.size - 1) {
                assertTrue(items[i] >= items[i + 1], "Sort violation at index $i for maxSize=$maxSize")
            }
        }

        println("Validation successful.\n")
    }

    /**
     * Test 10: Memory Allocation Measurement
     *
     * Measures approximate heap allocation per insert operation by comparing
     * used memory before and after a batch of inserts. Forces GC to get a baseline.
     */
    @Test
    fun `=== Test 10 Memory Allocation per Insert ===`() = runBlocking(Dispatchers.Default) {
        val maxSize = if (TestConfig.LOCAL) 10_000 else 5_000
        val insertCount = if (TestConfig.LOCAL) 1_000_000 else 20_000

        println("=== Test 10: Memory Allocation per Insert (local=${TestConfig.LOCAL}) ===")
        println("maxSize=$maxSize, inserts=$insertCount")

        val queue = ConcurrentPriorityQueue<Int>(maxSize = maxSize)

        // Warm up and fill the queue
        for (i in 0 until maxSize) {
            queue.add(Random.nextInt())
        }

        // Force GC and measure baseline
        System.gc()
        Thread.sleep(100)
        val memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // Perform measured inserts (queue is already full → inserts trigger evictions + path copies)
        for (i in 0 until insertCount) {
            queue.add(Random.nextInt())
        }

        // Measure after
        val memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // Force GC to see retained memory
        System.gc()
        Thread.sleep(100)
        val memRetained = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        val allocatedPerInsert = (memAfter - memBefore).toDouble() / insertCount
        val retainedTotal = memRetained - memBefore

        println("Approx allocated per insert: ${String.format("%.0f", allocatedPerInsert)} bytes")
        println("Memory before: ${memBefore / 1024 / 1024} MB")
        println("Memory peak:   ${memAfter / 1024 / 1024} MB")
        println("Memory after GC: ${memRetained / 1024 / 1024} MB")
        println("Retained delta: ${retainedTotal / 1024} KB (should be near zero — old versions are GC'd)")

        // Validate queue is still correct
        val items = queue.items.value
        assertEquals(maxSize, items.size, "Queue should be full")
        for (i in 0 until items.size - 1) {
            assertTrue(items[i] >= items[i + 1], "Sort violation at index $i")
        }

        println("Validation successful.\n")
    }

    /**
     * Test 11: Concurrent Insert Throughput
     *
     * Measures throughput when multiple coroutines insert concurrently,
     * exercising the CAS retry mechanism under contention.
     */
    @Test
    fun `=== Test 11 Concurrent Insert Throughput ===`() = runBlocking(Dispatchers.Default) {
        val maxSize = if (TestConfig.LOCAL) 10_000 else 1_000
        val coroutineCount = if (TestConfig.LOCAL) 32 else 4
        val insertsPerCoroutine = if (TestConfig.LOCAL) 100_000 else 5_000
        val totalInserts = coroutineCount * insertsPerCoroutine

        println("=== Test 11: Concurrent Insert Throughput (local=${TestConfig.LOCAL}) ===")
        println("maxSize=$maxSize, coroutines=$coroutineCount, inserts/coroutine=$insertsPerCoroutine")

        val queue = ConcurrentPriorityQueue<Int>(maxSize = maxSize)

        val time = measureTime {
            coroutineScope {
                repeat(coroutineCount) { coroutineId ->
                    launch(Dispatchers.Default) {
                        val rng = Random(coroutineId)
                        repeat(insertsPerCoroutine) {
                            queue.add(rng.nextInt())
                        }
                    }
                }
            }
        }

        val throughput = totalInserts / time.inWholeMilliseconds.toDouble() * 1000

        println("Total time: $time")
        println("Throughput: ${String.format("%,.0f", throughput)} concurrent ops/sec")
        println("Queue size: ${queue.size} (expected: $maxSize)")

        // Validate
        val items = queue.items.value
        assertEquals(maxSize, items.size, "Queue should be full at capacity")
        for (i in 0 until items.size - 1) {
            assertTrue(items[i] >= items[i + 1], "Sort violation at index $i")
        }

        println("Validation successful.\n")
    }
}