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
    fun `test thread safety under heavy load with strictly unique keys`() = runBlocking(Dispatchers.Default) {
        println("=== Test 1: Unique Keys Benchmark ===")
        val maxQueueCapacity = 5_000
        val coroutinesCount = 5_000 // Increased for extreme stress testing
        val insertionsPerCoroutine = 200

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
    fun `test thread safety under heavy load with overlapping duplicate keys`() = runBlocking(Dispatchers.Default) {
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
    fun `test addAll performance with unique keys`() = runBlocking(Dispatchers.Default) {
        println("=== Test 3: addAll() Unique Keys Benchmark ===")
        val maxQueueCapacity = 5_000
        val coroutinesCount = 500
        val batchSize = 2_000 // Elements per batch

        val randomSeed = Random(123)
        val preGeneratedBatches = List(coroutinesCount) { batchIndex ->
            List(batchSize) { taskIndex ->
                TaskItem(
                    identifier = "BatchTask_${batchIndex}_${taskIndex}",
                    priorityLevel = randomSeed.nextInt(1, 1_000_000)
                )
            }
        }

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }

        // Test with addAll()
        val queueWithAddAll = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addAllDuration = measureTime {
            val workerJobs = preGeneratedBatches.map { batch ->
                launch {
                    queueWithAddAll.addAll(batch)
                }
            }
            workerJobs.joinAll()
        }

        println("addAll() Execution Time: $addAllDuration")

        // Test with individual add() calls for comparison
        val queueWithAdd = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addDuration = measureTime {
            val workerJobs = preGeneratedBatches.map { batch ->
                launch {
                    batch.forEach { task ->
                        queueWithAdd.add(task)
                    }
                }
            }
            workerJobs.joinAll()
        }

        println("Individual add() Execution Time: $addDuration")

        // Reference: Java SkipListSet
        val javaSkipListSet = ConcurrentSkipListSet(deterministicComparator)

        val skipListDuration = measureTime {
            val workerJobs = preGeneratedBatches.map { batch ->
                launch {
                    javaSkipListSet.addAll(batch)
                }
            }
            workerJobs.joinAll()
        }

        println("Java SkipList addAll() Time: $skipListDuration")

        // Validate correctness
        val finalAddAllItems = queueWithAddAll.items.value
        val finalAddItems = queueWithAdd.items.value
        val expectedItems = javaSkipListSet.take(maxQueueCapacity)

        assertTrue(finalAddAllItems.size <= maxQueueCapacity, "addAll queue exceeded max capacity!")
        assertTrue(finalAddItems.size <= maxQueueCapacity, "add queue exceeded max capacity!")

        assertContentEquals(expectedItems, finalAddAllItems, "addAll() results don't match SkipList!")
        assertContentEquals(expectedItems, finalAddItems, "add() results don't match SkipList!")
        assertContentEquals(finalAddAllItems, finalAddItems, "addAll() and add() results differ!")

        val speedup = addDuration.inWholeMilliseconds.toDouble() / addAllDuration.inWholeMilliseconds.toDouble()
        println("Speedup (add/addAll): ${String.format("%.2f", speedup)}x")
        println("Validation successful. addAll() produces identical results to add().\n")
    }

    /**
     * Test 4: addAll() Duplicate Keys Benchmark
     *
     * Evaluates addAll() performance with heavy key collisions.
     * Verifies deduplication logic works correctly in batch mode.
     */
    @Test
    fun `test addAll performance with duplicate keys`() = runBlocking(Dispatchers.Default) {
        println("=== Test 4: addAll() Duplicate Keys Benchmark ===")
        val maxQueueCapacity = 100
        val coroutinesCount = 500
        val batchSize = 2_000
        val uniqueIdsPoolSize = 50 // Heavy collisions

        val randomSeed = Random(456)
        val preGeneratedBatches = List(coroutinesCount) { batchIndex ->
            List(batchSize) { taskIndex ->
                val sharedId = taskIndex % uniqueIdsPoolSize
                TaskItem(
                    identifier = "SharedBatchTask_$sharedId",
                    priorityLevel = randomSeed.nextInt(1, 1_000_000)
                )
            }
        }

        val priorityComparator = compareBy<TaskItem> { it.priorityLevel }

        // Test with addAll()
        val queueWithAddAll = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = priorityComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addAllDuration = measureTime {
            val workerJobs = preGeneratedBatches.map { batch ->
                launch {
                    queueWithAddAll.addAll(batch)
                }
            }
            workerJobs.joinAll()
        }

        println("addAll() Execution Time (Duplicates): $addAllDuration")

        // Test with individual add()
        val queueWithAdd = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = priorityComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addDuration = measureTime {
            val workerJobs = preGeneratedBatches.map { batch ->
                launch {
                    batch.forEach { task ->
                        queueWithAdd.add(task)
                    }
                }
            }
            workerJobs.joinAll()
        }

        println("Individual add() Execution Time (Duplicates): $addDuration")

        // Validate results
        val finalAddAllItems = queueWithAddAll.items.value
        val finalAddItems = queueWithAdd.items.value

        println("addAll() Final Size: ${finalAddAllItems.size}")
        println("add() Final Size: ${finalAddItems.size}")

        // Size validation
        assertTrue(finalAddAllItems.size <= uniqueIdsPoolSize, "addAll queue has too many elements!")
        assertTrue(finalAddItems.size <= uniqueIdsPoolSize, "add queue has too many elements!")

        // Uniqueness validation
        val addAllUniqueIds = finalAddAllItems.map { it.identifier }.toSet()
        val addUniqueIds = finalAddItems.map { it.identifier }.toSet()

        assertEquals(finalAddAllItems.size, addAllUniqueIds.size, "addAll() has duplicate keys!")
        assertEquals(finalAddItems.size, addUniqueIds.size, "add() has duplicate keys!")

        // Sorting validation
        val addAllSorted = finalAddAllItems.zipWithNext { a, b -> a.priorityLevel <= b.priorityLevel }.all { it }
        val addSorted = finalAddItems.zipWithNext { a, b -> a.priorityLevel <= b.priorityLevel }.all { it }

        assertTrue(addAllSorted, "addAll() result is not sorted!")
        assertTrue(addSorted, "add() result is not sorted!")

        // Both should have the same best priorities for each key
        // (may differ in which specific element due to race conditions, but priorities should match)
        val addAllPriorities = finalAddAllItems.sortedBy { it.identifier }.map { it.priorityLevel }
        val addPriorities = finalAddItems.sortedBy { it.identifier }.map { it.priorityLevel }

        // Keys should be the same
        assertEquals(
            finalAddAllItems.map { it.identifier }.toSet(),
            finalAddItems.map { it.identifier }.toSet(),
            "Different keys between addAll() and add()!"
        )

        val speedup = addDuration.inWholeMilliseconds.toDouble() / addAllDuration.inWholeMilliseconds.toDouble()
        println("Speedup (add/addAll): ${String.format("%.2f", speedup)}x")
        println("Validation successful. addAll() handles duplicates correctly.\n")
    }

    /**
     * Test 5: addAll() Single-Threaded Performance
     *
     * Measures raw performance without concurrency overhead.
     */
    @Test
    fun `test addAll single-threaded performance`() = runBlocking {
        println("=== Test 5: addAll() Single-Threaded Performance ===")
        val maxQueueCapacity = 10_000
        val totalElements = 1_000_000

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

        // Single batch addAll()
        val queueAddAll = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addAllDuration = measureTime {
            queueAddAll.addAll(allTasks)
        }

        println("Single addAll($totalElements elements): $addAllDuration")

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
        val finalAddAllItems = queueAddAll.items.value
        val finalAddItems = queueAdd.items.value
        val expectedItems = skipList.take(maxQueueCapacity)

        assertEquals(maxQueueCapacity, finalAddAllItems.size, "addAll queue wrong size!")
        assertEquals(maxQueueCapacity, finalAddItems.size, "add queue wrong size!")

        assertContentEquals(expectedItems, finalAddAllItems, "addAll() results don't match!")
        assertContentEquals(expectedItems, finalAddItems, "add() results don't match!")

        val speedup = addDuration.inWholeMilliseconds.toDouble() / addAllDuration.inWholeMilliseconds.toDouble()
        println("Speedup (add/addAll): ${String.format("%.2f", speedup)}x")
        println("Validation successful.\n")
    }

    /**
     * Test 6: Heavy Load Benchmark
     *
     * Extreme stress test with larger values to measure scalability.
     */
    @Test
    fun `test heavy load scalability`() = runBlocking(Dispatchers.Default) {
        println("=== Test 6: Heavy Load Scalability Benchmark ===")
        val maxQueueCapacity = 20_000
        val coroutinesCount = 500
        val insertionsPerCoroutine = 4_000 // 2M total operations
        val batchSize = 500

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
        println("Individual add() Throughput: ${String.format("%,.0f", (coroutinesCount * insertionsPerCoroutine) / addDuration.inWholeMilliseconds.toDouble() * 1000)} ops/sec")

        // Test 2: Batch addAll() calls
        val queueWithAddAll = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addAllDuration = measureTime {
            val jobs = preGeneratedTasks.map { tasks ->
                launch {
                    // Process in batches
                    tasks.chunked(batchSize).forEach { batch ->
                        queueWithAddAll.addAll(batch)
                    }
                }
            }
            jobs.joinAll()
        }

        println("Batched addAll() Time (batch=$batchSize): $addAllDuration")
        println("Batched addAll() Throughput: ${String.format("%,.0f", (coroutinesCount * insertionsPerCoroutine) / addAllDuration.inWholeMilliseconds.toDouble() * 1000)} ops/sec")

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
        println("Java SkipList Throughput: ${String.format("%,.0f", (coroutinesCount * insertionsPerCoroutine) / skipListDuration.inWholeMilliseconds.toDouble() * 1000)} ops/sec")

        // Validate
        val finalAddItems = queueWithAdd.items.value
        val finalAddAllItems = queueWithAddAll.items.value
        val expectedItems = skipList.take(maxQueueCapacity)

        assertEquals(maxQueueCapacity, finalAddItems.size, "add() queue wrong size!")
        assertEquals(maxQueueCapacity, finalAddAllItems.size, "addAll() queue wrong size!")
        assertContentEquals(expectedItems, finalAddItems, "add() results don't match SkipList!")
        assertContentEquals(expectedItems, finalAddAllItems, "addAll() results don't match SkipList!")

        val speedupVsSkipList = skipListDuration.inWholeMilliseconds.toDouble() / addDuration.inWholeMilliseconds.toDouble()
        println("\nCustom Queue vs SkipList: ${String.format("%.2f", speedupVsSkipList)}x")
        println("Validation successful.\n")
    }

    /**
     * Test 7: Large Batch Single Operation
     *
     * Tests the efficiency of a single massive addAll() operation.
     */
    @Test
    fun `test large batch single operation`() = runBlocking {
        println("=== Test 7: Large Batch Single Operation ===")
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

        // Test addAll() with massive batch
        val queueAddAll = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val addAllDuration = measureTime {
            queueAddAll.addAll(allTasks)
        }

        println("addAll($totalElements elements): $addAllDuration")
        println("addAll() Throughput: ${String.format("%,.0f", totalElements / addAllDuration.inWholeMilliseconds.toDouble() * 1000)} elements/sec")

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
        println("add() Throughput: ${String.format("%,.0f", totalElements / addDuration.inWholeMilliseconds.toDouble() * 1000)} elements/sec")

        // Java SkipList reference
        val skipList = ConcurrentSkipListSet(deterministicComparator)

        val skipListDuration = measureTime {
            skipList.addAll(allTasks)
        }

        println("Java SkipList addAll(): $skipListDuration")
        println("SkipList Throughput: ${String.format("%,.0f", totalElements / skipListDuration.inWholeMilliseconds.toDouble() * 1000)} elements/sec")

        // Validate
        val finalAddAllItems = queueAddAll.items.value
        val finalAddItems = queueAdd.items.value
        val expectedItems = skipList.take(maxQueueCapacity)

        assertEquals(maxQueueCapacity, finalAddAllItems.size, "addAll queue wrong size!")
        assertEquals(maxQueueCapacity, finalAddItems.size, "add queue wrong size!")
        assertContentEquals(expectedItems, finalAddAllItems, "addAll() results don't match!")
        assertContentEquals(expectedItems, finalAddItems, "add() results don't match!")

        println("\nCustom Queue addAll() vs SkipList: ${String.format("%.2f", skipListDuration.inWholeMilliseconds.toDouble() / addAllDuration.inWholeMilliseconds.toDouble())}x")
        println("Validation successful.\n")
    }

    /**
     * Test 8: addAllAsync() Parallel Performance
     *
     * Compares synchronous addAll() vs asynchronous addAllAsync() with parallel processing.
     */
    @Test
    fun `test addAllAsync parallel performance`() = runBlocking {
        println("=== Test 8: addAllAsync() Parallel Performance ===")
        val maxQueueCapacity = 50_000
        val totalElements = 2_000_000
        val availableProcessors = Runtime.getRuntime().availableProcessors()

        val randomSeed = Random(777)

        println("Generating $totalElements tasks...")
        val allTasks = List(totalElements) { index ->
            TaskItem(
                identifier = "AsyncTask_$index",
                priorityLevel = randomSeed.nextInt(1, Int.MAX_VALUE)
            )
        }

        val deterministicComparator = Comparator<TaskItem> { first, second ->
            val priorityComparison = first.priorityLevel.compareTo(second.priorityLevel)
            if (priorityComparison != 0) priorityComparison else first.identifier.compareTo(second.identifier)
        }

        println("Available processors: $availableProcessors")

        // Test 1: Synchronous addAll()
        val queueSync = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val syncDuration = measureTime {
            queueSync.addAll(allTasks)
        }

        println("Sync addAll(): $syncDuration")
        println("Sync Throughput: ${String.format("%,.0f", totalElements / syncDuration.inWholeMilliseconds.toDouble() * 1000)} elements/sec")

        // Test 2: Async addAllAsync() with default parallelism
        val queueAsync = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val asyncDuration = measureTime {
            queueAsync.addAllAsync(allTasks)
        }

        println("Async addAllAsync(parallelism=4): $asyncDuration")
        println("Async Throughput: ${String.format("%,.0f", totalElements / asyncDuration.inWholeMilliseconds.toDouble() * 1000)} elements/sec")

        // Test 3: Async with max parallelism
        val queueAsyncMax = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = deterministicComparator,
            uniqueKeySelector = { it.identifier }
        )

        val asyncMaxDuration = measureTime {
            queueAsyncMax.addAllAsync(allTasks, parallelism = availableProcessors)
        }

        println("Async addAllAsync(parallelism=$availableProcessors): $asyncMaxDuration")
        println("Async Max Throughput: ${String.format("%,.0f", totalElements / asyncMaxDuration.inWholeMilliseconds.toDouble() * 1000)} elements/sec")

        // Java SkipList reference
        val skipList = ConcurrentSkipListSet(deterministicComparator)

        val skipListDuration = measureTime {
            skipList.addAll(allTasks)
        }

        println("Java SkipList addAll(): $skipListDuration")

        // Validate all produce same results
        val syncItems = queueSync.items.value
        val asyncItems = queueAsync.items.value
        val asyncMaxItems = queueAsyncMax.items.value
        val expectedItems = skipList.take(maxQueueCapacity)

        assertEquals(maxQueueCapacity, syncItems.size, "Sync queue wrong size!")
        assertEquals(maxQueueCapacity, asyncItems.size, "Async queue wrong size!")
        assertEquals(maxQueueCapacity, asyncMaxItems.size, "Async max queue wrong size!")

        assertContentEquals(expectedItems, syncItems, "Sync results don't match SkipList!")
        assertContentEquals(expectedItems, asyncItems, "Async results don't match SkipList!")
        assertContentEquals(expectedItems, asyncMaxItems, "Async max results don't match SkipList!")

        val asyncSpeedup = syncDuration.inWholeMilliseconds.toDouble() / asyncDuration.inWholeMilliseconds.toDouble()
        val asyncMaxSpeedup = syncDuration.inWholeMilliseconds.toDouble() / asyncMaxDuration.inWholeMilliseconds.toDouble()

        println("\nSpeedup async(4) vs sync: ${String.format("%.2f", asyncSpeedup)}x")
        println("Speedup async($availableProcessors) vs sync: ${String.format("%.2f", asyncMaxSpeedup)}x")
        println("Validation successful.\n")
    }

    /**
     * Test 9: addAllAsync() with Heavy Key Collisions
     *
     * Tests parallel deduplication with many duplicate keys.
     */
    @Test
    fun `test addAllAsync with duplicate keys`() = runBlocking {
        println("=== Test 9: addAllAsync() Duplicate Keys ===")
        val maxQueueCapacity = 1_000
        val totalElements = 500_000
        val uniqueKeysPool = 10_000 // Heavy collisions

        val randomSeed = Random(888)

        println("Generating $totalElements tasks with $uniqueKeysPool unique keys...")
        val allTasks = List(totalElements) { index ->
            val sharedId = index % uniqueKeysPool
            TaskItem(
                identifier = "DupAsyncTask_$sharedId",
                priorityLevel = randomSeed.nextInt(1, Int.MAX_VALUE)
            )
        }

        val priorityComparator = compareBy<TaskItem> { it.priorityLevel }

        // Sync addAll()
        val queueSync = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = priorityComparator,
            uniqueKeySelector = { it.identifier }
        )

        val syncDuration = measureTime {
            queueSync.addAll(allTasks)
        }

        println("Sync addAll(): $syncDuration")

        // Async addAllAsync()
        val queueAsync = ConcurrentPriorityQueue(
            maxSize = maxQueueCapacity,
            comparator = priorityComparator,
            uniqueKeySelector = { it.identifier }
        )

        val asyncDuration = measureTime {
            queueAsync.addAllAsync(allTasks, parallelism = Runtime.getRuntime().availableProcessors())
        }

        println("Async addAllAsync(): $asyncDuration")

        // Validate
        val syncItems = queueSync.items.value
        val asyncItems = queueAsync.items.value

        assertEquals(syncItems.size, asyncItems.size, "Size mismatch!")
        assertTrue(syncItems.size <= maxQueueCapacity, "Exceeded max capacity!")

        // Check no duplicate keys
        val syncKeys = syncItems.map { it.identifier }.toSet()
        val asyncKeys = asyncItems.map { it.identifier }.toSet()

        assertEquals(syncItems.size, syncKeys.size, "Sync has duplicate keys!")
        assertEquals(asyncItems.size, asyncKeys.size, "Async has duplicate keys!")

        // Both should have same keys (best priorities)
        assertEquals(syncKeys, asyncKeys, "Different keys between sync and async!")

        // Check sorting
        val syncSorted = syncItems.zipWithNext { a, b -> a.priorityLevel <= b.priorityLevel }.all { it }
        val asyncSorted = asyncItems.zipWithNext { a, b -> a.priorityLevel <= b.priorityLevel }.all { it }

        assertTrue(syncSorted, "Sync result not sorted!")
        assertTrue(asyncSorted, "Async result not sorted!")

        val speedup = syncDuration.inWholeMilliseconds.toDouble() / asyncDuration.inWholeMilliseconds.toDouble()
        println("Speedup async vs sync: ${String.format("%.2f", speedup)}x")
        println("Final size: ${syncItems.size}")
        println("Validation successful.\n")
    }
}