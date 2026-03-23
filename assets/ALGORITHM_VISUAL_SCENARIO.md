# ConcurrentPriorityQueue Algorithm — Visual Scenario

## Complete Step-by-Step Walkthrough: Insert 10 Elements, Poll All 7

This document describes every visual frame for an animated walkthrough of the
`ConcurrentPriorityQueue<Int>(maxSize = 7)` algorithm. It covers:

1. **Initialization** — creating the queue
2. **Insertions** — 10 `add()` calls showing treap insertion, path copying, rotations, eviction, and rejection
3. **Extraction** — 7 `poll()` calls draining the queue in sorted (descending) order

Each step describes **what the viewer sees on screen** and **what happens internally**.

---

## Conventions Used in This Scenario

| Term | Meaning |
|------|---------|
| **BST key** | The `Int` value itself (e.g., 50, 30, 10) |
| **Treap priority** | A random `Long` used for heap balancing (shown as decimal 0.00–1.00 for readability) |
| **Comparator** | `reverseOrder()` — higher Int = higher priority. `compare(a, b) = b.compareTo(a)` |
| **BST ordering** | Higher values go LEFT, lower values go RIGHT (because reverseOrder flips comparison) |
| **Inorder traversal** | Produces elements in **descending** order: `[70, 60, 50, 40, 30, 20, 10]` |
| **first()** | Leftmost node — highest priority (largest Int) |
| **last()** | Rightmost node — lowest priority (smallest Int) |
| **uniqueKeySelector** | `{ it }` — the Int value IS the unique key |
| **Orange node** | Newly created / copied node (path copying) |
| **Blue node** | Shared / reused node from previous version (structural sharing) |
| **Red border** | Heap property violation (child priority > parent priority) |
| **Green glow** | Heap property restored after rotation |

### Color Palette

- `#F4A340` — Orange (new/copied nodes)
- `#4A90D9` — Blue (shared/reused nodes)
- `#E74C3C` — Red (violations, evictions)
- `#2ECC71` — Green (success, heap property OK)
- `#333333` — Dark gray (text, lines)
- `#FFFFFF` — White background

---

## PART 0: INITIALIZATION

### Frame 0.1 — Title Card

**Visual:** Large title text centered on screen.

```
ConcurrentPriorityQueue<Int>
maxSize = 7 | comparator = reverseOrder() | keySelector = { it }

"A lock-free, bounded priority queue backed by a persistent Treap"
```

**Narration:** "We're going to build a ConcurrentPriorityQueue that holds at most 7 integers, where higher numbers have higher priority. The queue is backed by a Treap — a randomized binary search tree that combines BST ordering with random heap priorities for expected O(log n) operations."

### Frame 0.2 — Architecture Overview

**Visual:** Three-layer diagram:

```
┌─────────────────────────────────────────────────┐
│  ConcurrentPriorityQueue<Int, Int>              │
│  ┌───────────────────────────────────────────┐  │
│  │  MutableStateFlow<PersistentPriorityList> │  │  ← Lock-free CAS updates
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │  TreapPriorityList                  │  │  │  ← Persistent treap + PersistentMap
│  │  │  ┌───────────┐  ┌───────────────┐   │  │  │
│  │  │  │ Treap     │  │ PersistentMap │   │  │  │
│  │  │  │ (BST +    │  │ (key→element  │   │  │  │
│  │  │  │  Heap)    │  │  O(1) lookup) │   │  │  │
│  │  │  └───────────┘  └───────────────┘   │  │  │
│  │  └─────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────┘  │
│  maxSize = 7                                    │
└─────────────────────────────────────────────────┘
```

**Narration:** "The ConcurrentPriorityQueue wraps a MutableStateFlow that holds an immutable TreapPriorityList. Every mutation creates a NEW treap version using structural sharing — most nodes are reused, only nodes on the affected path are copied. The StateFlow provides lock-free thread safety via Compare-And-Swap."

### Frame 0.3 — Empty State

**Visual:** Empty tree placeholder with text "Empty Treap (root = null)". Below it, a status bar:

```
┌────────────────────────────────────────┐
│ Queue Status                           │
│ Size: 0 / 7    Elements: []            │
│ StateFlow version: v0                  │
└────────────────────────────────────────┘
```

**Narration:** "The queue starts empty. The internal StateFlow holds version zero — an empty treap with no root node."

---

## PART 1: INSERTIONS

---

### Step 1: add(50) — First Element

**What happens in code:**
```kotlin
queueState.update { currentState ->        // CAS lock-free update
    // currentState is empty → fast path
    currentState.add(50)                    // Creates new TreapPriorityList
}
```

Inside `TreapPriorityList.add(50)`:
```kotlin
val treapPriority = (50.hashCode().toLong() shl 32) or (Random.nextLong() and 0xFFFFFFFFL)
// For this scenario: treapPriority ≈ 0.90
val newRoot = insertNode(null, 50, 50, priority=0.90)
// node == null → return Node(50, key=50, priority=0.90)
```

**Frame 1.1 — Before**

**Visual:** Empty tree. An incoming element `50` appears at the top with an arrow pointing down and a label "add(50)".

**Narration:** "We add our first element: 50. The queue is empty, so this is the fast path — we simply create a new root node."

**Frame 1.2 — Treap Priority Assignment**

**Visual:** The number 50 gets a random priority badge: `[heap: 0.90]`. Show a dice/random icon briefly.

```
   50
 [heap: 0.90]
  "Random treap priority assigned"
```

**Narration:** "Every element receives a random treap priority — a Long value combining the element's hashCode with randomness. This priority is used for heap balancing, NOT for queue priority ordering. The comparator handles queue priority; the random treap priority ensures the tree stays balanced with high probability."

**Frame 1.3 — New Root Created**

**Visual:** Single orange node appears as root.

```
     ┌────┐
     │ 50 │  ← Orange (new node)
     └────┘
    [0.90]
    size: 1
```

**Status bar:**
```
Size: 1 / 7    Elements: [50]
StateFlow: v0 → v1 (CAS success ✓)
add(50) returned: null (no eviction)
```

**Narration:** "Node 50 becomes the root with treap priority 0.90. The StateFlow atomically swaps from version 0 to version 1 via Compare-And-Swap. The add method returns null — meaning the element was added successfully without evicting anything."

---

### Step 2: add(30) — BST Placement, No Rotation

**What happens in code:**
```kotlin
// compare(30, 50) = reverseOrder: 50.compareTo(30) = 1 > 0 → go RIGHT
val newRight = insertNode(null, 30, 30, priority=0.60)  // Create leaf
val newNode = node.copy(right = newRight)  // Copy root with new right child
// newRight.priority (0.60) > node.priority (0.90)? NO → no rotation
```

**Frame 2.1 — BST Comparison**

**Visual:** Element `30` approaches root `50`. A comparison bubble appears:

```
compare(30, 50) using reverseOrder()
= 50.compareTo(30) = +1 > 0
→ Go RIGHT (30 has lower value → lower priority → goes right)
```

Arrow from `30` curves to the RIGHT of `50`.

**Narration:** "We add 30. The comparator uses reverse order, so higher values have higher priority and go LEFT. Since 30 is less than 50, it goes to the RIGHT."

**Frame 2.2 — Node Creation + Path Copy**

**Visual:** Two trees side by side with arrow between them labeled "Structural Sharing".

```
  BEFORE (v1)          AFTER (v2)
     ┌────┐              ┌────┐
     │ 50 │  - - - - →   │ 50'│  ← Orange (COPY of 50)
     └────┘              └────┘
    [0.90]              [0.90]
                            \
                          ┌────┐
                          │ 30 │  ← Orange (NEW)
                          └────┘
                         [0.60]
```

**Narration:** "Node 30 is created as a new leaf with treap priority 0.60. The root node 50 must be COPIED (not mutated) to point to the new child — this is structural sharing. The old version v1 remains untouched. Both trees coexist in memory."

**Frame 2.3 — Heap Property Check**

**Visual:** A check mark appears between 50' and 30:

```
     50' [0.90]
       \
       30 [0.60]

   Heap check: child.priority (0.60) > parent.priority (0.90)?
   0.60 > 0.90 → FALSE
   ✓ No rotation needed
```

**Status bar:**
```
Size: 2 / 7    Elements: [50, 30]
Inorder traversal: 50 → 30 (descending ✓)
add(30) returned: null
```

**Narration:** "We check the heap property: is the child's treap priority greater than the parent's? 0.60 is NOT greater than 0.90, so no rotation is needed. The tree satisfies both BST and heap properties."

---

### Step 3: add(70) — BST Placement, No Rotation

**What happens in code:**
```kotlin
// compare(70, 50) = 50.compareTo(70) = -1 ≤ 0 → go LEFT
val newLeft = insertNode(null, 70, 70, priority=0.75)
val newNode = node.copy(left = newLeft)
// newLeft.priority (0.75) > node.priority (0.90)? NO → no rotation
```

**Frame 3.1 — BST Comparison**

**Visual:** Element `70` approaches root `50'`. Comparison bubble:

```
compare(70, 50) using reverseOrder()
= 50.compareTo(70) = -1 ≤ 0
→ Go LEFT (70 has higher value → higher priority → goes left)
```

**Frame 3.2 — Structural Sharing**

**Visual:** Three trees fade in sequence: v1 (ghosted), v2 (ghosted), v3 (active).

```
  v2 (previous)              v3 (new)

     50' [0.90]                50'' [0.90]  ← Orange (new copy)
       \                      /       \
       30 [0.60]        70 [0.75]    30 [0.60]  ← Blue (SHARED!)
                        ↑ Orange     ↑ Reused from v2
                        (new node)

  Dashed line from v3's "30" back to v2's "30" showing it's the same object in memory.
```

**Narration:** "70 is greater than 50, so it goes LEFT. We create new node 70 and copy the root to point to it. But notice — the right child 30 is NOT copied. It is SHARED between versions v2 and v3. This is the key insight of persistent data structures: only nodes on the affected path are copied. Everything else is reused."

**Frame 3.3 — Heap Check**

```
   Heap check: 70.priority (0.75) > 50.priority (0.90)?
   0.75 > 0.90 → FALSE ✓ No rotation
```

**Status bar:**
```
Size: 3 / 7    Elements: [70, 50, 30]
Tree shape:
     50 [0.90]
    /       \
  70 [0.75]  30 [0.60]
```

---

### Step 4: add(10) — Deeper Insertion, No Rotation

**What happens in code:**
```kotlin
// compare(10, 50) = 50.compareTo(10) = 1 > 0 → RIGHT
// compare(10, 30) = 30.compareTo(10) = 1 > 0 → RIGHT
// node.right == null → Create Node(10, priority=0.45)
// Back at 30: 0.45 > 0.60? NO → no rotation
// Back at 50: 30'.priority still 0.60 > 0.90? NO → no rotation
```

**Frame 4.1 — Traversal Path**

**Visual:** Element 10 traces a path: 50 → RIGHT → 30 → RIGHT → (null). The path is highlighted in orange.

```
     50 [0.90]
    /       \
  70 [0.75]  30 [0.60]   ← compare(10,30): go RIGHT
                  \
                  (null)  ← insert here!
```

**Narration:** "10 is less than 50 — go right. 10 is less than 30 — go right again. We've reached a null position; this is where 10 will be inserted."

**Frame 4.2 — Path Copying (2 nodes copied)**

**Visual:** Show which nodes are copied vs shared.

```
     50' [0.90]   ← Orange (copied — on path)
    /       \
  70 [0.75]  30' [0.60]  ← Orange (copied — on path)
  ↑ Blue         \
  (shared)       10 [0.45]  ← Orange (new leaf)
```

**Narration:** "Path copying: nodes 50 and 30 are on the insertion path, so they are copied. Node 70 is NOT on the path — it is shared. Only 3 nodes allocated (2 copies + 1 new) out of a tree of 4. This is O(log n) space."

**Frame 4.3 — Heap Checks (bottom-up)**

```
At node 30': child 10 [0.45] > parent 30' [0.60]? NO ✓
At node 50': child 30' [0.60] > parent 50' [0.90]? NO ✓
No rotations needed.
```

**Status bar:**
```
Size: 4 / 7    Elements: [70, 50, 30, 10]
     50 [0.90]
    /       \
  70 [0.75]  30 [0.60]
                \
                10 [0.45]
```

---

### Step 5: add(40) — Insertion with ROTATION!

**What happens in code:**
```kotlin
// compare(40, 50) = 50.compareTo(40) = 1 > 0 → RIGHT of 50
// compare(40, 30) = 30.compareTo(40) = -1 ≤ 0 → LEFT of 30
// node.left == null → Create Node(40, priority=0.80)
// Back at 30: newLeft.priority (0.80) > node.priority (0.60)? YES! → rotateRight(30)!
// Back at 50: newRight is now 40[0.80]. 0.80 > 0.90? NO → no rotation at 50
```

**Frame 5.1 — Traversal Path**

**Visual:** Element 40 traces: 50 → RIGHT → 30 → LEFT → (null).

```
     50 [0.90]
    /       \
  70 [0.75]  30 [0.60]   ← compare(40,30): 30.compareTo(40)=-1 ≤ 0 → LEFT
            / \
     (null)   10 [0.45]
     ↑ insert here
```

**Narration:** "40 is less than 50, so go right. But 40 is GREATER than 30 — in reverse order, that means go LEFT. We insert 40 as the left child of 30."

**Frame 5.2 — New Node + Heap Violation Detected!

**Visual:** Node 40 is created. A RED border appears around the edge 30→40.

```
       30 [0.60]
      / \
 40 [0.80]  10 [0.45]

 ⚠ VIOLATION: child priority (0.80) > parent priority (0.60)!
 Red dashed circle around 30→40 edge
```

**Narration:** "We create node 40 with treap priority 0.80. But wait — 0.80 is GREATER than its parent 30's priority of 0.60. This violates the heap property! The treap must rotate to fix this."

**Frame 5.3 — Right Rotation Animation**

**Visual:** Animated rotation. Show the transformation step by step.

```
  BEFORE rotation:           AFTER rotateRight(30):

       30 [0.60]                  40 [0.80]
      / \                           \
 40 [0.80]  10 [0.45]            30 [0.60]
                                    \
                                  10 [0.45]

  ┌─────────────────────────────────────────────────────┐
  │ rotateRight(node=30):                                │
  │   left = 40 (the child with higher priority)         │
  │   result = Node(                                     │
  │     element = 40,  priority = 0.80,                  │
  │     left  = 40.left  = null,                         │
  │     right = 30.copy(left = 40.right) = 30(right=10)  │
  │   )                                                  │
  │   40 becomes parent, 30 becomes its right child      │
  └─────────────────────────────────────────────────────┘
```

**Narration:** "A RIGHT ROTATION at node 30: the left child 40 rises up to become the parent, and 30 drops down to become 40's right child. Node 10 stays as 30's right child. Now 40's priority 0.80 is correctly above 30's 0.60 — heap property restored!"

**Frame 5.4 — Check at Parent Node 50**

**Visual:** Zoom out to show full tree. Check at 50:

```
     50 [0.90]
    /       \
  70 [0.75]  40 [0.80]  ← rotated up from below
                \
               30 [0.60]
                 \
                 10 [0.45]

  Heap check at 50: right child 40 [0.80] > 50 [0.90]?
  0.80 > 0.90 → FALSE ✓ No further rotation
```

**Narration:** "Back at the parent node 50: is 40's priority 0.80 greater than 50's 0.90? No — so no further rotation is needed. The insertion is complete."

**Frame 5.5 — Full Tree After Step 5**

**Visual:** Complete tree with all nodes colored. Shared vs new annotated.

```
     50' [0.90]     ← Orange (copied)
    /       \
  70 [0.75]  40 [0.80]   ← Orange (new + rotated)
  ↑ Blue        \
  (shared)      30' [0.60]  ← Orange (copied + rotated down)
                   \
                  10 [0.45]  ← Blue (shared!)
```

**Status bar:**
```
Size: 5 / 7    Elements: [70, 50, 40, 30, 10]
Nodes allocated: 3 (50', 40, 30')    Nodes shared: 2 (70, 10)
add(40) returned: null
```

---

### Step 6: add(60) — Deeper Left Insertion

**What happens in code:**
```kotlin
// compare(60, 50) = 50.compareTo(60) = -1 ≤ 0 → LEFT of 50
// compare(60, 70) = 70.compareTo(60) = 1 > 0 → RIGHT of 70
// node.right == null → Create Node(60, priority=0.55)
// Back at 70: 0.55 > 0.75? NO → no rotation
// Back at 50: 70'.priority still 0.75 > 0.90? NO → no rotation
```

**Frame 6.1 — Traversal and Insertion**

**Visual:** Element 60 traces: 50 → LEFT → 70 → RIGHT → (null). Path highlighted.

```
compare(60, 50) = -1 ≤ 0 → LEFT
compare(60, 70) = +1 > 0  → RIGHT

     50 [0.90]
    /       \
  70 [0.75]  40 [0.80]
     \          \
   60 [0.55]   30 [0.60]  ← NEW!
                  \
                 10 [0.45]
```

**Frame 6.2 — Structural Sharing Details**

```
Nodes COPIED (on path):  50', 70'      ← 2 copies
Nodes NEW:               60            ← 1 new
Nodes SHARED:            40, 30, 10    ← 3 shared (entire right subtree!)
Total allocated: 3 out of 6 nodes = 50% reuse
```

**Narration:** "The entire right subtree (40, 30, 10) is shared — it wasn't touched at all. Only the path from root to the insertion point is copied."

**Status bar:**
```
Size: 6 / 7    Elements: [70, 60, 50, 40, 30, 10]
```

---

### Step 7: add(20) — Deep Insertion with CASCADING ROTATION!

**What happens in code:**
```kotlin
// compare(20, 50) → RIGHT    (20 < 50)
// compare(20, 40) → RIGHT    (20 < 40)
// compare(20, 30) → RIGHT    (20 < 30)
// compare(20, 10) → LEFT     (20 > 10)
// node.left == null → Create Node(20, priority=0.70)
//
// Back at 10: newLeft.priority (0.70) > node.priority (0.45)? YES → rotateRight(10)!
// Back at 30: newRight is now 20[0.70]. 0.70 > 0.60? YES → rotateLeft(30)!
// Back at 40: newRight is now 20[0.70]. 0.70 > 0.80? NO → stop
```

This is the most complex step — it demonstrates **cascading rotations** bubbling up the tree.

**Frame 7.1 — Deep Traversal (4 levels)**

**Visual:** Element 20 traces a long path down the right side: 50→40→30→10→LEFT.

```
     50 [0.90]
    /       \
  70 [0.75]  40 [0.80]
     \          \
   60 [0.55]   30 [0.60]
                  \
                 10 [0.45]
                /
          (null) ← insert 20 here
```

**Narration:** "20 is smaller than 50, 40, and 30, so it goes right at each level. But 20 is greater than 10 — in reverse order, that means go LEFT of 10. We insert 20 as a new leaf."

**Frame 7.2 — First Violation: Node 10**

**Visual:** Node 20 created with priority 0.70. Red violation at 10→20 edge.

```
        10 [0.45]
       /
  20 [0.70]

  ⚠ VIOLATION: 0.70 > 0.45
  → rotateRight(10)!
```

**Frame 7.3 — First Rotation: rotateRight(10)**

**Visual:** Animated rotation at node 10.

```
  BEFORE:              AFTER:
     10 [0.45]          20 [0.70]
    /                  /       \
  20 [0.70]       (null)     10 [0.45]

  Node 20 rises, node 10 becomes right child.
```

**Narration:** "First rotation: rotateRight at node 10. Node 20 rises up, node 10 drops down to become 20's right child."

**Frame 7.4 — Second Violation: Node 30**

**Visual:** Zoom out one level. Now node 20 is the right child of 30. Check heap property.

```
       30 [0.60]
          \
        20 [0.70]    ← just rotated up
       /       \
  (null)     10 [0.45]

  ⚠ VIOLATION: right child 20 [0.70] > parent 30 [0.60]
  → rotateLeft(30)!
```

**Narration:** "But we're not done! Back at node 30, we check again: is the new right child 20's priority (0.70) greater than 30's priority (0.60)? YES! We need another rotation — this time a LEFT rotation at node 30."

**Frame 7.5 — Second Rotation: rotateLeft(30)**

**Visual:** Animated rotation at node 30.

```
  BEFORE:                    AFTER:
       30 [0.60]                20 [0.70]
          \                    /         \
        20 [0.70]          30 [0.60]   10 [0.45]
       /       \
  (null)     10 [0.45]

  ┌──────────────────────────────────────────────────┐
  │ rotateLeft(node=30):                              │
  │   right = 20 (higher priority child)              │
  │   result = Node(                                  │
  │     element = 20,  priority = 0.70,               │
  │     left  = 30.copy(right = 20.left) = 30(r=null) │
  │     right = 20.right = 10                          │
  │   )                                               │
  │   20 becomes parent, 30 becomes left child,        │
  │   10 stays as right child                          │
  └──────────────────────────────────────────────────┘
```

**Narration:** "LEFT rotation at node 30: node 20 rises up to take 30's position. 30 becomes 20's left child (because 30 > 20 in value, left in reverse order). 10 remains as 20's right child."

**Frame 7.6 — Check at Node 40 (Rotation Stops)**

**Visual:** Zoom out to show 40's subtree.

```
       40 [0.80]
          \
        20 [0.70]    ← just rotated up (from cascading)
       /         \
   30 [0.60]   10 [0.45]

  Heap check: 20 [0.70] > 40 [0.80]?
  0.70 > 0.80 → FALSE ✓ Rotation stops here!
```

**Narration:** "Back at node 40: is 20's priority 0.70 greater than 40's 0.80? NO — the cascade stops. The heap property is satisfied throughout the tree."

**Frame 7.7 — Full Tree After Step 7 (QUEUE IS FULL!)**

**Visual:** Complete tree with all 7 nodes. Big label: "QUEUE FULL: 7/7".

```
         50 [0.90]
        /         \
    70 [0.75]    40 [0.80]
       \            \
     60 [0.55]    20 [0.70]
                 /        \
             30 [0.60]   10 [0.45]

  Inorder traversal: 70, 60, 50, 40, 30, 20, 10
  first() = 70 (highest priority)
  last()  = 10 (lowest priority)

  ┌──────────────────────────────────────────┐
  │  🔒 QUEUE FULL: 7 / 7                   │
  │  Next insertion may cause EVICTION!      │
  └──────────────────────────────────────────┘
```

**Narration:** "The queue is now full with 7 elements, sorted in descending order: 70, 60, 50, 40, 30, 20, 10. The highest priority element is 70 (leftmost), and the lowest is 10 (rightmost). Any new element that's better than 10 will trigger an eviction!"

**Status bar:**
```
Size: 7 / 7    Elements: [70, 60, 50, 40, 30, 20, 10]
Nodes allocated in this step: 5 (50', 40', 30', 20, 10')
Nodes shared: 2 (70, 60 — entire left subtree)
Cascading rotations: 2 (at 10, then at 30)
add(20) returned: null
```

---

### Step 8: add(80) — EVICTION! Element Displaces Lowest Priority

**What happens in code:**
```kotlin
queueState.update { currentState ->
    // Queue full, existingElement == null
    val lowestPriorityElement = currentState.last()  // = 10
    // compare(80, 10): reverseOrder → 10.compareTo(80) = -1 < 0 → NOT >= 0
    // → 80 is BETTER than 10, proceed with insertion

    var updated = currentState.add(80)   // Insert 80 into treap
    // updated.size = 8 > maxSize = 7
    removed = updated.last()             // removed = 10 (will be evicted)
    updated = updated.removeLast()       // Remove 10 from treap

    updated  // size = 7
}
return removed  // returns 10 (the evicted element)
```

**Frame 8.1 — Eviction Check**

**Visual:** Element 80 approaches the full queue. A gate/bouncer metaphor:

```
    ┌──────────────────────────────┐
    │  80 wants to enter!          │
    │                              │
    │  Queue: [70,60,50,40,30,20,10]│
    │  last() = 10                 │
    │                              │
    │  compare(80, 10):            │
    │  Is 80 worse than 10? NO!    │
    │  80 > 10 → 80 has higher     │
    │  priority → ALLOW ENTRY      │
    └──────────────────────────────┘
```

**Narration:** "Element 80 wants to join the full queue. The queue checks: is 80 worse than the current lowest-priority element 10? Since 80 is greater than 10, it has higher priority — so it's allowed in. But someone must leave."

**Frame 8.2 — Insert 80 into Treap**

```
// compare(80, 50) → LEFT (80 > 50)
// compare(80, 70) → LEFT (80 > 70)
// 70.left == null → Create Node(80, priority=0.95)
// Back at 70: 0.95 > 0.75? YES → rotateRight(70)!
// Back at 50: 80.priority (0.95) > 50.priority (0.90)? YES → rotateRight(50)!
```

**Frame 8.3 — Rotation 1: rotateRight(70)**

```
  BEFORE:              AFTER:
    70 [0.75]           80 [0.95]
   /      \                \
 80 [0.95] 60 [0.55]    70 [0.75]
                            \
                          60 [0.55]
```

**Narration:** "80's treap priority 0.95 is higher than 70's 0.75 — rotateRight! Node 80 rises, 70 becomes its right child."

**Frame 8.4 — Rotation 2: rotateRight(50)**

```
  BEFORE:                       AFTER:
       50 [0.90]                     80 [0.95]
      /         \                        \
   80 [0.95]   40 [0.80]              50 [0.90]
      \            \                  /         \
    70 [0.75]    20 [0.70]       70 [0.75]    40 [0.80]
       \        /       \           \            \
     60[0.55] 30[0.60] 10[0.45]   60[0.55]    20 [0.70]
                                              /       \
                                          30 [0.60] 10 [0.45]
```

**Narration:** "And again — 80's priority 0.95 is higher than 50's 0.90. Another rotateRight at the root! Node 80 becomes the new root of the entire treap."

**Frame 8.5 — Temporary State: 8 Elements**

```
Queue has 8 elements but maxSize = 7!
size (8) > maxSize (7) → EVICT last()!

last() = 10 (rightmost node = lowest priority)
```

**Frame 8.6 — Eviction of Element 10**

**Visual:** Node 10 is highlighted in RED and slides out of the tree with an "EVICTED" label. An animation shows it being removed.

```
          80 [0.95]
              \
            50 [0.90]
           /         \
      70 [0.75]    40 [0.80]
         \            \
       60[0.55]     20 [0.70]
                   /       \
               30 [0.60]  10 [0.45] ← RED "EVICTED!"
                                ↓
                          (removed via removeLast())

  removeLast():
    last() = 10 → removeByKey(10)
    Path: 80→50→40→20→10 (found, leaf node)
    mergeChildren(null, null) = null
    20.copy(right = null) → path copied back up
```

**Frame 8.7 — Final Tree After Eviction**

```
          80 [0.95]
              \
            50 [0.90]
           /         \
      70 [0.75]    40 [0.80]
         \            \
       60[0.55]     20 [0.70]
                   /
               30 [0.60]

  Inorder: 80, 70, 60, 50, 40, 30, 20
  first() = 80, last() = 20
```

**Status bar:**
```
Size: 7 / 7    Elements: [80, 70, 60, 50, 40, 30, 20]
add(80) returned: 10 ← THE EVICTED ELEMENT!
Element 10 was displaced by higher-priority element 80.
```

**Narration:** "Element 10 is evicted — removed from the treap via removeLast(). The add() method returns 10, telling the caller which element was displaced. The queue remains at capacity with 7 elements, now ranging from 80 down to 20."

---

### Step 9: add(5) — REJECTED! Worse Than Lowest Priority

**What happens in code:**
```kotlin
queueState.update { currentState ->
    // Queue full (size=7 >= maxSize=7), existingElement == null
    val lowestPriorityElement = currentState.last()  // = 20
    // compare(5, 20): reverseOrder → 20.compareTo(5) = 1 >= 0
    // → 5 is WORSE than 20 → REJECT (return currentState unchanged)
    return@update currentState
}
return null  // No eviction, element was rejected
```

**Frame 9.1 — Rejection**

**Visual:** Element 5 approaches the queue but bounces off a wall/shield. Big red X.

```
    ┌──────────────────────────────┐
    │  5 wants to enter!           │
    │                              │
    │  Queue: [80,70,60,50,40,30,20]│
    │  last() = 20                 │
    │                              │
    │  compare(5, 20):             │
    │  Is 5 worse than 20? YES!    │
    │  5 < 20 → 5 has lower       │
    │  priority → REJECTED ✗      │
    │                              │
    │  No tree modification.       │
    │  No CAS update needed.       │
    │  StateFlow stays at same     │
    │  version — zero allocation!  │
    └──────────────────────────────┘
```

**Narration:** "Element 5 tries to join, but it's worse than the current lowest-priority element 20. The queue rejects it immediately — no tree modification, no node allocation, no CAS update. This is the fast-path rejection and it's extremely cheap: just one comparison."

**Status bar:**
```
Size: 7 / 7    Elements: [80, 70, 60, 50, 40, 30, 20]    (UNCHANGED)
add(5) returned: null (rejected, not evicted — null means "no eviction happened")
```

---

### Step 10: add(25) — Eviction of Element 20

**What happens in code:**
```kotlin
// compare(25, 20) = 20.compareTo(25) = -1 < 0 → NOT >= 0 → 25 is better → proceed
// Insert 25 into treap, then evict last()
```

**Frame 10.1 — Entry Check**

```
compare(25, 20): 25 > 20 → 25 has higher priority than current lowest.
ALLOWED — will evict lowest after insertion.
```

**Frame 10.2 — Insert 25 into Treap**

```
// compare(25, 80) → RIGHT (25 < 80)
// compare(25, 50) → RIGHT (25 < 50)
// compare(25, 40) → RIGHT (25 < 40)
// compare(25, 20) → LEFT  (25 > 20)
// compare(25, 30) → RIGHT (25 < 30)
// 30.right == null → Create Node(25, priority=0.50)
// Back at 30: 0.50 > 0.60? NO → no rotation
// ... no rotations all the way up

Tree with 25 inserted (8 elements):
          80 [0.95]
              \
            50 [0.90]
           /         \
      70 [0.75]    40 [0.80]
         \            \
       60[0.55]     20 [0.70]
                   /
               30 [0.60]
                  \
                  25 [0.50]  ← NEW
```

**Frame 10.3 — Evict last() = 20**

```
last() = 20 (rightmost in treap)
removeByKey(20):
  Path: 80→50→40→20 (found!)
  20 has left child 30[0.60,right=25[0.50]], right child = null
  mergeChildren(left=30[...], right=null) = 30[0.60,right=25[0.50]]
  40.right = 30[0.60,right=25[0.50]]    ← 20 replaced by its left subtree
```

**Visual:** Node 20 slides out (RED), its child subtree (30,25) slides up to take its place.

**Frame 10.4 — Final Tree After Step 10**

```
          80 [0.95]
              \
            50 [0.90]
           /         \
      70 [0.75]    40 [0.80]
         \            \
       60[0.55]     30 [0.60]
                       \
                      25 [0.50]

  Inorder: 80, 70, 60, 50, 40, 30, 25
```

**Status bar:**
```
Size: 7 / 7    Elements: [80, 70, 60, 50, 40, 30, 25]
add(25) returned: 20 ← EVICTED
```

**Narration:** "Element 25 replaces 20. The eviction mechanism is elegant: first insert the new element (temporarily exceeding capacity), then remove the last element (lowest priority). The add method returns 20 — the caller knows exactly which element was displaced."

---

## PART 2: POLLING — Extracting All Elements in Sorted Order

**Narration (transition):** "Now let's drain the queue using poll(). Each poll() removes and returns the HIGHEST priority element — the leftmost node in the treap. Since the comparator uses reverse order, higher integers come first."

---

### Step 11: poll() → 80

**What happens in code:**
```kotlin
fun poll(): T? {
    var result: T? = null
    queueState.update { currentState ->
        val first = currentState.first()   // = 80 (leftmost)
        result = first
        currentState.removeByKey(uniqueKeySelector(first))  // Remove 80
    }
    return result  // 80
}
```

**Frame 11.1 — Finding first()**

**Visual:** Highlight the leftmost path: 80 is the root. But wait — 80 has no left child (80.left = null). So first() = 80.

Actually, let me re-examine. The tree is:
```
          80 [0.95]
              \
            50 [0.90]
           /         \
      70 [0.75]    40 [0.80]
         \            \
       60[0.55]     30 [0.60]
                       \
                      25 [0.50]
```

80 has no left child → first() = 80. ✓

```
first(): traverse LEFT until null
  80.left == null → first() = 80

  80 is the highest priority element (largest integer)
```

**Frame 11.2 — Remove 80 (root)**

```
removeByKey(80):
  80 is root. mergeChildren(left=null, right=50[...])
  New root = 50 (because left is null, just return right)
```

**Visual:** Node 80 lifts up and out of the tree with "POLLED: 80" label. The right subtree (50 as new root) slides up.

**Frame 11.3 — Tree After Polling 80**

```
            50 [0.90]
           /         \
      70 [0.75]    40 [0.80]
         \            \
       60[0.55]     30 [0.60]
                       \
                      25 [0.50]

  Remaining: [70, 60, 50, 40, 30, 25]    Size: 6
  poll() returned: 80
```

---

### Step 12: poll() → 70

```
first(): 50.left = 70, 70.left = null → first() = 70

removeByKey(70):
  Path: 50→LEFT→70 (found!)
  70 has no left child, right = 60[0.55]
  mergeChildren(null, 60) = 60
  50.left = 60
```

**Visual:** Node 70 lifts out. Node 60 slides up to take its position.

```
            50 [0.90]
           /         \
      60 [0.55]    40 [0.80]
                      \
                    30 [0.60]
                       \
                      25 [0.50]

  Remaining: [60, 50, 40, 30, 25]    Size: 5
  poll() returned: 70
```

---

### Step 13: poll() → 60

```
first(): 50.left = 60, 60.left = null → first() = 60

removeByKey(60):
  60 is a leaf (no children)
  mergeChildren(null, null) = null
  50.left = null
```

**Visual:** Node 60 lifts out. 50's left becomes empty.

```
            50 [0.90]
                 \
               40 [0.80]
                  \
                30 [0.60]
                   \
                  25 [0.50]

  Remaining: [50, 40, 30, 25]    Size: 4
  poll() returned: 60
```

---

### Step 14: poll() → 50

```
first(): 50.left == null → first() = 50

removeByKey(50):
  50 is root. mergeChildren(null, 40[...]) = 40
  New root = 40
```

**Visual:** Node 50 lifts out. Node 40 becomes root.

```
               40 [0.80]
                  \
                30 [0.60]
                   \
                  25 [0.50]

  Remaining: [40, 30, 25]    Size: 3
  poll() returned: 50
```

---

### Step 15: poll() → 40

```
first(): 40.left == null → first() = 40
removeByKey(40): root becomes 30
```

```
                30 [0.60]
                   \
                  25 [0.50]

  Remaining: [30, 25]    Size: 2
  poll() returned: 40
```

---

### Step 16: poll() → 30

```
first(): 30.left == null → first() = 30
removeByKey(30): root becomes 25
```

```
                  25 [0.50]

  Remaining: [25]    Size: 1
  poll() returned: 30
```

---

### Step 17: poll() → 25

```
first(): 25.left == null → first() = 25
removeByKey(25): root becomes null
```

**Visual:** Last node 25 lifts out. Tree is empty.

```
                 (empty)

  Remaining: []    Size: 0
  poll() returned: 25
```

---

### Step 18: poll() → null (Empty Queue)

```
first() = null → return currentState (no update)
return null
```

**Visual:** "Queue is empty. poll() returns null."

---

## PART 3: SUMMARY AND KEY INSIGHTS

### Frame S.1 — Complete Operation Timeline

**Visual:** Horizontal timeline showing all operations and their results:

```
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │ INSERTION PHASE                                                             │
 ├─────┬────────────┬──────────┬──────────┬─────────────┬──────────────────────┤
 │ #   │ Operation  │ Result   │ Size     │ Rotations   │ Notes                │
 ├─────┼────────────┼──────────┼──────────┼─────────────┼──────────────────────┤
 │  1  │ add(50)    │ null     │ 1/7      │ 0           │ First element        │
 │  2  │ add(30)    │ null     │ 2/7      │ 0           │                      │
 │  3  │ add(70)    │ null     │ 3/7      │ 0           │                      │
 │  4  │ add(10)    │ null     │ 4/7      │ 0           │                      │
 │  5  │ add(40)    │ null     │ 5/7      │ 1 (right)   │ Rotation at 30       │
 │  6  │ add(60)    │ null     │ 6/7      │ 0           │                      │
 │  7  │ add(20)    │ null     │ 7/7 FULL │ 2 (cascade) │ Rotations at 10 & 30 │
 │  8  │ add(80)    │ 10       │ 7/7      │ 2 (cascade) │ Evicts 10            │
 │  9  │ add(5)     │ null     │ 7/7      │ 0           │ REJECTED (too low)   │
 │ 10  │ add(25)    │ 20       │ 7/7      │ 0           │ Evicts 20            │
 ├─────┴────────────┴──────────┴──────────┴─────────────┴──────────────────────┤
 │ EXTRACTION PHASE                                                            │
 ├─────┬────────────┬──────────┬──────────┬────────────────────────────────────┤
 │ 11  │ poll()     │ 80       │ 6        │ Elements come out in sorted order  │
 │ 12  │ poll()     │ 70       │ 5        │                                    │
 │ 13  │ poll()     │ 60       │ 4        │                                    │
 │ 14  │ poll()     │ 50       │ 3        │                                    │
 │ 15  │ poll()     │ 40       │ 2        │                                    │
 │ 16  │ poll()     │ 30       │ 1        │                                    │
 │ 17  │ poll()     │ 25       │ 0        │                                    │
 │ 18  │ poll()     │ null     │ 0        │ Queue empty                        │
 └─────┴────────────┴──────────┴──────────┴────────────────────────────────────┘
```

### Frame S.2 — Key Concepts Demonstrated

```
┌─────────────────────────────────────────────────────────────────┐
│                    KEY CONCEPTS                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. TREAP = BST + Heap                                          │
│     • BST property: elements sorted by comparator (reverseOrder) │
│     • Heap property: parent's random priority ≥ children's       │
│     • Rotations restore heap property after insertion             │
│                                                                  │
│  2. STRUCTURAL SHARING                                           │
│     • Immutable nodes — never modified, only copied              │
│     • Only O(log n) nodes copied per operation                   │
│     • Rest of the tree is shared between versions                │
│     • Old versions remain valid — safe for concurrent readers    │
│                                                                  │
│  3. LOCK-FREE CONCURRENCY                                       │
│     • MutableStateFlow.update() uses CAS (Compare-And-Swap)     │
│     • No locks, no blocking, no deadlocks                       │
│     • Concurrent writes may retry but never corrupt              │
│     • Readers always see a consistent snapshot                   │
│                                                                  │
│  4. BOUNDED EVICTION                                            │
│     • maxSize enforced: lowest priority element evicted          │
│     • Elements worse than current worst are rejected immediately │
│     • Zero allocation for rejected elements                     │
│                                                                  │
│  5. PERSISTENT DATA STRUCTURE                                   │
│     • Every mutation produces a NEW tree version                 │
│     • Previous versions are NOT destroyed                       │
│     • Multiple coroutines can safely read different versions     │
│     • Perfect for StateFlow: each emit is an immutable snapshot  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Frame S.3 — Complexity Summary

```
┌───────────────────────────────────────────┐
│          COMPLEXITY ANALYSIS              │
├──────────────┬────────┬───────────────────┤
│ Operation    │ Time   │ Space             │
├──────────────┼────────┼───────────────────┤
│ add()        │ O(lg n)│ O(lg n) new nodes │
│ poll()       │ O(lg n)│ O(lg n) new nodes │
│ first()      │ O(lg n)│ O(1)              │
│ last()       │ O(lg n)│ O(1)              │
│ getByKey()   │ O(1)   │ O(1)              │
│ containsKey()│ O(1)   │ O(1)              │
│ removeByKey()│ O(lg n)│ O(lg n) new nodes │
│ clear()      │ O(1)   │ O(1)              │
│ items (read) │ O(1)   │ O(1) (snapshot)   │
│ size         │ O(1)   │ O(1)              │
└──────────────┴────────┴───────────────────┘

  "lg n" = expected, due to random treap priorities
  Space per mutation = O(log n) because of path copying
  Total space = O(n) for the current tree
```

### Frame S.4 — Concurrent Access Pattern

**Visual:** Two coroutine timelines running in parallel, both accessing the same queue.

```
  Coroutine A (Writer)              Coroutine B (Reader/UI)
  ─────────────────────             ──────────────────────────
  queue.add(80)                     val items = queue.items.value
    ↓                                 ↓
  queueState.update {               // Gets snapshot v7
    // Read current v7              // [70,60,50,40,30,20,10]
    // Create new treap v8         // Safe to iterate!
    // CAS: v7 → v8 ✓             // No locks needed
  }                                // No ConcurrentModification
    ↓
  // v8 emitted via StateFlow      queue.items.collect { items ->
                                      // Receives v8 automatically
                                      // [80,70,60,50,40,30,25]
                                      // UI recomposes with new data
                                   }
```

**Narration:** "This is the beauty of combining persistent data structures with StateFlow. Writer coroutines create new immutable versions via CAS. Reader coroutines — like Jetpack Compose UI — observe the StateFlow and receive each new snapshot automatically. No locks, no blocking, no ConcurrentModificationException. Every reader always sees a consistent, complete snapshot of the queue."

---

## APPENDIX: Node Allocation Accounting

Total nodes created across all 10 add operations and 7 poll operations:

```
Step  1: add(50)  — 1 new node                    = 1
Step  2: add(30)  — 1 new + 1 copy                = 2
Step  3: add(70)  — 1 new + 1 copy                = 2
Step  4: add(10)  — 1 new + 2 copies              = 3
Step  5: add(40)  — 1 new + 2 copies (+ rotation) = 3
Step  6: add(60)  — 1 new + 2 copies              = 3
Step  7: add(20)  — 1 new + 4 copies (+ 2 rot.)   = 5
Step  8: add(80)  — 1 new + 2 copies (+ 2 rot.)   = 3
                    + removeLast(10): 4 copies      = 4
Step  9: add(5)   — REJECTED, 0 allocations        = 0
Step 10: add(25)  — 1 new + 5 copies              = 6
                    + removeLast(20): 3 copies      = 3
─────────────────────────────────────────────────────
Total insert phase allocations:                    = 35 nodes

Poll 80: 0 new (root removed, right subtree reused)
Poll 70: 1 copy (50's left pointer updated)
Poll 60: 1 copy
Poll 50: 0 new (root removed)
Poll 40: 0 new (root removed)
Poll 30: 0 new (root removed)
Poll 25: 0 new (root removed, tree empty)
─────────────────────────────────────────────────────
Total poll phase allocations:                      = 2 nodes
─────────────────────────────────────────────────────
Grand total:                                       = 37 nodes

Without structural sharing (full copy each time):
  10 insertions × 7 nodes + 7 polls × ~5 nodes    ≈ 105 nodes
  Structural sharing saved ~65% of allocations!
```

---

## APPENDIX: Treap Priority Assignments Used

These are the treap priorities used in this scenario for reproducibility:

| Element | Treap Priority | Notes |
|---------|---------------|-------|
| 50 | 0.90 | First root |
| 30 | 0.60 | No rotation |
| 70 | 0.75 | No rotation |
| 10 | 0.45 | No rotation |
| 40 | 0.80 | Triggers rotateRight at 30 |
| 60 | 0.55 | No rotation |
| 20 | 0.70 | Triggers cascading: rotateRight(10), rotateLeft(30) |
| 80 | 0.95 | Triggers cascading: rotateRight(70), rotateRight(50); becomes root |
| 5  | — | Rejected (never inserted) |
| 25 | 0.50 | No rotation |

---

*This scenario document is designed to be consumed by NotebookLM or a video animation tool alongside the library source code and README.md to produce a comprehensive video explanation of the ConcurrentPriorityQueue algorithm.*
