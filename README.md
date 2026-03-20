![concurrent_priority_queue.png](assets/images/concurrent_priority_queue.png)
# Concurrent Priority Queue

A high-performance, lock-free, bounded **Concurrent Priority Queue** for Kotlin Multiplatform (KMP).

Built on a persistent **Treap** data structure with `MutableStateFlow` for thread-safe atomic updates. Designed for heavy concurrent environments where thousands of coroutines can add items simultaneously without blocking.

![Badge: Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7f52ff?logo=kotlin)
[![GitHub license](https://img.shields.io/github/license/kotlin/kotlinx.collections.immutable)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.karloti/concurrent-priority-queue.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.karloti/concurrent-priority-queue)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.10-orange.svg?logo=kotlin)](http://kotlinlang.org)

## Features

- **Lock-Free Updates:** Uses `MutableStateFlow.update()` with CAS (Compare-And-Swap) for non-blocking atomic updates
- **Persistent Treap:** O(log n) operations with structural sharing for efficient copy-on-write semantics
- **Bounded Capacity:** Automatically evicts lowest-priority elements when exceeding `maxSize`
- **Built-in Deduplication:** Unique key selector with automatic "upsert" behavior (update if better priority)
- **Reactive State:** Exposes `StateFlow<List<T>>` for seamless UI binding (Compose, SwiftUI, React)
- **True Multiplatform:** JVM, Android, iOS, macOS, Linux, Windows, watchOS, tvOS, JavaScript, Wasm

## Installation

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("io.github.karloti:concurrent-priority-queue:1.0.3")
}
```

## Quick Start

### Basic Usage

```kotlin
// Create a queue that keeps the top 5 highest numbers
val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)

queue.add(10)
queue.add(50)
queue.add(20)
queue.add(5)
queue.add(100)
queue.add(1)  // Rejected - worse than top 5

println(queue.items.value)  // [100, 50, 20, 10, 5]
```

### Custom Data Classes with Deduplication

```kotlin
data class SearchResult(val id: String, val score: Int)

val queue = ConcurrentPriorityQueue<SearchResult, String>(
    maxSize = 3,
    comparator = compareByDescending { it.score },  // Higher score = higher priority
    uniqueKeySelector = { it.id }                   // Deduplicate by ID
)

queue.add(SearchResult("A", 10))
queue.add(SearchResult("B", 20))
queue.add(SearchResult("A", 30))  // Updates "A" (better priority)
queue.add(SearchResult("A", 5))   // Ignored (worse priority)
queue.add(SearchResult("C", 15))

println(queue.items.value)
// [SearchResult(id=A, score=30), SearchResult(id=B, score=20), SearchResult(id=C, score=15)]
```

### Ascending Order (Min-Heap Behavior)

```kotlin
data class Task(val id: String, val priority: Int)

val taskQueue = ConcurrentPriorityQueue<Task, String>(
    maxSize = 5,
    comparator = compareBy { it.priority },  // Lower number = higher priority
    uniqueKeySelector = { it.id }
)

taskQueue.add(Task("email", 50))
taskQueue.add(Task("backup", 100))
taskQueue.add(Task("critical", 1))

println(taskQueue.first())  // Task(id=critical, priority=1)
```

### Reactive UI Updates (Jetpack Compose)

```kotlin
@Composable
fun TopScoresList(queue: ConcurrentPriorityQueue<Score, String>) {
    val topScores by queue.items.collectAsState()

    LazyColumn {
        items(topScores) { score ->
            Text("${score.name}: ${score.value}")
        }
    }
}
```

### Batch Operations and Filtering

```kotlin
data class Task(val id: String, val priority: Int, val completed: Boolean)

val queue = ConcurrentPriorityQueue<Task, String>(
    maxSize = 100,
    comparator = compareBy { it.priority },
    uniqueKeySelector = { it.id }
)

// Add multiple elements at once
queue.addAll(listOf(
    Task("task-1", 10, false),
    Task("task-2", 5, false),
    Task("task-3", 20, true)
))

// Remove completed tasks
val removedCount = queue.removeIf { it.completed }  // 1

// Keep only high-priority tasks (priority <= 10)
queue.retainIf { it.priority <= 10 }

// Poll highest priority task
val nextTask = queue.poll()  // Task(id=task-2, priority=5)

// Clear all remaining
queue.clear()
```

## API Reference

### Constructor

```kotlin
ConcurrentPriorityQueue<T, K>(
    maxSize: Int = 5,
    comparator: Comparator<T>,
    uniqueKeySelector: (T) -> K
)
```

| Parameter | Description |
|-----------|-------------|
| `maxSize` | Maximum queue capacity. Elements beyond this are evicted. |
| `comparator` | Defines priority order. First element in sorted order has highest priority. |
| `uniqueKeySelector` | Extracts unique identity key for deduplication. |

### Factory Methods

```kotlin
// For Comparable types (descending order, element = key)
ConcurrentPriorityQueue<Int>(maxSize = 10)

// Custom comparator (element = key)
ConcurrentPriorityQueue<Task>(maxSize = 10, compareBy { it.priority })

// Comparable types with custom key selector
ConcurrentPriorityQueue<Score, String>(maxSize = 10) { it.id }
```

### Methods

#### Adding Elements

| Method | Complexity | Description |
|--------|------------|-------------|
| `add(element)` | O(log n) | Add/update element. Returns `true` if added, `false` if rejected. |
| `addAll(elements)` | O(m log n) | Add multiple elements. Returns count of elements added. |

#### Querying Elements

| Method | Complexity | Description |
|--------|------------|-------------|
| `get(key)` / `[key]` | O(1) | Get element by key, or `null`. |
| `first()` | O(log n) | Peek highest priority element. |
| `last()` | O(log n) | Peek lowest priority element. |
| `contains(element)` | O(1) | Check if element exists. |
| `containsKey(key)` | O(1) | Check if key exists. |
| `isEmpty()` | O(1) | Check if queue is empty. |
| `isNotEmpty()` | O(1) | Check if queue has elements. |
| `size` | O(1) | Current number of elements. |
| `items` | O(1) | `StateFlow<List<T>>` - reactive sorted list. |
| `iterator()` | O(n) | Iterate elements in priority order. |

#### Removing Elements

| Method | Complexity | Description |
|--------|------------|-------------|
| `poll()` | O(log n) | Remove and return highest priority element. |
| `remove(element)` | O(log n) | Remove specific element. |
| `removeByKey(key)` | O(log n) | Remove element by key. |
| `removeIf { predicate }` | O(k log n) | Remove all matching elements. Returns removed count. |
| `retainIf { predicate }` | O(k log n) | Keep only matching elements. Returns removed count. |
| `clear()` | O(1) | Remove all elements. |

## Architecture

### Data Structure: Persistent Treap

The queue is backed by `PersistentIndexedPriorityList<T, K>` — a persistent (immutable) **Treap** (Tree + Heap):

```
        [Priority: 0.89]
              30
            /    \
    [0.45]       [0.67]
      20           50
     /  \            \
  [0.12] [0.33]    [0.55]
    10     25        40
```

**Why Treap?**
- **BST Property:** Left < Node < Right (maintains sorted order by comparator)
- **Heap Property:** Parent priority > Child priority (random priorities ensure balance)
- **Expected O(log n):** Randomized balancing without complex rebalancing logic (AVL/Red-Black)
- **Persistent:** Structural sharing enables efficient copy-on-write

### Concurrency Model

```
┌─────────────────────────────────────────────────────────────┐
│                    MutableStateFlow                         │
│                 (Atomic CAS Updates)                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   Thread 1          Thread 2          Thread 3              │
│   add(A)            add(B)            add(C)                │
│      │                 │                 │                  │
│      ▼                 ▼                 ▼                  │
│   ┌──────────────────────────────────────────────┐          │
│   │           MutableStateFlow.update()          │          │
│   │  ┌─────────────────────────────────────────┐ │          │
│   │  │  1. Read current state (snapshot)       │ │          │
│   │  │  2. Compute new state (pure function)   │ │          │
│   │  │  3. CAS: if unchanged, commit; else     │ │          │
│   │  │     retry with new snapshot             │ │          │
│   │  └─────────────────────────────────────────┘ │          │
│   └──────────────────────────────────────────────┘          │
│                          │                                  │
│                          ▼                                  │
│   ┌──────────────────────────────────────────────┐          │
│   │     PersistentIndexedPriorityList (Treap)    │          │
│   │                                              │          │
│   │  • Immutable nodes with structural sharing   │          │
│   │  • O(log n) insert/remove                    │          │
│   │  • O(1) key lookup via PersistentMap         │          │
│   └──────────────────────────────────────────────┘          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Key insight:** Since the Treap is persistent (immutable), failed CAS retries only recompute the delta, not the entire structure. Structural sharing means most nodes are reused.

### Dependencies

| Library | Purpose |
|---------|---------|
| `kotlinx-coroutines-core` | `MutableStateFlow`, `StateFlow` for reactive state |
| `kotlinx-collections-immutable` | `PersistentMap` for O(1) key lookups |
| `kotlinx-atomicfu` | Atomic operations (internal) |

## Performance Comparison

### Benchmark Environment
- **CPU:** 16 cores
- **JVM:** OpenJDK 21
- **Kotlin:** 2.3.10

### Test Results (JVM)

#### Single-Threaded Performance

| Scenario | ConcurrentPriorityQueue | Java ConcurrentSkipListSet |
|----------|------------------------|---------------------------|
| 3M insertions (capacity: 10K) | **264ms** | 5,122ms |
| Throughput | **11.3M ops/sec** | 585K ops/sec |

> **19x faster** for bounded priority queue operations

#### Concurrent Performance (500 coroutines × 4,000 ops = 2M total)

| Scenario | ConcurrentPriorityQueue | Java ConcurrentSkipListSet |
|----------|------------------------|---------------------------|
| 2M concurrent insertions | 484ms | 341ms |
| Throughput | 4.1M ops/sec | 5.8M ops/sec |

> SkipListSet is **0.7x faster** for pure concurrent inserts, BUT lacks built-in bounding and deduplication

#### Feature Comparison

| Feature | ConcurrentPriorityQueue | ConcurrentSkipListSet | PriorityQueue |
|---------|------------------------|----------------------|---------------|
| Thread-Safe | ✅ Lock-free | ✅ Lock-free | ❌ Not thread-safe |
| Bounded Capacity | ✅ Built-in | ❌ Manual | ❌ Manual |
| Deduplication | ✅ Built-in (upsert) | ✅ Set semantics | ❌ No |
| Priority Updates | ✅ Automatic | ❌ Remove + Add | ❌ Remove + Add |
| Reactive (StateFlow) | ✅ Native | ❌ No | ❌ No |
| Kotlin Multiplatform | ✅ Full | ❌ JVM only | ❌ JVM only |
| Memory per Update | O(log n) new nodes | O(1) | O(n) rebuild |

### Complexity Analysis

| Operation | ConcurrentPriorityQueue | ConcurrentSkipListSet | PriorityQueue |
|-----------|------------------------|----------------------|---------------|
| `add()` | O(log n) | O(log n) | O(log n) |
| `poll()` / `first()` | O(log n) | O(1) | O(log n) |
| `contains(key)` | O(1) | O(log n) | O(n) |
| `remove(key)` | O(log n) | O(log n) | O(n) |
| Bounded trim | O(log n) | O(n) manual | O(n) manual |
| Concurrent contention | Retry (CAS) | Lock-free | N/A (unsafe) |

### When to Use What

| Use Case | Recommended |
|----------|-------------|
| Bounded leaderboard with live updates | **ConcurrentPriorityQueue** |
| High-throughput unbounded concurrent set | ConcurrentSkipListSet |
| Single-threaded priority scheduling | PriorityQueue |
| Multiplatform (iOS, JS, Wasm) | **ConcurrentPriorityQueue** |
| Need reactive UI binding | **ConcurrentPriorityQueue** |

## Algorithm Details

### Insert Operation

```kotlin
fun add(element: T): Boolean {
    queueState.update { currentState ->
        // Fast-path 1: Empty queue
        if (currentState.isEmpty()) return@update currentState.insert(element)

        // Fast-path 2: Key exists with equal/better priority → reject
        val existing = currentState[uniqueKeySelector(element)]
        if (existing != null && comparator.compare(element, existing) >= 0) {
            return@update currentState  // No change
        }

        // Fast-path 3: Full queue, new element worse than last → reject
        if (currentState.size >= maxSize && existing == null) {
            val last = currentState.last()
            if (last != null && comparator.compare(element, last) >= 0) {
                return@update currentState  // No change
            }
        }

        // Insert (auto-removes existing key if present)
        var updated = currentState.insert(element)

        // Evict lowest priority if over capacity
        if (updated.size > maxSize) {
            updated = updated.removeLast()
        }

        updated
    }
}
```

### Treap Insert (Simplified)

```kotlin
private fun insertNode(node: Node?, element: T, key: K, priority: Long): Node {
    if (node == null) return Node(element, key, priority)

    return if (comparator.compare(element, node.element) <= 0) {
        // Insert left
        val newLeft = insertNode(node.left, element, key, priority)
        val newNode = node.copy(left = newLeft)

        // Rotate right if treap heap property violated
        if (newLeft.priority > node.priority) rotateRight(newNode)
        else newNode
    } else {
        // Insert right
        val newRight = insertNode(node.right, element, key, priority)
        val newNode = node.copy(right = newRight)

        // Rotate left if treap heap property violated
        if (newRight.priority > node.priority) rotateLeft(newNode)
        else newNode
    }
}
```

## Supported Platforms

| Platform | Targets |
|----------|---------|
| **JVM** | `jvm` |
| **Android** | `androidTarget` |
| **iOS** | `iosX64`, `iosArm64`, `iosSimulatorArm64` |
| **macOS** | `macosX64`, `macosArm64` |
| **Linux** | `linuxX64`, `linuxArm64` |
| **Windows** | `mingwX64` |
| **watchOS** | `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, `watchosX64`, `watchosSimulatorArm64` |
| **tvOS** | `tvosX64`, `tvosArm64`, `tvosSimulatorArm64` |
| **Web** | `js`, `wasmJs`, `wasmWasi` |
| **Android Native** | `androidNativeArm32`, `androidNativeArm64`, `androidNativeX86`, `androidNativeX64` |

## Tracking & Roadmap

Issue tracking: [Concurrent Priority Queue - YouTrack](https://smartcoding.youtrack.cloud/projects/CPQ)

## License

Apache License 2.0 - see [LICENSE](LICENSE) for details.
