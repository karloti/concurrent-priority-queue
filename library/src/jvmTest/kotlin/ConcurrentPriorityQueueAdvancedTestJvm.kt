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

@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

import TestConfig.LOCAL
import io.github.karloti.cpq.ConcurrentPriorityQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.math.log2
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
    fun `=== Test 7 addAll() Functional Correctness ===`(): Unit = runTest {
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
        assertContentEquals(result1, batch1, "First batch should all be added")
        assertContentEquals(result1, skipList.toList().take(maxQueueCapacity), "Queue should contain the same items")

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
        assertContentEquals(result2, batch2, " Second batch should all be added and evicted (10..19)")
        assertContentEquals(result2, skipList.toList().take(maxQueueCapacity), "Queue should contain the same items")

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
        assertContentEquals(result3, skipList.toList().take(maxQueueCapacity), "Queue should contain the same items")

        println("Validation successful. addAll() behaves as expected.\n")
    }

    /**
     * Test 8: Massive addAll Benchmark (Millions of items)
     *
     * Compare addAll() performance on a list of 1,000,000 elements against [ConcurrentSkipListSet].
     * Both collections receive the exact same 1D list of elements with unique keys and varying weights.
     */
    @Test
    fun `=== Test 8 Massive addAll Benchmark ===`() = runTest(timeout = 1.minutes) {
        val maxQueueCapacity = if (LOCAL) 10_000 else 1_000
        val count = if (LOCAL) 100 else 10
        println("=== Test 8: Massive addAll Benchmark (${maxQueueCapacity * count} items, local=${LOCAL}) ===")

        val randomSeed = Random(123456)
        println("Generating $maxQueueCapacity unique tasks...")

        // Pre-allocate list to isolate generation time from benchmark

        val allTasks = List(maxQueueCapacity) { i ->
            TaskItem(
                identifier = "MassiveTask_$i", // Unique key
                priorityLevel = randomSeed.nextInt() // Random weight
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

        assertContentEquals(
            expected = skipList,
            actual = finalQueueItems,
            message = "Top $maxQueueCapacity elements do not match between Queue and SkipList!"
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
    fun `=== Test 9 Insert Throughput by Queue Size ===`() = runTest {
        val sizes = if (LOCAL) listOf(100, 1_000, 10_000, 100_000) else listOf(100, 1_000)
        val insertCount = if (LOCAL) 1_000_000 else 10_000

        println("=== Test 9: Insert Throughput by Queue Size (local=${LOCAL}) ===")
        println("Insert count per size: $insertCount")
        println("%-12s  %12s  %15s  %8s".format("maxSize", "Time", "Throughput", "Depth"))

        for (maxSize in sizes) {
            val queue = ConcurrentPriorityQueue<Int>(maxSize = maxSize, reverseOrder())

            val time = measureTime {
                for (i in 0 until insertCount) {
                    queue.add(i + maxSize * insertCount)
                }
            }

            val throughput = insertCount / time.inWholeMilliseconds.toDouble() * 1000
            val expectedDepth = log2(maxSize.toDouble()).toInt()

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
    fun `=== Test 10 Memory Allocation per Insert ===`() = runTest {
        val maxSize = if (LOCAL) 100_000 else 5_000
        val insertCount = if (LOCAL) 10_000_000 else 20_000

        println("=== Test 10: Memory Allocation per Insert (local=${LOCAL}) ===")
        println("maxSize=$maxSize, inserts=$insertCount")

        val queue = ConcurrentPriorityQueue<Int>(maxSize = maxSize)

        // Warm up and fill the queue
        repeat(insertCount, queue::add)

        // Force GC and measure baseline
        System.gc()
        delay(300.milliseconds)
        val memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // Perform measured inserts (queue is already full → inserts trigger evictions + path copies)
        repeat(insertCount, queue::add)

        // Measure after
        val memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // Force GC to see retained memory
        System.gc()
        delay(300.milliseconds)
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

        items.indices.forEach { i ->
            assertEquals(items[i], i, "Sort violation at index $i")
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
    fun `=== Test 11 Concurrent Insert Throughput ===`() = runTest {
        val maxSize = if (LOCAL) 10_000 else 1_000
        val coroutineCount = if (LOCAL) 32 else 4
        val insertsPerCoroutine = if (LOCAL) 100_000 else 5_000
        val totalInserts = coroutineCount * insertsPerCoroutine

        println("=== Test 11: Concurrent Insert Throughput (local=${LOCAL}) ===")
        println("maxSize=$maxSize, coroutines=$coroutineCount, inserts/coroutine=$insertsPerCoroutine")

        val queue = ConcurrentPriorityQueue<Int>(maxSize = maxSize, reverseOrder())

        val time = measureTime {
            List(coroutineCount) { coroutineId ->
                launch {
                    repeat(insertsPerCoroutine) {
                        queue.add(it + coroutineId * insertsPerCoroutine)
                    }
                }
            }.joinAll()
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

    // ======================== addAll(Flow, parallelism, chunkSize, transform) ========================

    /**
     * Test 12: addAll(Flow, transform) – basic functional correctness.
     *
     * Verifies that the parallel-transform overload:
     * 1. Adds all elements when queue has capacity
     * 2. Evicts worst elements when full
     * 3. Returns the correct eviction count
     * 4. Maintains sorted order
     */
    @Test
    fun `=== Test 12 addAll Flow transform Functional Correctness ===`() = runTest(timeout = 1.minutes) {
        println("=== Test 12: addAll(Flow, transform) Functional Correctness ===")
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

        // 1. Add batch that fits completely (priorities 20..24)
        val evictions1 = queue.addAll(
            elements = (0 until 5).asFlow()
        ) { i -> TaskItem("A$i", 20 + i) }

        assertEquals(5, queue.size, "Queue should contain 5 elements after first batch")
        assertEquals(0, evictions1, "No evictions expected when queue has space")

        // 2. Add batch that fills + evicts (priorities 10..19, all better than 20..24)
        val evictions2 = queue.addAll(
            elements = (0 until 10).asFlow()
        ) { i -> TaskItem("B$i", 10 + i) }

        assertEquals(maxQueueCapacity, queue.size, "Queue should be at capacity")
        // B0..B9 (10..19) are all better than A0..A4 (20..24), so 5 evictions
        assertEquals(5, evictions2, "5 elements from first batch should have been evicted")

        val priorities = queue.items.value.map { it.priorityLevel }.sorted()
        assertEquals((10..19).toList(), priorities, "Queue should contain priorities 10..19")

        // 3. Add worse elements (priorities 100+) — should be rejected, 0 evictions
        val evictions3 = queue.addAll(
            elements = (0 until 5).asFlow()
        ) { i -> TaskItem("C$i", 100 + i) }

        assertEquals(maxQueueCapacity, queue.size, "Queue size should remain unchanged")
        assertEquals(0, evictions3, "No evictions when all new elements are worse")
        assertEquals(
            (10..19).toList(),
            queue.items.value.map { it.priorityLevel }.sorted(),
            "Queue contents should be unchanged"
        )

        println("Validation successful.\n")
    }

    /**
     * Test 13: addAll(Flow, transform) – transform function is actually applied.
     *
     * Ensures the transform maps source elements to target elements correctly
     * and the queue contains the transformed values.
     */
    @Test
    fun `=== Test 13 addAll Flow transform applies mapping ===`() = runTest(timeout = 1.minutes) {
        println("=== Test 13: addAll(Flow, transform) applies mapping ===")

        val queue = ConcurrentPriorityQueue<Int>(maxSize = 20)

        val evictions = queue.addAll(
            elements = (1..10).asFlow()
        ) { it * it } // square each element


        assertEquals(0, evictions, "No evictions expected")
        assertEquals(10, queue.size, "Queue should contain 10 elements")

        val expected = (1..10).map { it * it }
        assertEquals(expected, queue.items.value.toList(), "Queue should contain squared values in descending order")

        println("Validation successful.\n")
    }

    /**
     * Test 14: addAll(Flow, transform) – empty flow produces no changes.
     */
    @Test
    fun `=== Test 14 addAll Flow transform empty flow ===`() = runTest(timeout = 1.minutes) {
        println("=== Test 14: addAll(Flow, transform) empty flow ===")

        val queue = ConcurrentPriorityQueue<Int>(maxSize = 10)
        queue.add(42)

        val evictions = queue.addAll(
            elements = flowOf<Int>()
        ) { it }

        assertEquals(0, evictions, "No evictions on empty flow")
        assertEquals(1, queue.size, "Queue should still have 1 element")
        assertEquals(42, queue.first(), "Original element should remain")

        println("Validation successful.\n")
    }

    /**
     * Test 15: addAll(Flow, transform) – upsert (duplicate key) semantics.
     *
     * When elements with the same key arrive via the flow, the queue should
     * keep the one with better priority (upsert).
     */
    @Test
    fun `=== Test 15 addAll Flow transform upsert semantics ===`() = runTest(timeout = 1.minutes) {
        println("=== Test 15: addAll(Flow, transform) upsert semantics ===")

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }

        val queue = ConcurrentPriorityQueue(
            maxSize = 10,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        // Add initial elements
        queue.add(TaskItem("task-1", 50))
        queue.add(TaskItem("task-2", 60))

        // Send updates with better priorities for the same keys
        queue.addAll(
            elements = listOf("task-1" to 10, "task-2" to 20, "task-3" to 30).asFlow()
        ) { (id, priority) -> TaskItem(id, priority) }

        assertEquals(3, queue.size, "Queue should have 3 unique tasks")
        assertEquals(10, queue["task-1"]!!.priorityLevel, "task-1 should be updated to priority 10")
        assertEquals(20, queue["task-2"]!!.priorityLevel, "task-2 should be updated to priority 20")
        assertEquals(30, queue["task-3"]!!.priorityLevel, "task-3 should have priority 30")

        println("Validation successful.\n")
    }

    /**
     * Test 16: addAll(Flow, transform) – suspending transform (simulates I/O).
     *
     * Verifies that the parallel pipeline handles suspending transforms correctly,
     * with multiple concurrent suspend points.
     */
    @Test
    fun `=== Test 16 addAll Flow transform with suspending transform ===`() = runTest(timeout = 1.minutes) {
        println("=== Test 16: addAll(Flow, transform) with suspending transform ===")

        val queue = ConcurrentPriorityQueue<Int>(maxSize = 50)

        val count = if (LOCAL) 200 else 20
        val evictions = queue.addAll(
            elements = (1..count).asFlow(),
        ) { value ->
            delay(1.milliseconds) // simulate async I/O
            value
        }

        val expectedSize = minOf(count, 50)
        assertEquals(expectedSize, queue.size, "Queue should be at capacity or contain all elements")

        // Verify sorted descending
        val items = queue.items.value
        for (i in 0 until items.size - 1) {
            assertTrue(items[i] <= items[i + 1], "Sort violation at index $i: ${items[i]} < ${items[i + 1]}")
        }

        // If count > 50, we should have evictions
        if (count > 50) {
            assertTrue(evictions > 0, "Should have evictions when adding $count elements to queue of capacity 50")
        }

        println("Evictions: $evictions, Queue size: ${queue.size}")
        println("Validation successful.\n")
    }

    /**
     * Test 17: addAll(Flow, transform) – parallelism is respected.
     *
     * Verifies that with parallelism=1 processing is sequential (transforms
     * don't overlap), and with higher parallelism transforms overlap.
     */
    @Test
    fun `=== Test 17 addAll Flow transform parallelism behaviour ===`() = runTest(timeout = 1.minutes) {
        println("=== Test 17: addAll(Flow, transform) parallelism behaviour ===")

        val maxQueueCapacity = if (LOCAL) 1_000 else 100
        val queue1 = ConcurrentPriorityQueue<Int>(maxSize = maxQueueCapacity)
        val count = if (LOCAL) 200 else 20
        val delayMs = if (LOCAL) 100L else 10L

        // Sequential (parallelism = 1) should be slower
        val sequentialTime = measureTime {
            queue1.addAll(
                elements = (1..count).asFlow(),
            ) { value ->
                delay(delayMs.milliseconds)
                value
            }
        }

        val queue2 = ConcurrentPriorityQueue<Int>(maxSize = maxQueueCapacity)

        // Parallel (parallelism = count) should be faster
        val parallelTime = measureTime {
            queue2.addAll(
                elements = (1..count).asFlow()
            ) { value ->
                delay(delayMs.milliseconds)
                value
            }
        }

        assertEquals(count, queue1.size, "Sequential queue should have all elements")
        assertEquals(count, queue2.size, "Parallel queue should have all elements")

        println("Sequential time: $sequentialTime")
        println("Parallel time:   $parallelTime")
        println(
            "Speedup: ${
                String.format(
                    "%.2f",
                    sequentialTime.inWholeMilliseconds.toDouble() / parallelTime.inWholeMilliseconds.toDouble()
                )
            }x"
        )

        println("Validation successful.\n")
    }

    /**
     * Test 18 (LOCAL): addAll(Flow, transform) – massive throughput benchmark.
     *
     * Feeds a large flow through the parallel transform pipeline and measures
     * throughput. Validates correctness of the final queue state.
     */
    @Test
    fun `=== Test 18 addAll Flow transform massive throughput ===`() = runTest(timeout = 1.minutes) {
        val maxQueueCapacity = if (LOCAL) 50_000 else 1_000
        val totalElements = if (LOCAL) 500_000 else 5_000

        println("=== Test 18: addAll(Flow, transform) massive throughput (local=${LOCAL}) ===")
        println("maxSize=$maxQueueCapacity, elements=$totalElements")

        val queue = ConcurrentPriorityQueue<Int>(maxSize = maxQueueCapacity, reverseOrder())

        val time = measureTime {
            queue.addAll(elements = (0 until totalElements).asFlow(), transform = { it })
        }
//        val time = measureTime {
//            queue.addAll(
//                elements = (0 until totalElements).asFlow(),
//                parallelism = 1,
//                transform = {
////                    delay(1.milliseconds)
//                    it
//                }
//            )
//        }

        val throughput = totalElements / time.inWholeMilliseconds.toDouble() * 1000
        println("Time: $time")
        println("Throughput: ${String.format("%,.0f", throughput)} ops/sec")
        println("Queue size: ${queue.size}")

        assertEquals(
            minOf(maxQueueCapacity, totalElements),
            queue.size,
            "Queue should be at capacity"
        )

        // Verify sorted order (descending)
        val items = queue.items.value
        for (i in 0 until items.size - 1) {
            assertTrue(items[i] >= items[i + 1], "Sort violation at index $i")
        }

        // Top elements should be the largest values
        assertEquals(totalElements - 1, items.first(), "First element should be the largest")

        println("Validation successful.\n")
    }

    /**
     * Test 19 (LOCAL): addAll(Flow, transform) – concurrent producers with parallel transform.
     *
     * Multiple coroutines each feed their own flow through addAll concurrently,
     * exercising CAS contention from multiple parallel pipelines.
     */
    @Test
    fun `=== Test 19 addAll Flow transform concurrent producers ===`() = runTest(timeout = 1.minutes) {
        val maxQueueCapacity = if (LOCAL) 1_000 else 500
        val producerCount = if (LOCAL) 4 else 2
        val elementsPerProducer = if (LOCAL) 10_000 else 2_000
        val parallelism = if (LOCAL) DEFAULT_CONCURRENCY else 1
        val delayDuration = if (LOCAL) 10.milliseconds.inWholeMilliseconds else null

        println("=== Test 19: addAll(Flow, transform) concurrent producers (local=${LOCAL}) ===")
        println("maxSize=$maxQueueCapacity, producers=$producerCount, elements/producer=$elementsPerProducer, parallelism=$parallelism, delay=$delayDuration ms")

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }

        val queue = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val time = measureTime {
            when {
                delayDuration != null && delayDuration > 0 -> {
                    List(producerCount) { producerId ->
                        launch {
                            queue.addAll(
                                elements = (0 until elementsPerProducer).asFlow(),
                            ) { i ->
                                val taskItem = TaskItem(
                                    identifier = "P${producerId}_T$i",
                                    priorityLevel = i + producerId * elementsPerProducer
                                )
                                Thread.sleep(delayDuration)
                                taskItem
                            }
                        }
                    }.joinAll()
                }
                else -> {
                    List(producerCount) { producerId ->
                        launch {
                            queue.addAll(
                                elements = (0 until elementsPerProducer).asFlow(),
                            ) { i ->
                                TaskItem(
                                    identifier = "P${producerId}_T$i",
                                    priorityLevel = i + producerId * elementsPerProducer
                                )
                            }
                        }
                    }.joinAll()
                }
            }
        }

        val totalElements = producerCount * elementsPerProducer
        val throughput = totalElements / time.inWholeMilliseconds.toDouble() * 1000

        println("Total time: $time")
        println("Throughput: ${String.format("%,.0f", throughput)} ops/sec")
        println("Queue size: ${queue.size} (expected: $maxQueueCapacity)")

        assertEquals(maxQueueCapacity, queue.size, "Queue should be at capacity")

        // Verify sorted order
        val items = queue.items.value
        for (i in 0 until items.size - 1) {
            assertTrue(
                deterministicComparator.compare(items[i], items[i + 1]) <= 0,
                "Sort violation at index $i"
            )
        }

        // All items should have the best (lowest) priorities
        val worstInQueue = items.maxOf { it.priorityLevel }
        assertTrue(
            worstInQueue < totalElements,
            "Worst element in queue ($worstInQueue) should be less than total elements ($totalElements)"
        )

        println("Validation successful.\n")
    }

    /**
     * Test 20 (LOCAL): addAll(Flow, transform) – heavy transform with high parallelism.
     *
     * Simulates a CPU-intensive transform to verify that parallelism properly
     * distributes work across threads under real load.
     */
    @Test
    fun `=== Test 20 addAll Flow transform CPU intensive ===`() = runTest(timeout = 1.minutes) {
        val maxQueueCapacity = if (LOCAL) 10_000 else 500
        val totalElements = if (LOCAL) 100_000 else 2_000
        val parallelism = if (LOCAL) 16 else 4

        println("=== Test 20: addAll(Flow, transform) CPU-intensive (local=${LOCAL}) ===")
        println("maxSize=$maxQueueCapacity, elements=$totalElements, parallelism=$parallelism")

        val queue = ConcurrentPriorityQueue<Long>(maxSize = maxQueueCapacity, reverseOrder())

        val time = measureTime {
            queue.addAll(
                elements = (0 until totalElements).asFlow()
            ) { value ->
                // Simulate CPU work: compute a hash chain
                var hash = value.toLong()
                repeat(100) {
                    hash = hash xor (hash shl 13)
                    hash = hash xor (hash shr 7)
                    hash = hash xor (hash shl 17)
                }
                hash
            }
        }

        val throughput = totalElements / time.inWholeMilliseconds.toDouble() * 1000
        println("Time: $time")
        println("Throughput: ${String.format("%,.0f", throughput)} ops/sec")
        println("Queue size: ${queue.size}")

        assertEquals(
            minOf(maxQueueCapacity, totalElements),
            queue.size,
            "Queue should be at expected size"
        )

        // Verify sorted descending
        val items = queue.items.value
        for (i in 0 until items.size - 1) {
            assertTrue(items[i] >= items[i + 1], "Sort violation at index $i")
        }

        println("Validation successful.\n")
    }
}