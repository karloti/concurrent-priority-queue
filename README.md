# Concurrent Priority Queue

A high-performance, lock-free, asynchronous **Concurrent Priority Queue** for Kotlin Multiplatform (KMP).

Designed for heavy concurrent environments, this library provides a bounded, thread-safe priority queue that allows thousands of coroutines to add items simultaneously without blocking, while a serialized consumer efficiently manages the internal state.

![Badge: Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7f52ff?logo=kotlin)
[![GitHub license](https://img.shields.io/github/license/kotlin/kotlinx.collections.immutable)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.karloti/concurrent-priority-queue.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.karloti/concurrent-priority-queue)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.10-orange.svg?logo=kotlin)](http://kotlinlang.org)

## Features

*   **Lock-Free Producer:** The `add()` operation is non-blocking and wait-free for producers, utilizing atomic operations.
*   **Serialized Consumer:** Uses a high-performance spin-lock mechanism to ensure safe, single-threaded processing of the queue state without suspending producers.
*   **Bounded Capacity:** Automatically evicts the lowest-priority element when the queue exceeds `maxSize`.
*   **Deduplication:** Built-in support for unique keys (`uniqueKeySelector`) to prevent duplicate items (e.g., updating an existing item's score instead of adding a new one).
*   **Reactive State:** Exposes the current queue state as a `StateFlow<List<T>>`, making it perfect for UI binding (Compose, SwiftUI, React).
*   **True Multiplatform:** Works on **JVM, Android, iOS, macOS, Linux, Windows, watchOS, tvOS, JavaScript, and Wasm**.

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
commonMain.dependencies {
    implementation("io.github.karloti:concurrent-priority-queue:1.0.1")
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

1.  **Pending Buffer:** Incoming items are instantly appended to an atomic, lock-free `PendingBuffer`. This ensures `add()` is always O(1) for the producer.
2.  **Drain Loop:** A "Leader" thread is elected via an atomic `CompareAndSet` flag (`isProcessing`).
3.  **Batch Processing:** The leader drains the buffer and merges it into the internal `PersistentMap` and `PersistentList`.
4.  **Immutable State:** The final state is emitted to `_items` as a truly immutable list, ensuring thread safety for all readers.

## Supported Targets

| Platform | Targets |
| :--- | :--- |
| **JVM** | `jvm` |
| **Android** | `androidTarget` |
| **iOS** | `iosX64`, `iosArm64`, `iosSimulatorArm64` |
| **macOS** | `macosX64`, `macosArm64` |
| **Linux** | `linuxX64`, `linuxArm64` |
| **Windows** | `mingwX64` |
| **watchOS** | All targets |
| **tvOS** | All targets |
| **Web** | `js`, `wasmJs`, `wasmWasi` |


## Tracking & Roadmap

We use YouTrack for task management and issue tracking.
You can view the current tasks and progress here:
[Typeahead KMP Issues & Roadmap](https://smartcoding.youtrack.cloud/projects/CPQ)

## License

This project is licensed under the **Apache License Version 2.0** - see the [LICENSE](LICENSE) file for details.
