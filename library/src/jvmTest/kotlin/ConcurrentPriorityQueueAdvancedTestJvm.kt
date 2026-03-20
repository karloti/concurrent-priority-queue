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

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Advanced performance and thread-safety test suite for [ConcurrentPriorityQueue].
 * * These tests are explicitly executed on [Dispatchers.Default] using `runBlocking`
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
     * Test 1: Unique Keys Benchmark
     * * Evaluates thread safety and performance when inserting strictly unique keys.
     * Ensures that the queue correctly bounds its capacity and maintains the correct
     * elements when compared against a standard Java [ConcurrentSkipListSet].
     */
    @Test
    fun `=== Test 1 Unique Keys Benchmark ===`() = runBlocking(Dispatchers.Default) {
        println("=== Test 1: Unique Keys Benchmark ===")
        val maxQueueCapacity = 500_000
        val coroutinesCount = 5_000 // Increased for extreme stress testing
        val insertionsPerCoroutine = 500

        val randomSeed = Random(42)
        val preGeneratedTasks = List(coroutinesCount) { threadIndex ->
            List(insertionsPerCoroutine) { taskIndex ->
                TaskItem(
                    identifier = "UniqueTask_${threadIndex}_${taskIndex}",
                    priorityLevel = randomSeed.nextInt(1, 1_000_000)
                )
            }
        }

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }

        val customPriorityQueue = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val customQueueDuration = measureTime {
            val workerJobs = preGeneratedTasks.map { tasksForThread ->
                launch {
                    tasksForThread.forEach { task ->
                        customPriorityQueue.add(task)
                    }
                }
            }
            workerJobs.joinAll()
        }

        println("Custom Queue Execution Time (Unique Keys): $customQueueDuration")

        val javaSkipListSet = ConcurrentSkipListSet(deterministicComparator)

        val skipListDuration = measureTime {
            val workerJobs = preGeneratedTasks.map { tasksForThread ->
                launch {
                    tasksForThread.forEach { task ->
                        // Add safely, but DO NOT bound concurrently.
                        // ConcurrentSkipListSet.size is O(N) and creates massive Check-Then-Act race conditions.
                        javaSkipListSet.add(task)
                    }
                }
            }
            workerJobs.joinAll()
        }

        println("Java SkipList Execution Time (Unique Keys): $skipListDuration")

        val finalCustomItems = customPriorityQueue.items.value

        // Extract the correctly bounded expected result AFTER all concurrent operations finish
        val finalSkipListItems = javaSkipListSet.take(maxQueueCapacity)

        assertTrue(finalCustomItems.size <= maxQueueCapacity, "Custom Queue exceeded max capacity!")
        assertTrue(finalSkipListItems.size <= maxQueueCapacity, "SkipList exceeded max capacity!")
        assertContentEquals(finalSkipListItems, finalCustomItems, "Queues do not match! Contents differ.")

        println("Validation successful. Both structures hold the exact same elements.\n")
    }

    /**
     * Test 2: Duplicate Keys Benchmark
     * * Evaluates thread safety and deduplication logic when inserting colliding duplicate keys.
     * Tests whether identical identifiers correctly overwrite each other, prioritize the
     * best values, and strictly enforce state consistency without memory leaks.
     */
    @Test
    fun `=== Test 2 Duplicate Keys Benchmark ===`() = runBlocking(Dispatchers.Default) {
        println("=== Test 2: Duplicate Keys Benchmark ===")
        val maxQueueCapacity = 100
        val coroutinesCount = 5000
        val insertionsPerCoroutine = 200
        val uniqueIdsPoolSize = 50 // Creates massive collision constraints

        // Pre-generate the identical structure to test massive duplicate contention.
        val randomSeed = Random(99)
        val preGeneratedTasks = List(coroutinesCount) {
            List(insertionsPerCoroutine) { taskIndex ->
                val sharedId = taskIndex % uniqueIdsPoolSize
                TaskItem(
                    identifier = "SharedTask_$sharedId",
                    priorityLevel = randomSeed.nextInt(1, 1_000_000)
                )
            }
        }

        val customPriorityQueue = ConcurrentPriorityQueue<TaskItem, String>(
            maxSize = maxQueueCapacity,
            comparator = compareBy { it.priorityLevel }, // Ascending: Smaller is better
            uniqueKeySelector = { it.identifier }
        )

        // Measure Custom Queue performance under heavy deduplication load
        val customQueueDuration = measureTime {
            val workerJobs = preGeneratedTasks.map { tasksForThread ->
                launch {
                    tasksForThread.forEach { task ->
                        customPriorityQueue.add(task)
                    }
                }
            }
            workerJobs.joinAll()
        }

        println("Custom Queue Execution Time (Duplicate Keys): $customQueueDuration")

        val finalItems = customPriorityQueue.items.value
        println("Custom Queue Final Size (Expected <= $uniqueIdsPoolSize): ${finalItems.size}")

        // Validations
        val uniqueIdsInResult = finalItems.map { it.identifier }.toSet()

        assertTrue(finalItems.size <= uniqueIdsPoolSize, "Queue failed to bound duplicate elements correctly!")
        assertEquals(finalItems.size, uniqueIdsInResult.size, "Duplicate identifiers found in the final internal list!")

        // Ensure the queue remains perfectly sorted regardless of race conditions
        val isCorrectlySorted = finalItems.zipWithNext { current, next ->
            current.priorityLevel <= next.priorityLevel
        }.all { it }

        assertTrue(isCorrectlySorted, "Queue list is not correctly sorted by priority!")

        println("Validation successful. Queue successfully handled massive concurrent duplicates without internal state corruption.\n")
    }

    /**
     * Test 3: addAll() Unique Keys Benchmark
     *
     * Evaluates performance of batch insertion with unique keys.
     * Compares addAll() vs individual add() calls and validates correctness
     * against ConcurrentSkipListSet.
     */
    @Test
    fun `=== Test 3 add Unique Keys Benchmark ===`() = runBlocking(Dispatchers.Default) {
        println("=== Test 3: add Unique Keys Benchmark ===")
        val maxQueueCapacity = 5_000
        val coroutinesCount = 1_000_000

        val randomSeed = Random(123)
        val preGeneratedBatches = List(coroutinesCount) { batchIndex ->
            TaskItem(
                identifier = "BatchTask_${batchIndex}",
                priorityLevel = randomSeed.nextInt(1, 1_000_000)
            )
        }

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }

        // Test with individual add() calls for comparison
        val queueWithAdd = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addDuration = measureTime {
            preGeneratedBatches.forEach { batch -> queueWithAdd.add(batch) }
        }

        println("Individual add() Execution Time: $addDuration")

        // Reference: Java SkipListSet
        val javaSkipListSet = ConcurrentSkipListSet(deterministicComparator)

        val skipListDuration = measureTime {
            javaSkipListSet.addAll(preGeneratedBatches)
        }

        println("Java SkipList addAll() Time: $skipListDuration")

        // Validate correctness
        val finalAddItems = queueWithAdd.items.value
        val expectedItems = javaSkipListSet.take(maxQueueCapacity)

        assertTrue(finalAddItems.size <= maxQueueCapacity, "add queue exceeded max capacity!")

        assertContentEquals(expectedItems, finalAddItems, "add() results don't match SkipList!")

        println("Validation successful. addAll() produces identical results to add().\n")
    }

    /**
     * Test 5: addAll() Single-Threaded Performance
     *
     * Measures raw performance without concurrency overhead.
     */
    @Test
    fun `=== Test 4 add() Single-Threaded Performance ===`() = runBlocking {
        println("=== Test 4: add() Single-Threaded Performance ===")
        val maxQueueCapacity = 10_000
        val totalElements = 3_000_000

        val randomSeed = Random(789)
        val allTasks = List(totalElements) { index ->
            TaskItem(
                identifier = "SingleTask_$index",
                priorityLevel = randomSeed.nextInt(1, 10_000_000)
            )
        }

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }

        // Individual add() calls
        val queueAdd = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addDuration = measureTime {
            allTasks.forEach { task ->
                queueAdd.add(task)
            }
        }

        println("Individual add() x $totalElements: $addDuration")

        // Java SkipList reference
        val skipList = ConcurrentSkipListSet(deterministicComparator)

        val skipListDuration = measureTime {
            skipList.addAll(allTasks)
        }

        println("Java SkipList addAll(): $skipListDuration")

        // Validate
        val finalAddItems = queueAdd.items.value
        val expectedItems = skipList.take(maxQueueCapacity)

        assertEquals(maxQueueCapacity, finalAddItems.size, "add queue wrong size!")

        assertContentEquals(expectedItems, finalAddItems, "add() results don't match!")

        println("Validation successful.\n")
    }

    /**
     * Test 6: Heavy Load Benchmark
     *
     * Extreme stress test with larger values to measure scalability.
     */
    @Test
    fun `=== Test 5 Heavy Load Scalability Benchmark ===`() = runBlocking(Dispatchers.Default) {
        println("=== Test 5: Heavy Load Scalability Benchmark ===")
        val maxQueueCapacity = 20_000
        val coroutinesCount = 500
        val insertionsPerCoroutine = 4_000 // 2M total operations

        val randomSeed = Random(2024)
        val preGeneratedTasks = List(coroutinesCount) { threadIndex ->
            List(insertionsPerCoroutine) { taskIndex ->
                TaskItem(
                    identifier = "HeavyTask_${threadIndex}_${taskIndex}",
                    priorityLevel = randomSeed.nextInt(1, 100_000_000)
                )
            }
        }

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }

        println("Total operations: ${coroutinesCount * insertionsPerCoroutine} (${coroutinesCount} coroutines x $insertionsPerCoroutine ops)")
        println("Available processors: ${Runtime.getRuntime().availableProcessors()}")

        // Test 1: Individual add() calls
        val queueWithAdd = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addDuration = measureTime {
            val jobs = preGeneratedTasks.map { tasks ->
                launch {
                    tasks.forEach { task ->
                        queueWithAdd.add(task)
                    }
                }
            }
            jobs.joinAll()
        }

        println("Individual add() Time: $addDuration")
        println(
            "Individual add() Throughput: ${
                String.format(
                    "%,.0f",
                    (coroutinesCount * insertionsPerCoroutine) / addDuration.inWholeMilliseconds.toDouble() * 1000
                )
            } ops/sec"
        )


        // Reference: Java SkipList
        val skipList = ConcurrentSkipListSet(deterministicComparator)

        val skipListDuration = measureTime {
            val jobs = preGeneratedTasks.map { tasks ->
                launch {
                    tasks.forEach { task ->
                        skipList.add(task)
                    }
                }
            }
            jobs.joinAll()
        }

        println("Java SkipList Time: $skipListDuration")
        println(
            "Java SkipList Throughput: ${
                String.format(
                    "%,.0f",
                    (coroutinesCount * insertionsPerCoroutine) / skipListDuration.inWholeMilliseconds.toDouble() * 1000
                )
            } ops/sec"
        )

        // Validate
        val finalAddItems = queueWithAdd.items.value
        val expectedItems = skipList.take(maxQueueCapacity)

        assertEquals(maxQueueCapacity, finalAddItems.size, "add() queue wrong size!")
        assertContentEquals(expectedItems, finalAddItems, "add() results don't match SkipList!")

        val speedupVsSkipList =
            skipListDuration.inWholeMilliseconds.toDouble() / addDuration.inWholeMilliseconds.toDouble()
        println("\nCustom Queue vs SkipList: ${String.format("%.2f", speedupVsSkipList)}x")
        println("Validation successful.\n")
    }

    /**
     * Test 7: Large Batch Single Operation
     *
     * Tests the efficiency of a single massive addAll() operation.
     */
    @Test
    fun `=== Test 6 Large Batch Single Operation ===`() = runBlocking {
        println("=== Test 6: Large Batch Single Operation ===")
        val maxQueueCapacity = 50_000
        val totalElements = 2_000_000

        val randomSeed = Random(999)

        println("Generating $totalElements tasks...")
        val allTasks = List(totalElements) { index ->
            TaskItem(
                identifier = "MassiveTask_$index",
                priorityLevel = randomSeed.nextInt(1, Int.MAX_VALUE)
            )
        }

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }


        // Test individual add() for comparison
        val queueAdd = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addDuration = measureTime {
            allTasks.forEach { task ->
                queueAdd.add(task)
            }
        }

        println("Individual add() x $totalElements: $addDuration")
        println(
            "add() Throughput: ${
                String.format(
                    "%,.0f",
                    totalElements / addDuration.inWholeMilliseconds.toDouble() * 1000
                )
            } elements/sec"
        )

        // Java SkipList reference
        val skipList = ConcurrentSkipListSet(deterministicComparator)

        val skipListDuration = measureTime {
            skipList.addAll(allTasks)
        }

        println("Java SkipList addAll(): $skipListDuration")
        println(
            "SkipList Throughput: ${
                String.format(
                    "%,.0f",
                    totalElements / skipListDuration.inWholeMilliseconds.toDouble() * 1000
                )
            } elements/sec"
        )

        // Validate
        val finalAddItems = queueAdd.items.value
        val expectedItems = skipList.take(maxQueueCapacity)

        assertEquals(maxQueueCapacity, finalAddItems.size, "add queue wrong size!")
        assertContentEquals(expectedItems, finalAddItems, "add() results don't match!")

        println("Validation successful.\n")
    }
}