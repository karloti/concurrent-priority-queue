# Concurrent Priority Queue

A high-performance, lock-free, asynchronous **Concurrent Priority Queue** for Kotlin Multiplatform (KMP).

Designed for heavy concurrent environments, this library provides a bounded, thread-safe priority queue that allows thousands of coroutines to add items simultaneously without blocking, while a serialized consumer efficiently manages the internal state.

![Badge: Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7f52ff?logo=kotlin)
[![GitHub license](https://img.shields.io/github/license/kotlin/kotlinx.collections.immutable)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.karloti/concurrent-priority-queue.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.karloti/concurrent-priority-queue)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.10-orange.svg?logo=kotlin)](http://kotlinlang.org)

## Features

*   **Lock-Free Producer:** The `add()` operation is non-blocking and wait-free for producers, utilizing atomic updates on immutable snapshots.
*   **Persistent Collections:** Uses `kotlinx-collections-immutable` (`PersistentList` and `PersistentMap`) to maintain a consistent state efficiently.
*   **Bounded Capacity:** Automatically evicts the lowest-priority element when the queue exceeds `maxSize`.
*   **Deduplication:** Built-in support for unique keys (`uniqueKeySelector`) to prevent duplicate items (e.g., updating an existing item's score instead of adding a new one).
*   **Reactive State:** Exposes the current queue state as a `StateFlow<List<T>>`, making it perfect for UI binding (Compose, SwiftUI, React).
*   **True Multiplatform:** Works on **JVM, Android, iOS, macOS, Linux, Windows, watchOS, tvOS, JavaScript, and Wasm**.

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
commonMain.dependencies {
    implementation("io.github.karloti:concurrent-priority-queue:1.0.3") // <- Get the latest version
}
```

## Usage

### 1. Basic Usage (Comparable Items)

Create a queue for simple comparable types (like `Int`, `String`, or `Double`). The queue will automatically keep the **highest** values (descending order) by default.

```kotlin
// Create a queue that holds the top 3 highest numbers
val queue = ConcurrentPriorityQueue<Int>(maxSize = 3)

// Add items concurrently from any thread
queue.add(10)
queue.add(5)
queue.add(20)
queue.add(1)

// Access the current state (Snapshots are immutable)
println(queue.items.value) 
// Output: [20, 10, 5]
```

### 2. Custom Data Classes with Deduplication

For complex objects, you can define a custom `priorityComparator` and a `uniqueKeySelector`. This is useful for "Upsert" behavior—where adding an item with an existing ID updates it if the new priority is higher (or ignores it if lower), but never duplicates the ID.

```kotlin
data class SearchResult(val id: String, val score: Int)

val searchQueue = ConcurrentPriorityQueue<SearchResult, String>(
    maxSize = 5,
    priorityComparator = compareByDescending { it.score }, // Keep highest scores
    uniqueKeySelector = { it.id } // Deduplicate by ID
)

// Add an item
searchQueue.add(SearchResult("A", 10))

// Try adding the same ID with a HIGHER score -> Updates existing item
searchQueue.add(SearchResult("A", 20))

// Try adding the same ID with a LOWER score -> Ignored
searchQueue.add(SearchResult("A", 5))

// Add other items
searchQueue.add(SearchResult("B", 15))

println(searchQueue.items.value)
// Output: [SearchResult("A", 20), SearchResult("B", 15)]
```

### 3. Reactive UI Updates

Since `items` is a `StateFlow`, you can collect it in your UI.

**Jetpack Compose:**
```kotlin
@Composable
fun TopScoresList(queue: ConcurrentPriorityQueue<Score, String>) {
    val topScores by queue.items.collectAsState()

    LazyColumn {
        items(topScores) { score ->
            Text("Score: ${score.value}")
        }
    }
}
```

## How It Works

1.  **Atomic Snapshots:** The internal state is stored as an immutable `Snapshot` containing a `PersistentList` (sorted by priority) and a `PersistentMap` (for O(1) key lookups).
2.  **Lock-Free Updates:** The `add()` operation uses `atomic.update` to ensure thread-safety. It performs a non-blocking "Copy-on-Write" style update on the persistent data structures.
3.  **Deduplication & Bounding:** During each update, the queue checks for existing keys (O(1)), performs binary search for insertion points (O(log N)), and trims the collection to `maxSize`.
4.  **Wait-Free Reads:** Reading `items` simply returns the current immutable list reference, ensuring zero contention with writers.

## Complexity & Memory Analysis

| Operation | Time Complexity | Memory Complexity | Description |
| :--- | :--- | :--- | :--- |
| **`add()`** | $O(\log N)$ | $O(N)$ | Logarithmic insertion due to binary search and persistent collection structural sharing. |
| **`items` (Read)** | $O(1)$ | $O(1)$ | Instant access to the latest immutable snapshot. |
| **Storage** | - | $O(N)$ | Stores $N$ elements in a list and $N$ keys in a map. |

## Benchmarks (JVM)

Performance comparison under extreme stress (5,000 coroutines, 1,000,000 total insertions) on `Dispatchers.Default`:

| Scenario | ConcurrentPriorityQueue | ConcurrentSkipListSet (Java) |
| :--- | :--- | :--- |
| **Unique Keys (5,000 capacity)** | ~397ms | ~187ms |
| **Duplicate Keys (50 keys, 100 capacity)** | ~97ms | N/A (Limited bounding support) |

*Note: While `ConcurrentSkipListSet` is faster for simple unique insertions, `ConcurrentPriorityQueue` provides superior convenience with built-in unique key selectors (Upsert logic) and deterministic bounding, which are not natively available in standard Java concurrent collections.*

## Supported Targets

| Platform | Targets |
| :--- | :--- |
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

We use YouTrack for task management and issue tracking.
You can view the current tasks and progress here:
[Typeahead KMP Issues & Roadmap](https://smartcoding.youtrack.cloud/projects/CPQ)

## License

This project is licensed under the **Apache License Version 2.0** - see the [LICENSE](LICENSE) file for details.
