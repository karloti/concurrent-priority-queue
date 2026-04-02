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

import io.github.karloti.cpq.ConcurrentPriorityQueue
import io.github.karloti.cpq.ConcurrentPriorityQueueBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.comparisons.naturalOrder
import kotlin.comparisons.reverseOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Demonstrates every syntactic way to instantiate [ConcurrentPriorityQueue] using
 * **positional arguments only** (no named parameters).
 *
 * Tests are grouped by instantiation category:
 * - **Group A** — Comparable invoke (`T: Comparable<T>`), progressively more positional args
 * - **Group B** — Generic `<T, K>` invoke / primary constructor, progressively more positional args
 * - **Group C** — Explicit companion `.invoke<>()` calls
 * - **Group D** — Bounded generic type parameters in helper functions
 *
 * Parameter order reference:
 * ```
 * // Comparable invoke  (T: Comparable<T>, K = T)
 * ConcurrentPriorityQueue<T>(maxSize, comparator, dispatcher, keySelector)
 *                            pos 1    pos 2       pos 3       pos 4 (trailing λ)
 *
 * // Generic invoke / primary constructor  (separate T and K)
 * ConcurrentPriorityQueue<T,K>(comparator, maxSize, dispatcher, keySelector)
 *                              pos 1       pos 2    pos 3       pos 4 (trailing λ)
 * ```
 */
class ConcurrentPriorityQueueInstantiationTest {

    /** Non-comparable element type with separate key and score fields. */
    private data class Item(val id: Int, val name: String, val score: Int)

    /** Custom comparable type keyed and sorted by its [value] field. */
    private data class Score(val value: Int) : Comparable<Score> {
        override fun compareTo(other: Score) = value.compareTo(other.value)
    }

    // =========================================================================
    // Group A — Comparable invoke (T: Comparable<T>), positional combinations
    // =========================================================================

    /**
     * `ConcurrentPriorityQueue<Int>()` — all defaults.
     * Resolves to the Comparable invoke: maxSize=5, ascending comparator, keySelector={it}.
     */
    @Test
    fun comparableInvokeInt_noArgs() = runTest {
        val queue = ConcurrentPriorityQueue<Int>()
        queue.add(3); queue.add(1); queue.add(2)
        assertEquals(3, queue.size)
        assertEquals(1, queue.first())
    }

    /**
     * `ConcurrentPriorityQueue<Int>(3)` — 1 positional arg: maxSize.
     * Capacity is enforced; the smallest 3 of 4 values are kept.
     */
    @Test
    fun comparableInvokeInt_maxSize() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(3)
        queue.add(10); queue.add(20); queue.add(30); queue.add(40)
        assertEquals(3, queue.size)
        assertEquals(10, queue.first())
    }

    /**
     * `ConcurrentPriorityQueue<Int>(3, reverseOrder())` — 2 positional args: maxSize, comparator.
     * Descending order: the largest 3 of 4 values are kept.
     */
    @Test
    fun comparableInvokeInt_maxSize_comparator() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(3, reverseOrder())
        queue.add(10); queue.add(20); queue.add(30); queue.add(40)
        assertEquals(3, queue.size)
        assertEquals(40, queue.first())
    }

    /**
     * `ConcurrentPriorityQueue<Int>(3, reverseOrder(), Dispatchers.Default)` — 3 positional args.
     * Same as above with an explicit dispatcher at position 3.
     */
    @Test
    fun comparableInvokeInt_maxSize_comparator_dispatcher() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(3, reverseOrder(), Dispatchers.Default)
        queue.add(5); queue.add(15); queue.add(25); queue.add(35)
        assertEquals(3, queue.size)
        assertEquals(35, queue.first())
    }

    /**
     * `ConcurrentPriorityQueue<Int>(3, reverseOrder(), Dispatchers.Default) { it }` — all 4 positional args.
     * The trailing lambda provides the `keySelector` at position 4.
     */
    @Test
    fun comparableInvokeInt_allPositional() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(3, reverseOrder(), Dispatchers.Default) { it }
        queue.add(5); queue.add(15); queue.add(25); queue.add(35)
        assertEquals(3, queue.size)
        assertEquals(35, queue.first())
    }

    /**
     * `ConcurrentPriorityQueue<String>()` — all defaults with `String`.
     * Natural lexicographic ascending order; "apple" sorts first.
     */
    @Test
    fun comparableInvokeString_noArgs() = runTest {
        val queue = ConcurrentPriorityQueue<String>()
        queue.add("banana"); queue.add("apple"); queue.add("cherry")
        assertEquals(3, queue.size)
        assertEquals("apple", queue.first())
    }

    /**
     * `ConcurrentPriorityQueue<String>(2, reverseOrder())` — 2 positional args with `String`.
     * Keeps the 2 lexicographically largest strings.
     */
    @Test
    fun comparableInvokeString_maxSize_comparator() = runTest {
        val queue = ConcurrentPriorityQueue<String>(2, reverseOrder())
        queue.add("banana"); queue.add("apple"); queue.add("cherry")
        assertEquals(2, queue.size)
        assertEquals("cherry", queue.first())
        assertEquals(listOf("cherry", "banana"), queue.items.value)
    }

    /**
     * `ConcurrentPriorityQueue<Score>()` — all defaults with a custom `Comparable` type.
     * Ascending by `value`; Score(5) sorts first.
     */
    @Test
    fun comparableInvokeScore_noArgs() = runTest {
        val queue = ConcurrentPriorityQueue<Score>()
        queue.add(Score(10)); queue.add(Score(5)); queue.add(Score(20))
        assertEquals(Score(5), queue.first())
    }

    /**
     * `ConcurrentPriorityQueue<Score>(2, reverseOrder())` — 2 positional args with custom type.
     * Keeps the 2 highest-value Score instances.
     */
    @Test
    fun comparableInvokeScore_maxSize_comparator() = runTest {
        val queue = ConcurrentPriorityQueue<Score>(2, reverseOrder())
        queue.add(Score(10)); queue.add(Score(5)); queue.add(Score(20))
        assertEquals(2, queue.size)
        assertEquals(Score(20), queue.first())
    }

    /**
     * `ConcurrentPriorityQueue<Double>(5, naturalOrder())` — explicit `naturalOrder()` comparator.
     * Ascending doubles; 1.0 has the highest priority.
     */
    @Test
    fun comparableInvokeDouble_naturalOrder() = runTest {
        val queue = ConcurrentPriorityQueue<Double>(5, naturalOrder())
        queue.add(3.14); queue.add(1.0); queue.add(2.72)
        assertEquals(1.0, queue.first())
    }

    // =========================================================================
    // Group B — Generic <T, K>, all positional combinations
    // =========================================================================

    /**
     * `ConcurrentPriorityQueue(compareByDescending<Item> { it.score }) { it.id }` — 2 positions.
     * Comparator at pos 1, `keySelector` as trailing lambda at pos 4; maxSize defaults to 5.
     */
    @Test
    fun genericInvoke_comparatorAndTrailingLambda() = runTest {
        val queue = ConcurrentPriorityQueue(compareByDescending<Item> { it.score }) { it.id }
        queue.add(Item(1, "Alpha", 100))
        queue.add(Item(2, "Beta", 50))
        assertEquals(100, queue.first()?.score)
        assertEquals(1, queue.first()?.id)
    }

    /**
     * `ConcurrentPriorityQueue(compareByDescending<Item> { it.score }, 3) { it.id }` — 3 positions.
     * Adds maxSize=3 at position 2; only 3 of 4 added items are retained.
     */
    @Test
    fun genericInvoke_comparator_maxSize() = runTest {
        val queue = ConcurrentPriorityQueue(compareByDescending<Item> { it.score }, 3) { it.id }
        queue.add(Item(1, "A", 10))
        queue.add(Item(2, "B", 40))
        queue.add(Item(3, "C", 30))
        queue.add(Item(4, "D", 20))
        assertEquals(3, queue.size)
        assertEquals(40, queue.first()?.score)
    }

    /**
     * `ConcurrentPriorityQueue(compareByDescending<Item> { it.score }, 3, Dispatchers.Default) { it.id }` — all 4 positions.
     * Explicit dispatcher at position 3.
     */
    @Test
    fun genericInvoke_allFourPositional() = runTest {
        val queue = ConcurrentPriorityQueue(
            compareByDescending<Item> { it.score }, 3, Dispatchers.Default
        ) { it.id }
        queue.add(Item(1, "A", 10))
        queue.add(Item(2, "B", 40))
        queue.add(Item(3, "C", 30))
        queue.add(Item(4, "D", 20))
        assertEquals(3, queue.size)
        assertEquals(40, queue.first()?.score)
    }

    /**
     * `ConcurrentPriorityQueue(compareByDescending<Item> { it.score }) { it.name }` — key type is `String`.
     * `K = String`; elements are identified by their `name` field.
     */
    @Test
    fun genericInvoke_stringKey() = runTest {
        val queue = ConcurrentPriorityQueue(compareByDescending<Item> { it.score }) { it.name }
        queue.add(Item(1, "Alpha", 100))
        queue.add(Item(2, "Beta", 50))
        assertTrue(queue.containsKey("Alpha"))
        assertTrue(queue.containsKey("Beta"))
    }

    /**
     * `ConcurrentPriorityQueue<Item, Int>(compareByDescending { it.score }) { it.id }` — explicit `<T, K>` type args.
     * Type arguments written explicitly at the call site; trailing lambda for keySelector.
     */
    @Test
    fun genericInvoke_explicitTypeArgs() = runTest {
        val queue = ConcurrentPriorityQueue<Item, Int>(compareByDescending { it.score }) { it.id }
        queue.add(Item(7, "Gamma", 77))
        assertEquals(1, queue.size)
        assertEquals(7, queue.first()?.id)
    }

    /**
     * `ConcurrentPriorityQueue(compareBy<Item> { it.score }, 2, Dispatchers.Default) { it.id }` — ascending min-heap.
     * Keeps the 2 items with the lowest score.
     */
    @Test
    fun genericInvoke_ascendingMinHeap() = runTest {
        val queue = ConcurrentPriorityQueue(compareBy<Item> { it.score }, 2, Dispatchers.Default) { it.id }
        queue.add(Item(1, "A", 5))
        queue.add(Item(2, "B", 10))
        queue.add(Item(3, "C", 1))
        assertEquals(2, queue.size)
        assertEquals(1, queue.first()?.score)
    }

    // =========================================================================
    // Group C — Explicit companion .invoke<>() calls
    // =========================================================================

    /**
     * `ConcurrentPriorityQueue.invoke<Item, Int>(compareByDescending { it.score }) { it.id }` — explicit generic companion invoke.
     * Directly references the companion's `invoke` operator with both type parameters specified.
     */
    @Test
    fun companionInvoke_genericTK() = runTest {
        val queue = ConcurrentPriorityQueue.invoke<Item, Int>(compareByDescending { it.score }) { it.id }
        queue.add(Item(1, "Alpha", 100))
        queue.add(Item(2, "Beta", 50))
        assertEquals(1, queue.first()?.id)
        assertEquals(100, queue.first()?.score)
    }

    /**
     * `ConcurrentPriorityQueue.invoke<Int>()` — explicit Comparable companion invoke, zero args.
     * All defaults: maxSize=5, ascending `compareBy { it }`, `keySelector = { it }`.
     */
    @Test
    fun companionInvoke_comparable_noArgs() = runTest {
        val queue = ConcurrentPriorityQueue.invoke<Int>()
        queue.add(3); queue.add(1); queue.add(2)
        assertEquals(3, queue.size)
        assertEquals(1, queue.first())
    }

    /**
     * `ConcurrentPriorityQueue.invoke<Int>(5, reverseOrder())` — 2 positional args via explicit invoke.
     * maxSize=5 at pos 1, `reverseOrder()` at pos 2; largest Int has highest priority.
     */
    @Test
    fun companionInvoke_comparable_maxSize_comparator() = runTest {
        val queue = ConcurrentPriorityQueue.invoke<Int>(5, reverseOrder())
        queue.add(10); queue.add(20); queue.add(30)
        assertEquals(30, queue.first())
    }

    /**
     * `ConcurrentPriorityQueue.invoke<Int>(3, reverseOrder(), Dispatchers.Default)` — all 3 non-lambda positional args.
     * Explicit companion invoke with maxSize, comparator, and dispatcher all positional.
     */
    @Test
    fun companionInvoke_comparable_allThreeNonLambda() = runTest {
        val queue = ConcurrentPriorityQueue.invoke<Int>(3, reverseOrder(), Dispatchers.Default)
        queue.add(10); queue.add(20); queue.add(30); queue.add(40)
        assertEquals(3, queue.size)
        assertEquals(40, queue.first())
    }

    // =========================================================================
    // Group D — Bounded generic type parameters
    // =========================================================================

    /**
     * Creates an ascending queue for any [Comparable] type using 1 positional arg (maxSize).
     * The `ConcurrentPriorityQueue(maxSize)` call resolves to the Comparable invoke.
     */
    private inline fun <reified C : Comparable<C>> ascendingQueue(maxSize: Int): ConcurrentPriorityQueue<C, C> =
        ConcurrentPriorityQueue<C>(maxSize)

    /**
     * Creates a descending queue for any [Comparable] type using 2 positional args.
     * Must be `inline reified` to forward the type argument to the Comparable invoke.
     */
    private inline fun <reified C : Comparable<C>> descendingQueue(): ConcurrentPriorityQueue<C, C> =
        ConcurrentPriorityQueue<C>(5, reverseOrder())

    /**
     * Demonstrates [ascendingQueue] called with `Int`, `String`, and `Score`.
     * Shows that a single helper function works for any bounded generic type.
     */
    @Test
    fun boundedGenericHelper_ascendingQueue() = runTest {
        val intQueue = ascendingQueue<Int>(3)
        intQueue.add(3); intQueue.add(1); intQueue.add(2)
        assertEquals(1, intQueue.first())

        val stringQueue = ascendingQueue<String>(3)
        stringQueue.add("z"); stringQueue.add("a"); stringQueue.add("m")
        assertEquals("a", stringQueue.first())

        val scoreQueue = ascendingQueue<Score>(3)
        scoreQueue.add(Score(30)); scoreQueue.add(Score(10)); scoreQueue.add(Score(20))
        assertEquals(Score(10), scoreQueue.first())
    }

    /**
     * Demonstrates [descendingQueue] called with `Int` and `String`.
     * The `reified` constraint lets the inline helper forward `C` to the Comparable invoke.
     */
    @Test
    fun boundedGenericHelper_descendingQueue() = runTest {
        val intQueue = descendingQueue<Int>()
        intQueue.add(10); intQueue.add(30); intQueue.add(20)
        assertEquals(30, intQueue.first())

        val stringQueue = descendingQueue<String>()
        stringQueue.add("apple"); stringQueue.add("cherry"); stringQueue.add("banana")
        assertEquals("cherry", stringQueue.first())
    }

    // =========================================================================
    // Group E — builder() and mutate() instantiation paths
    //
    // Both methods return a brand-new ConcurrentPriorityQueue instance.
    // builder() gives step-by-step control; mutate() is a concise DSL shorthand.
    // Neither requires a comparator or keySelector at the call site — they are
    // inherited from the source queue's configuration.
    // =========================================================================

    /**
     * Creates an empty copy of an existing queue via `builder().build()`.
     *
     * `builder()` returns a [ConcurrentPriorityQueueBuilder] pre-configured with
     * the source queue's comparator, maxSize, dispatcher, and keySelector.
     * Calling `build()` with no mutations produces an independent queue
     * that starts empty and carries the same configuration.
     *
     * ## Example
     *
     * ```kotlin
     * val source = ConcurrentPriorityQueue<Int>(3)
     * val copy = source.builder().build()   // empty, maxSize = 3, same comparator
     * copy.add(7)
     * println(copy.first())  // 7
     * ```
     */
    @Test
    fun builderBuild_emptySnapshot() = runTest {
        val source = ConcurrentPriorityQueue<Int>(3)
        // source is empty — builder is seeded with zero elements

        val copy = source.builder().build()

        assertEquals(0, copy.size)
        source.add(99)             // mutate source after build
        assertEquals(0, copy.size) // copy is independent; source mutation does not affect it
    }

    /**
     * Creates a populated snapshot of a queue via `builder().build()`.
     *
     * The builder is seeded with the source queue's current elements.
     * `build()` produces a new queue containing those elements; subsequent
     * mutations on either queue do not affect the other.
     *
     * ## Example
     *
     * ```kotlin
     * val source = ConcurrentPriorityQueue<Int>(3)
     * source.add(1); source.add(2); source.add(3)
     * val snapshot = source.builder().build()
     * source.poll()
     * println(snapshot.items.value)  // [1, 2, 3]  — unaffected
     * ```
     *
     * Note: [ConcurrentPriorityQueueBuilder] is obtained from [ConcurrentPriorityQueue.builder];
     * it is not constructed directly.
     */
    @Test
    fun builderBuild_populatedSnapshot() = runTest {
        val source = ConcurrentPriorityQueue<Int>(3)
        source.add(1); source.add(2); source.add(3)

        val snapshot = source.builder().build()

        assertEquals(listOf(1, 2, 3), snapshot.items.value)
        source.poll()                                        // remove 1 from source
        assertEquals(listOf(1, 2, 3), snapshot.items.value) // snapshot is unaffected
    }

    /**
     * Adds elements to a builder before calling `build()`.
     *
     * `apply { }` accumulates mutations on the [ConcurrentPriorityQueueBuilder] in a
     * single block; `build()` materialises them into a new queue. This is more efficient
     * than adding elements to a live queue one-by-one because there is no CAS overhead
     * inside the builder.
     *
     * ## Example
     *
     * ```kotlin
     * val base = ConcurrentPriorityQueue<Int>(3)
     * val queue = base.builder().apply {
     *     add(50); add(10); add(30); add(5)
     * }.build()
     * println(queue.first())  // 5
     * ```
     */
    @Test
    fun builderApplyBuild_addElements() = runTest {
        val base = ConcurrentPriorityQueue<Int>(3)

        val queue = base.builder().apply {
            add(50); add(10); add(30); add(5)
        }.build()

        assertEquals(3, queue.size)
        assertEquals(5, queue.first())
        assertEquals(listOf(5, 10, 30), queue.items.value)
    }

    /**
     * Removes elements inside the builder before calling `build()`.
     *
     * `poll()`, `remove()`, and `removeIf()` are available on the builder.
     * Here `removeIf` prunes elements before the queue is materialised.
     *
     * ## Example
     *
     * ```kotlin
     * val source = ConcurrentPriorityQueue<Int>(5)
     * source.addAll(listOf(1, 2, 3, 4, 5))
     * val filtered = source.builder().apply {
     *     removeIf { it % 2 == 0 }   // drop even numbers
     * }.build()
     * println(filtered.items.value)  // [1, 3, 5]
     * ```
     */
    @Test
    fun builderApplyBuild_removeElements() = runTest {
        val source = ConcurrentPriorityQueue<Int>(5)
        source.addAll(listOf(1, 2, 3, 4, 5))

        val filtered = source.builder().apply {
            removeIf { it % 2 == 0 }
        }.build()

        assertEquals(listOf(1, 3, 5), filtered.items.value)
        assertEquals(5, source.size) // source is unaffected
    }

    /**
     * Creates a modified queue via `mutate { }` — the concise DSL shorthand for
     * `builder().apply { … }.build()`.
     *
     * The lambda receives a [ConcurrentPriorityQueueBuilder] as its receiver.
     * The result is a new [ConcurrentPriorityQueue] with the source's configuration
     * and the mutations applied. The original queue is never modified.
     *
     * ## Example
     *
     * ```kotlin
     * val source = ConcurrentPriorityQueue<Int>(5)
     * source.addAll(listOf(10, 20, 30))
     * val updated = source.mutate {
     *     add(5)
     *     removeIf { it > 15 }
     * }
     * println(updated.items.value)  // [5, 10]
     * ```
     */
    @Test
    fun mutate_addAndRemove() = runTest {
        val source = ConcurrentPriorityQueue<Int>(5)
        source.addAll(listOf(10, 20, 30))

        val updated = source.mutate {
            add(5)
            removeIf { it > 15 }
        }

        assertEquals(listOf(5, 10), updated.items.value)
        assertEquals(listOf(10, 20, 30), source.items.value) // source unchanged
    }

    /**
     * `mutate { }` with a generic `<T, K>` queue (non-Comparable element type).
     *
     * Configuration (comparator, keySelector, maxSize) is inherited from the source;
     * the lambda only needs to describe what changes.
     *
     * ## Example
     *
     * ```kotlin
     * val source = ConcurrentPriorityQueue(compareByDescending<Item> { it.score }) { it.id }
     * source.add(Item(1, "Alpha", 90))
     * source.add(Item(2, "Beta",  70))
     * val updated = source.mutate { add(Item(3, "Gamma", 100)) }
     * println(updated.first()?.name)  // "Gamma"
     * ```
     */
    @Test
    fun mutate_genericQueue_addElement() = runTest {
        val source = ConcurrentPriorityQueue(compareByDescending<Item> { it.score }) { it.id }
        source.add(Item(1, "Alpha", 90))
        source.add(Item(2, "Beta", 70))

        val updated = source.mutate { add(Item(3, "Gamma", 100)) }

        assertEquals("Gamma", updated.first()?.name)
        assertEquals(3, updated.size)
        assertEquals(2, source.size) // source unchanged
    }

    /**
     * Chains `mutate { }` calls to build a queue step-by-step without mutating any live queue.
     *
     * Each `mutate` call produces a new independent queue; the chain reads as a pipeline
     * of transformations.
     *
     * ## Example
     *
     * ```kotlin
     * val queue = ConcurrentPriorityQueue<Int>(5)
     *     .mutate { addAll(listOf(3, 1, 4, 1, 5)) }
     *     .mutate { removeIf { it > 3 } }
     * println(queue.items.value)  // [1, 3]
     * ```
     */
    @Test
    fun mutate_chained() = runTest {
        val queue = ConcurrentPriorityQueue<Int>(5)
            .mutate { addAll(listOf(3, 1, 4, 1, 5)) }
            .mutate { removeIf { it > 3 } }

        assertEquals(listOf(1, 3), queue.items.value)
    }
}
