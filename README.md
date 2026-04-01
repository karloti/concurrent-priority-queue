[![High-Performance Concurrent Priority Queue](assets/images/concurrent_priority_queue.png)](https://youtu.be/6E7KxG_bQdQ)

# Concurrent Priority Queue for Kotlin Multiplatform

A high-performance, **lock-free**, **bounded** priority queue for Kotlin Multiplatform. Built on a persistent [Treap](https://en.wikipedia.org/wiki/Treap) data structure combined with `MutableStateFlow` for thread-safe atomic updates via CAS (Compare-And-Swap). Designed for concurrent environments where many coroutines or threads insert, update, and remove elements simultaneously — without blocking.

![Badge: Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7f52ff?logo=kotlin)
[![GitHub license](https://img.shields.io/github/license/kotlin/kotlinx.collections.immutable)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.karloti/concurrent-priority-queue.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.karloti/concurrent-priority-queue)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.10-orange.svg?logo=kotlin)](http://kotlinlang.org)

---

## Key Features

- **Lock-Free Concurrency** — Uses `MutableStateFlow.update()` with CAS for non-blocking atomic updates. No mutexes, no locks, no deadlocks.
- **Persistent Treap** — O(log n) insert, remove, and access with structural sharing. Old and new states share most of their nodes, ensuring efficient memory usage.
- **Bounded Capacity** — Automatically evicts the lowest-priority elements when `maxSize` is reached.
- **Built-in Deduplication (Upsert)** — A unique key selector prevents duplicates. If a key already exists, the element is updated only if the new one has strictly better priority.
- **Reactive State** — Exposes `StateFlow<List<T>>` for seamless UI binding with Jetpack Compose, SwiftUI, or any reactive framework.
- **O(1) Key Lookup** — Internal persistent hash map enables constant-time element retrieval by key.
- **Batch Mutations** — High-performance modifications via `builder()` or `mutate { ... }` that bypass per-operation CAS overhead.
- **True Multiplatform** — Supports JVM, Android, iOS, macOS, Linux, Windows, watchOS, tvOS, JavaScript, and WebAssembly.

---

## Installation

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("io.github.karloti:concurrent-priority-queue:1.3.5")  // <- Use latest version
}
```

The library transitively provides `kotlinx-coroutines-core`, so you don't need to declare it separately for `StateFlow` and `Flow` types.

---

## Quick Start

### Basic Usage — Top-K Smallest Elements (Default)

By default, the queue uses **natural order** (ascending), meaning smaller values have higher priority.

```kotlin
// Keep the 5 smallest integers (min-priority queue)
val queue = ConcurrentPriorityQueue<Int>(maxSize = 5)

queue.add(10)
queue.add(50)
queue.add(20)
queue.add(5)
queue.add(1)
queue.add(100) // Rejected — larger than all current top 5

println(queue.items.value)  // [1, 5, 10, 20, 50]
```

### Custom Rankings (Max-Priority)

```kotlin
data class SearchResult(val id: String, val score: Int)

val queue = ConcurrentPriorityQueue<SearchResult, String>(
    maxSize = 3,
    comparator = compareByDescending { it.score },  // Higher score = higher priority
    uniqueKeySelector = { it.id }
)

queue.add(SearchResult("A", 10))
queue.add(SearchResult("B", 20))
queue.add(SearchResult("A", 30))  // Updates "A" to score 30 (better priority)
queue.add(SearchResult("A", 5))   // Rejected — existing score 30 is better
queue.add(SearchResult("C", 15))

println(queue.items.value)
// [SearchResult(id=A, score=30), SearchResult(id=B, score=20), SearchResult(id=C, score=15)]
```

### Task Scheduling

```kotlin
data class Task(val id: String, val deadline: Long)

val taskQueue = ConcurrentPriorityQueue<Task, String>(
    maxSize = 100,
    comparator = compareBy { it.deadline },  // Earlier deadline = higher priority
    uniqueKeySelector = { it.id }
)

taskQueue.add(Task("backup", 1735689600))
taskQueue.add(Task("critical-fix", 1735603200))

println(taskQueue.first())  // Task(id=critical-fix, ...)
val next = taskQueue.poll() // Removes and returns the highest priority task
```

### Reactive UI with Jetpack Compose

```kotlin
@Composable
fun LeaderboardScreen(queue: ConcurrentPriorityQueue<Score, String>) {
    val topScores by queue.items.collectAsState()

    LazyColumn {
        items(topScores) { score ->
            Text("${score.name}: ${score.value}")
        }
    }
}
```

### Batch Mutations with Builder

```kotlin
// Efficiently apply multiple changes without per-operation CAS overhead
val updated = queue.mutate {
    add(Task("task-1", 10))
    add(Task("task-2", 5))
    add(Task("task-3", 20))
    removeIf { it.priority > 15 }
    poll()  // Remove highest priority
}
// `updated` is a new ConcurrentPriorityQueue with all changes applied
```

### Bulk Operations and Filtering

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

---

## Architecture

### Interface Hierarchy

The library is structured following Kotlin conventions inspired by `kotlinx.collections.immutable`:

![architecture_hierarchy.png](assets/images/architecture_hierarchy.png) _A class hierarchy diagram showing the three inheritance trees described above, with clear interface/class distinction, using UML-style notation with Kotlin-themed colors (purple/violet)._

### Data Structure: Persistent Treap

The queue is backed by a persistent (immutable) **Treap** — a randomized binary search tree that combines two properties:

- **BST property** — nodes are ordered by the user-supplied comparator (left < parent < right), so in-order traversal yields sorted output.
- **Heap property** — each node carries a **random priority** (a "noise" value assigned at insertion time). A parent's priority is always greater than its children's. This single rule, powered by randomness, keeps the tree balanced in expected O(log n) depth — **without** any color bits, height counters, or complex rebalancing rules.

The key insight: **randomness replaces complexity**. Red-Black trees need 5 invariant rules and cascading recoloring. AVL trees need height tracking and up to O(log n) rotations per insert. A Treap needs only one rule — "parent priority > child priority" — enforced by a single rotation when violated. The random priorities ensure that, on average, the tree is as balanced as if the elements were inserted in random order, regardless of the actual insertion order.

#### How Insertion Works

When inserting a new element, the algorithm performs two phases:

**Phase 1 — Descent and path recording:**

The algorithm iteratively descends the tree following BST order (comparator) until it reaches a `null` position, recording the path from root to insertion point in an `ArrayList` and tracking left/right directions in a `Long` bitmask. The iterative approach guarantees constant stack usage regardless of tree size — no risk of stack overflow even for queues with millions of elements.

![treap_structural_sharing.png](assets/images/treap_structural_sharing.png)
*Step 1: Insert(15) — the algorithm descends to a null position and creates leaf 15. Then, walking the recorded path bottom-up, nodes 10, 20, 30 are copied with references to the new child (path copying). Nodes 25, 40, 50 are shared (blue) — zero allocation for them. Note: node 15 (priority 0.21) > parent 10' (priority 0.12) — heap property is violated, rotation needed (see Step 2).*

**Phase 2 — Bottom-up reconstruction with fused rotations:**

The algorithm walks the recorded path in reverse (bottom-up), reconstructing nodes with path copying. At each level it checks: does the newly inserted child have a higher priority than its parent? If yes, a **fused rotation** moves the child up — combining the node copy and rotation into a single allocation (instead of two). Rotations stop as soon as the heap property is satisfied.

![treap_rotation_after_insert.png](assets/images/treap_rotation_after_insert.png)
*Step 2: rotateLeft(10') — node 15 (priority 0.21) has higher priority than parent 10 (priority 0.12), so a left rotation makes 15 the parent and 10 becomes its left child. Now 15' (0.21) < 20' (0.45) — heap property satisfied, no more rotations needed.*

**Total allocations for insert(15):** 5 new nodes (30', 20', 15', 10'', and the leaf 15). **Shared (zero-copy):** 3 nodes (50, 25, 40). Without structural sharing, all 7 nodes of the resulting tree would require fresh allocation. Fused rotations produce the rotation output (`15'` and `10''`) in a single allocation step — a naive implementation would first copy the parent, then rotate, creating an intermediate node that is immediately discarded.

> **Scaling perspective:** In this small tree (6 nodes), structural sharing saves just 1 allocation. But in a tree with 10,000 nodes, an insert copies only ~13 nodes (log₂ 10,000) while sharing the remaining ~9,987 — a **99.87% reduction** in allocation.

#### Internal Data Structures

Each version of the queue (each immutable snapshot) consists of **two synchronized persistent structures**:

1. **Treap** (`Node<T, K>`) — A binary tree where each node stores the element, its key, a random priority, left/right child references, and a **subtree size** counter. The subtree size enables O(log n) indexed access (`get(index)`) without traversing the entire tree. In-order traversal yields elements in sorted (comparator) order.

2. **PersistentHashMap** (`PersistentMap<K, T>` from `kotlinx.collections.immutable`) — A Hash Array Mapped Trie (HAMT) that maps keys to elements, providing **O(1) key lookup**. This powers `contains(key)`, `get(key)`, and the upsert check: *"does this key already exist, and is the new element strictly better by comparator?"* Without this map, key lookup would require O(log n) tree traversal.

Both structures are fully immutable. Every mutation produces a new Treap root **and** a new PersistentMap, wrapped together in a new `TreapPriorityList` instance. The `ConcurrentPriorityQueue` then atomically swaps this instance into its `MutableStateFlow` via CAS.

**How the upsert works (step by step):**
1. Look up the key in the PersistentMap — O(1).
2. If the key exists and the new element is NOT strictly better by comparator → **reject** (return `this`, zero allocation).
3. If the key exists and the new element IS better → **remove** the old element from both Treap and Map, then insert the new one.
4. If the key doesn't exist → **insert** directly.

**Priority generation:** Each node's treap priority is a fully random `Long` generated by `Random.nextLong()` at insertion time. Pure randomness is critical — it ensures expected O(log n) tree depth regardless of the actual insertion order or element type. Using `hashCode()` as part of the priority would be a mistake: for types like `Int` (where `hashCode() == value`), the priority would correlate with comparator order, producing degenerate O(n)-depth trees instead of balanced O(log n) ones.

**Why Treap over Red-Black Tree or AVL?**

| Property | Treap | Red-Black Tree | AVL Tree |
|----------|-------|----------------|----------|
| Balance guarantee | Expected O(log n) | Worst-case O(log n) | Worst-case O(log n) |
| Rebalancing complexity | Single rotation per level | 5 color rules, cascading recoloring | Height tracking, double rotations |
| Persistent-friendly | Excellent (path-copy only) | Difficult (recoloring propagates up) | Moderate (height updates propagate) |
| Nodes touched per insert | O(log n) path only | O(log n) + recoloring ancestors | O(log n) + height updates |
| Implementation size | ~100 lines | ~300+ lines | ~200+ lines |

The treap's simplicity makes it ideal for persistent data structures where every mutation produces a new root. Unlike Red-Black trees, treap rotations are local and never cascade, which means persistent copies touch minimal nodes. The random priority ("noise") is the secret — it provides probabilistic balance that is indistinguishable from optimal in practice, with none of the implementation complexity.

#### Bounded Eviction

When `maxSize` is set, the queue automatically manages capacity:

1. **Fast-path rejection:** If the queue is full and the new element's key doesn't already exist, it is compared against the current worst (`last()`, O(log n) traversal to the rightmost node). If the new element is worse or equal — **rejected, zero allocation** (no path copying, no new treap version). This avoids the cost of insert + evict (2x O(log n) + allocations), which is why the benchmark shows 5x speedup over `ConcurrentSkipListSet` for repeated adds to a full queue.
2. **Insert** the new element into the treap — O(log n).
3. **Evict:** if `size > maxSize`, call `removeLast()` which walks to the rightmost node (the worst element by comparator order) and removes it — O(log n). The evicted element is returned to the caller.

### Concurrency Model

**How it works:** Each mutation reads the current immutable treap, computes a new treap with the change applied, and atomically swaps it in using CAS. If another thread modified the state between read and swap, the operation retries with the updated snapshot. Since the treap is persistent, retries only recompute the delta (O(log n) new nodes), not the entire structure.

**No locks, no blocking, no deadlocks.** Under contention, threads may retry, but they never wait for each other. This is the same pattern used by Kotlin's `MutableStateFlow.update()`.

![cas_retry_flow.png](assets/images/cas_retry_flow.png) _A sequence diagram showing three concurrent threads performing CAS operations. Thread 1 succeeds on first try, Thread 2's CAS fails because Thread 1 changed the state, so Thread 2 retries with the new state and succeeds. Thread 3 observes the final state. Arrows show the flow of read-compute-CAS-retry._

---

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
| `maxSize` | Maximum queue capacity. Elements beyond this limit are evicted. Must be > 0. |
| `comparator` | Defines priority order. The first element in sorted order has the highest priority. |
| `uniqueKeySelector` | Extracts a unique identity key from each element for deduplication. |

### Factory Methods

```kotlin
// For Comparable types
ConcurrentPriorityQueue<Int>(maxSize = 10)

// Custom comparator (element is its own key)
ConcurrentPriorityQueue<Task>(maxSize = 10, compareBy { it.priority })

// Comparable types with custom key selector
ConcurrentPriorityQueue<Score, String>(maxSize = 10) { it.id }
```

### Adding Elements

| Method | Return Type | Complexity | Description |
|--------|-------------|------------|-------------|
| `add(element)` | `T?` | O(log n) | Upsert with eviction. Returns the evicted element, or `null` if no eviction occurred (including rejections). |
| `addAll(Iterable)` | `List<T>` | O(m log n) | Add all elements. Returns a snapshot of the queue contents after processing. |
| `addAll(Sequence)` | `Int` | O(m log n) | Add all elements. Returns the number of non-eviction insertions. |
| `addAll(Flow)` | `Int` | O(m log n) | Suspending. Add all emitted elements. Returns the number of evictions caused. |

### Querying Elements

| Method | Return Type | Complexity | Description |
|--------|-------------|------------|-------------|
| `get(key)` / `[key]` | `T?` | O(1) | Retrieve element by key. |
| `first()` | `T?` | O(log n) | Peek at the highest-priority element. |
| `last()` | `T?` | O(log n) | Peek at the lowest-priority element. |
| `contains(element)` | `Boolean` | O(1) | Check if element exists (by key). |
| `containsKey(key)` | `Boolean` | O(1) | Check if key exists. |
| `isEmpty()` | `Boolean` | O(1) | Check if queue is empty. |
| `isNotEmpty()` | `Boolean` | O(1) | Check if queue has elements. |
| `size` | `Int` | O(1) | Current number of elements. |
| `items` | `StateFlow<List<T>>` | O(1) | Reactive sorted list for UI binding. |
| `iterator()` | `Iterator<T>` | O(n) | Iterate in priority order (snapshot). |

### Removing Elements

| Method | Return Type | Complexity | Description |
|--------|-------------|------------|-------------|
| `poll()` | `T?` | O(log n) | Remove and return the highest-priority element. |
| `remove(element)` | `Boolean` | O(log n) | Remove element (by key). |
| `removeByKey(key)` | `Boolean` | O(log n) | Remove element by key. |
| `removeIf { predicate }` | `Int` | O(k log n) | Remove all matching elements. Returns count removed. |
| `retainIf { predicate }` | `Int` | O(k log n) | Keep only matching elements. Returns count removed. |
| `clear()` | `Unit` | O(1) | Remove all elements. |

### Builder API

The builder allows batch modifications without CAS overhead per operation. All changes are applied to an internal mutable snapshot and committed at once via `build()`.

```kotlin
// Option 1: Explicit builder
val builder = queue.builder()
builder.add(element1)
builder.add(element2)
builder.removeByKey(oldKey)
val newQueue = builder.build()

// Option 2: Inline mutate block
val newQueue = queue.mutate {
    add(element1)
    removeIf { it.score < 10 }
    poll()
}
```

| Builder Method | Return Type | Description |
|---------------|-------------|-------------|
| `build()` | `ConcurrentPriorityQueue<T, K>` | Create a new queue from current builder state. |
| `add(element)` | `Boolean` | Add element (respects capacity and upsert). |
| `remove(element)` | `Boolean` | Remove element. |
| `removeByKey(key)` | `Boolean` | Remove by key. |
| `poll()` | `T?` | Remove and return highest-priority element. |
| `removeIf { predicate }` | `Int` | Remove matching elements. |
| `retainIf { predicate }` | `Int` | Keep matching elements. |
| `getByKey(key)` | `T?` | Retrieve element by key. |
| `containsKey(key)` | `Boolean` | Check if key exists. |
| `first()` / `last()` | `T?` | Peek at highest/lowest priority. |
| `toList()` | `List<T>` | Current elements in priority order. |
| `clear()` | `Unit` | Remove all elements. |

### Persistent Priority List API

For use cases that don't need concurrency, the library also exposes the underlying persistent data structure directly:

```kotlin
val list = persistentPriorityListOf(
    comparator = compareBy<Task> { it.priority },
    keySelector = { it.id }
)

val list2 = list.add(Task("a", 10))  // Returns a new list; `list` is unchanged
val list3 = list2.add(Task("b", 5))

// Builder for batch modifications
val list4 = buildPersistentPriorityList(compareBy { it.priority }, { it.id }) {
    add(Task("x", 1))
    add(Task("y", 2))
    add(Task("z", 3))
}
```

---

## Performance

### Benchmark: ConcurrentPriorityQueue vs. ConcurrentSkipListSet

**Environment:** Windows 11, OpenJDK 21, Kotlin 2.3.10

#### addAll Throughput (100M operations)

Repeated `addAll` on a bounded queue (capacity 100,000) with 100,000 unique elements × 1,000 iterations, compared against `java.util.concurrent.ConcurrentSkipListSet` receiving identical input.

| Metric | ConcurrentPriorityQueue | ConcurrentSkipListSet |
|--------|------------------------|-----------------------|
| Total time (100M ops) | **10.07 s** | 50.69 s |
| Throughput | **9.93M ops/sec** | 1.97M ops/sec |
| Speedup | **5.03x faster** | baseline |

![benchmark_addall_throughput.png](assets/images/benchmark_addall_throughput.png)
*addAll throughput comparison: ConcurrentPriorityQueue vs ConcurrentSkipListSet — 100M operations on 100K-capacity queue.*

#### Insert Throughput by Queue Size (1M inserts each)

| Queue Capacity | Throughput | Expected Tree Depth |
|---------------|------------|---------------------|
| 100 | 20,408,163 ops/sec | ~7 |
| 1,000 | 9,523,810 ops/sec | ~10 |
| 10,000 | 193,125 ops/sec | ~13 |
| 100,000 | 1,890 ops/sec | ~17 |

Note: `ConcurrentSkipListSet` cannot be bounded — at 100,000 capacity with 1M inserts it grows to 1M elements, consuming unbounded memory. CPQ maintains exactly 100,000 elements throughout via automatic eviction.

![benchmark_throughput_by_size.png](assets/images/benchmark_throughput_by_size.png)
*Insert throughput scaling: as queue capacity grows from 100 to 100K, each insert touches more nodes (log₂ n path copies), reducing throughput. The logarithmic x-axis highlights the O(log n) relationship.*

#### Concurrent Insert Throughput

32 coroutines on `Dispatchers.Default`, each inserting 100,000 random integers into a queue of capacity 10,000:

| Metric | Value |
|--------|-------|
| Total operations | 3,200,000 |
| Total time | 8.51 s |
| Throughput | **376,117 concurrent ops/sec** |

> **Why is CPQ faster in addAll?** The bounded capacity is the key advantage. Once the queue is full, every subsequent `add` of a duplicate or worse-priority element is rejected via fast-path checks — O(log n) comparison against the worst element, zero allocation. `ConcurrentSkipListSet` has no capacity limit and must traverse and compare against its full skip-list structure on every call. Structural sharing means that when an insert does modify the treap, only ~17 nodes (log₂ 100,000) are allocated; all other nodes are reused. The fused insert+rotate optimization further reduces allocations by eliminating intermediate node copies during rotations.
>
> Both implementations produce identical sorted output for the same input, validated at the end of each benchmark run.

### Complexity Comparison

| Operation | ConcurrentPriorityQueue | ConcurrentSkipListSet | java.util.PriorityQueue | synchronized List + sort |
|-----------|------------------------|----------------------|------------------------|-------------------------|
| `add()` | O(log n) | O(log n) | O(log n) | O(n log n) |
| `poll()` / `first()` | O(log n) | O(1) | O(log n) | O(n log n) |
| `contains(key)` | **O(1)** | O(log n) | O(n) | O(n) |
| `remove(key)` | O(log n) | O(log n) | O(n) | O(n) |
| Bounded eviction | **O(log n) automatic** | O(n) manual | O(n) manual | O(n log n) manual |
| Concurrent safety | Lock-free CAS | Lock-free | Not thread-safe | Blocking (synchronized) |
| Memory per mutation | O(log n) new nodes | O(1) in-place | O(1) in-place | O(n) full copy |

### Feature Comparison

| Feature | ConcurrentPriorityQueue | ConcurrentSkipListSet | PriorityQueue | Channel |
|---------|------------------------|-----------------------|---------------|---------|
| Thread-safe | Lock-free (CAS) | Lock-free | No | Yes (suspend) |
| Bounded capacity | Built-in | Manual | Manual | Built-in |
| Deduplication (upsert) | Built-in | Set semantics (no update) | No | No |
| Priority updates | Automatic (better wins) | Remove + re-add | Remove + re-add | N/A |
| Reactive (`StateFlow`) | Native | No | No | Receive only |
| Kotlin Multiplatform | All targets | JVM only | JVM only | All targets |
| Key-based O(1) lookup | Yes | No | No | No |
| Snapshot iteration | Yes (immutable) | Weakly consistent | Fail-fast | N/A |
| Builder for batch ops | Yes | No | No | No |
| Persistent (copy-on-write) | Yes | No | No | No |

---

## Comparison with Alternatives

### vs. `java.util.PriorityQueue`

Java's `PriorityQueue` is a classic binary heap. It provides O(log n) insertion and removal but has critical limitations:

- **Not thread-safe.** Concurrent access causes `ConcurrentModificationException` or silent corruption.
- **No bounded capacity.** You must manually check size and remove excess elements.
- **No deduplication.** Duplicate elements are stored separately; there is no "update if better" mechanism.
- **O(n) key lookup.** `contains()` and `remove()` scan the entire heap.
- **JVM only.** Not available on Kotlin/Native, JS, or Wasm targets.

Use `PriorityQueue` when you need a simple, single-threaded heap on JVM with no size limit.

### vs. `java.util.concurrent.ConcurrentSkipListSet`

`ConcurrentSkipListSet` is a lock-free sorted set backed by a skip list. It is the closest JVM standard library alternative:

- **No bounded capacity.** The set grows unbounded. You must manually trim it after every insertion — which introduces a race condition between "add" and "trim" unless you wrap both in a lock (defeating the purpose of lock-free).
- **No upsert.** Since it is a `Set`, elements with the same natural ordering are deduplicated, but you cannot update an existing element's priority without removing and re-adding it — again introducing a race window.
- **No reactive state.** There is no built-in way to observe changes. You must poll or wrap it in a `Flow`.
- **JVM only.** Not available on Kotlin/Native, JS, or Wasm.
- **O(log n) key lookup.** Compared to ConcurrentPriorityQueue's O(1) hash-map-backed lookup.

Use `ConcurrentSkipListSet` when you need an unbounded concurrent sorted set on JVM without priority updates or capacity limits.

### vs. `Collections.synchronizedList` + `sort()`

A common ad-hoc approach: wrap a mutable list in `synchronizedList` and call `sort()` after every modification.

- **O(n log n) per mutation.** Every insert requires a full re-sort.
- **Blocking.** `synchronized` locks the entire list for every read and write.
- **No structural sharing.** Every sort produces a new ordering of the entire list.
- **No deduplication.** Must be implemented manually with an additional `Set`.

This approach is acceptable for very small lists (< 50 elements) where simplicity outweighs performance. For anything larger, dedicated data structures are significantly faster.

### vs. Kotlin `Channel` with Priority

Kotlin Channels can be used as concurrent queues, but they are designed for producer-consumer communication, not priority ordering:

- **No priority ordering.** `Channel` is FIFO (first-in-first-out). To simulate priority, you must sort externally.
- **No random access.** You cannot peek, query by key, or iterate without consuming.
- **No deduplication.** Duplicate elements flow through the channel independently.
- **Suspending only.** Send and receive are suspending functions; there is no non-suspending "try-add" that returns the evicted element.

Use Channels for producer-consumer workflows. Use `ConcurrentPriorityQueue` when you need a queryable, sorted, bounded collection.

---

## Edge-Device Vector Search: Replacing a Vector Database

One of the most powerful — and non-obvious — applications of `ConcurrentPriorityQueue` is as a lightweight **vector similarity search engine** that runs entirely on the user's device, eliminating the need for a backend vector database.

### The Idea

Traditional vector databases (Pinecone, Weaviate, Milvus, Qdrant) are designed for server-side workloads: millions of high-dimensional dense vectors, indexed with HNSW or IVF, queried over the network. But many real-world use cases involve **small-to-medium datasets** (hundreds to tens of thousands of items) where network latency, backend infrastructure, and cost are unnecessary overhead.

`ConcurrentPriorityQueue` turns this pattern on its head:

1. **Load embeddings** (sparse or dense vectors) into the queue as elements.
2. **Set the comparator** to a lambda that computes `dotProduct(queryVector, candidateVector)` in descending order.
3. **Bound the queue** to `maxSize = K` (e.g., 10) — the queue automatically maintains only the top-K nearest neighbors.
4. **Deduplicate by key** — each vector is identified by a unique key (document ID, word, entity), so updates replace stale embeddings without duplicates.

```kotlin
data class Embedding(val id: String, val vector: FloatArray, val score: Float)

// Query vector computed from user input
val queryVector: FloatArray = computeEmbedding(userInput)

val topK = ConcurrentPriorityQueue<Embedding, String>(
    maxSize = 10,
    comparator = compareByDescending { it.score },
    uniqueKeySelector = { it.id }
)

// Add all candidate embeddings — only the top 10 by dot product survive
for (doc in documents) {
    val score = dotProduct(queryVector, doc.vector)
    topK.add(Embedding(doc.id, doc.vector, score))
}

// topK.items.value now contains the 10 nearest neighbors
```

### Why This Works

| Aspect | Backend Vector DB | ConcurrentPriorityQueue |
|--------|-------------------|------------------------|
| Latency | Network round-trip (50–200 ms) | In-memory, zero latency |
| Infrastructure | Requires server, scaling, ops | Runs on the device — no backend |
| Cost | Per-query pricing or hosting | Zero — ships with the app |
| Dynamic updates | Requires re-indexing pipeline | `add()` / `removeByKey()` in O(log n) |
| Deduplication | Manual or schema-dependent | Built-in upsert by key |
| Top-K retrieval | Built-in (ANN algorithms) | Built-in (bounded capacity) |
| Thread safety | Server handles concurrency | Lock-free CAS on device |
| Offline support | Requires connectivity | Fully offline |
| Dataset size sweet spot | Millions+ vectors | Hundreds to ~50K vectors |

For datasets in the hundreds-to-thousands range — country lists, product catalogs, contact lists, vocabulary dictionaries, UI element labels — a bounded priority queue with a dot-product comparator is faster, simpler, and requires no infrastructure.

### Real-World Example: Typeahead KMP

This pattern is not theoretical. It is the foundation of [**Typeahead KMP**](https://github.com/karloti/typeahead-kmp) — an open-source, lock-free fuzzy search engine for Kotlin Multiplatform built by the same author.

**Typeahead KMP** uses `ConcurrentPriorityQueue` internally as its core ranking engine:

1. Each searchable item (e.g., a country name) is tokenized into an **L2-normalized sparse vector** using N-grams, skip-grams, and positional anchors.
2. When the user types a query (keystroke by keystroke), the query is also converted into a sparse vector.
3. The engine computes the **dot product** (cosine similarity) between the query vector and every candidate vector.
4. Results are inserted into a `ConcurrentPriorityQueue` with `maxSize = K`, so only the top-K best matches survive — automatically, with no manual sorting or trimming.
5. Because the queue exposes `StateFlow<List<T>>`, the UI reactively updates on every keystroke.

#### The Country Selector Problem

Imagine a UI where the user must select a country from a list of **250+ countries in 32 languages** — over 8,000 searchable strings. The user types "Bgularia" (a typo for "Bulgaria"). A naive prefix search returns nothing. A Levenshtein-based search is too slow for per-keystroke updates on mobile.

With Typeahead KMP + ConcurrentPriorityQueue:

```
Keystroke: "B"     → [Brazil, Bulgaria, Bahamas, Belgium, Benin, ...]
Keystroke: "Bg"    → [Bulgaria, Bangladesh, Belgium, ...]  (fuzzy prefix recovery)
Keystroke: "Bgul"  → [Bulgaria 0.91, Portugal 0.34, ...]   (skip-gram momentum)
Keystroke: "Bgula" → [Bulgaria 0.89, Angola 0.21, ...]     (top-K converging)
Keystroke: "Bgular"→ [Bulgaria 0.93, ...]                  (lock on target)
```

Each keystroke triggers a dot-product computation across all candidates, and the bounded queue instantly discards anything outside the top-K — all in **O(K)** time per candidate, with **zero memory allocation**, running entirely on the user's device.

#### Architecture: How Typeahead KMP Uses ConcurrentPriorityQueue

![typeahead_architecture.png](assets/images/typeahead_architecture.png) _This architecture — sparse vectors + bounded priority queue + reactive state — transforms what would traditionally require a backend vector database into a **pure client-side solution** that works offline, across all Kotlin Multiplatform targets, with zero infrastructure cost._

> **See it in action:** [github.com/karloti/typeahead-kmp](https://github.com/karloti/typeahead-kmp)

---

## Thread Safety Guarantees

All public methods of `ConcurrentPriorityQueue` are safe to call concurrently from any number of coroutines or threads. The implementation provides the following guarantees:

1. **Linearizability.** Each `MutableStateFlow.update()` call is atomic — the queue transitions from one valid state to another with no intermediate states visible to other threads.
2. **No lost updates.** CAS retries ensure that concurrent modifications are never silently dropped.
3. **Snapshot isolation.** `items.value` and `iterator()` return immutable snapshots. Iterating a snapshot while another thread mutates the queue is safe and well-defined.
4. **No deadlocks or livelocks.** The lock-free design guarantees progress. Under extreme contention, individual operations may retry, but the system as a whole always makes forward progress (lock-freedom).

### Verified by Tests

The library includes **10 dedicated concurrency tests** that exercise the queue under real multi-threaded conditions (`Dispatchers.Default`):

| Test Scenario | Coroutines | Operations |
|---------------|-----------|------------|
| Concurrent adds | 20 | 2,000 |
| Concurrent upserts (overlapping keys) | 10 | 1,000 |
| Concurrent add + remove | 20 | 1,000 |
| Concurrent add + poll | 15 | 1,500 |
| Concurrent add + removeIf | 8 | 240 |
| Concurrent add + clear | 13 | 1,060 |
| Concurrent add + removeByKey | 15 | 150 |
| Concurrent add + retainIf | 8 | 530 |
| Mixed mutations (all operations) | 16 | 338 |
| Snapshot isolation under mutations | 10 | 1,500 |

Every test validates structural invariants after execution: capacity bounds, key uniqueness, sort order, and containsKey/get consistency.

---

## Memory Model

### Structural Sharing

When a treap with 10,000 nodes is modified, only the nodes along the path from root to the affected leaf are copied. All other nodes are shared between the old and new versions.

```
Nodes in tree:     10,000
Path length:       ~13 (log2 10,000)
New nodes per op:  ~13
Shared nodes:      ~9,987  (99.87%)
```

This means:

- **Memory per mutation:** O(log n) new objects. Measured at **~11 bytes per insert** on a full queue of 10,000 elements (1M inserts, JVM with GC). The low measured value reflects that most inserts to a full queue are rejected via fast-path (zero allocation) — only inserts that actually modify the tree allocate ~13 nodes.
- **Garbage collection friendly:** Old versions are reclaimed when no longer referenced. The persistent structure naturally supports GC since there are no cyclic references. Measured retained memory after 1M inserts + GC: **190 KB** — all intermediate versions are fully reclaimed.
- **CAS retry cost:** If a CAS fails, the retry recomputes only O(log n) nodes — not the entire structure.

![memory_structural_sharing.png](assets/images/memory_structural_sharing.png)
*Memory profile during 1M inserts into a full 10K queue: peak allocation is minimal, and GC reclaims all intermediate versions — retained delta near zero.*

### Memory Overhead vs. Mutable Collections

| Aspect | ConcurrentPriorityQueue | Mutable List + Lock |
|--------|------------------------|---------------------|
| Per-element overhead | ~48 bytes (node + key entry) | ~16 bytes (array slot) |
| Per-mutation allocation | O(log n) nodes | O(1) amortized |
| Snapshot cost | O(1) — just read the reference | O(n) — must copy entire list |
| Concurrent reader cost | Zero (no synchronization) | Must acquire lock |

The higher per-element overhead is the trade-off for lock-free concurrency and O(1) snapshots. For typical queue sizes (hundreds to low thousands), this overhead is negligible.

---

## Test Coverage

The library has **60 tests** across 3 test suites, all passing on JVM, JS, Native (Windows/Linux/macOS), and WebAssembly:

| Suite | Tests | Coverage |
|-------|-------|----------|
| **Unit Tests** | 45 | All public methods: add, poll, remove, removeByKey, removeIf, retainIf, clear, contains, containsKey, get, first, last, isEmpty, isNotEmpty, size, items, iterator, addAll (Iterable/Sequence/Flow), factory methods, edge cases (maxSize=0, empty queue, eviction, upsert, re-entry after eviction) |
| **Concurrency Tests** | 10 | Thread-safety under contention: concurrent adds, upserts, removes, polls, removeIf, retainIf, clear, removeByKey, mixed mutations, snapshot isolation |
| **JVM Advanced Tests** | 5 | addAll functional correctness with ConcurrentSkipListSet reference comparison; 100M-operation throughput benchmark vs SkipListSet (capacity 100K); insert throughput scaling by queue size (100 → 100,000); memory allocation per insert (1M inserts); concurrent insert throughput (32 coroutines, 3.2M operations, 376K ops/sec) |

---

## Supported Platforms

| Platform | Targets |
|----------|---------|
| **JVM** | `jvm` |
| **Android** | `android` (minSdk 24, compileSdk 36) |
| **iOS** | `iosX64`, `iosArm64`, `iosSimulatorArm64` |
| **macOS** | `macosX64`, `macosArm64` |
| **Linux** | `linuxX64`, `linuxArm64` |
| **Windows** | `mingwX64` |
| **watchOS** | `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, `watchosX64`, `watchosSimulatorArm64` |
| **tvOS** | `tvosX64`, `tvosArm64`, `tvosSimulatorArm64` |
| **JavaScript** | `js` (browser) |
| **WebAssembly** | `wasmJs` (browser, Node.js), `wasmWasi` (Node.js) |
| **Android Native** | `androidNativeArm32`, `androidNativeArm64`, `androidNativeX86`, `androidNativeX64` |

---

## Dependencies

| Library | Scope | Purpose |
|---------|-------|---------|
| [`kotlinx-coroutines-core`](https://github.com/Kotlin/kotlinx.coroutines) | `api` | `MutableStateFlow`, `StateFlow`, `Flow` for reactive state and CAS updates |
| [`kotlinx-collections-immutable`](https://github.com/Kotlin/kotlinx.collections.immutable) | `implementation` | `PersistentMap` (HAMT) for O(1) key lookups inside the treap |

---

## Use Cases

- **Leaderboards and rankings** — Maintain a bounded top-K list that updates in real time as scores arrive from multiple sources.
- **Task schedulers** — Priority-based job queues where tasks can be re-prioritized without remove-and-reinsert.
- **Search result aggregation** — Merge results from multiple concurrent data sources, deduplicating by document ID and keeping the highest-relevance matches.
- **Rate limiting / top-N filtering** — Keep only the N most important events from a high-volume stream.
- **Real-time dashboards** — Expose `StateFlow<List<T>>` directly to Jetpack Compose, SwiftUI, or React for live-updating sorted views.
- **IoT sensor aggregation** — Collect readings from many devices, keep only the most recent/important per device (upsert by device ID).
- **Multiplayer game state** — Track player scores or positions across threads without locking.
- **Edge-device vector search** — Replace a backend vector database for small-to-medium datasets. Load sparse/dense embeddings, use a dot-product comparator, and get top-K nearest neighbors with zero network latency. See [Typeahead KMP](https://github.com/karloti/typeahead-kmp) for a production example.

---

## Tracking & Roadmap

Issue tracking: [Concurrent Priority Queue - YouTrack](https://smartcoding.youtrack.cloud/projects/CPQ)

---

## License

```
Copyright 2026 Kaloyan Karaivanov

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
