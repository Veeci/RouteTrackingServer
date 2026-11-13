package veeci.practicing.rts.routing.domain.search

/**
 * A binary min-heap of `(node, key)` pairs, kept in two parallel primitive arrays.
 *
 * It has no decrease-key. When a search finds a shorter way to a node, it pushes the node again with the
 * smaller key and leaves the old entry in place ("lazy deletion"). The caller skips an entry when its node
 * is already settled. That costs a few extra entries but keeps the heap simple, and on road graphs, where
 * a node has 2 to 4 edges, the heap stays small.
 *
 * `java.util.PriorityQueue<Entry>` would allocate one object per push. A search on a city graph pushes
 * hundreds of thousands of entries, so this heap avoids that garbage.
 */
internal class IntDoubleHeap(
    initialCapacity: Int = INITIAL_CAPACITY,
) {
    private var nodes = IntArray(initialCapacity.coerceAtLeast(1))
    private var keys = DoubleArray(initialCapacity.coerceAtLeast(1))

    var size = 0
        private set

    fun isEmpty(): Boolean = size == 0

    fun push(
        node: Int,
        key: Double,
    ) {
        if (size == nodes.size) {
            nodes = nodes.copyOf(size * 2)
            keys = keys.copyOf(size * 2)
        }
        var i = size++
        // Sift up: move larger parents down until the new key fits.
        while (i > 0) {
            val parent = (i - 1) / 2
            if (keys[parent] <= key) break
            nodes[i] = nodes[parent]
            keys[i] = keys[parent]
            i = parent
        }
        nodes[i] = node
        keys[i] = key
    }

    /** Removes the entry with the smallest key and returns its node. Call only when the heap is not empty. */
    fun pop(): Int {
        check(size > 0) { "pop on an empty heap" }
        val top = nodes[0]
        size--
        if (size > 0) siftDown(nodes[size], keys[size])
        return top
    }

    /** Puts `(node, key)` at the root, then moves smaller children up until it fits. */
    private fun siftDown(
        node: Int,
        key: Double,
    ) {
        var i = 0
        var child = smallerChild(i)
        while (child != NO_CHILD && keys[child] < key) {
            nodes[i] = nodes[child]
            keys[i] = keys[child]
            i = child
            child = smallerChild(i)
        }
        nodes[i] = node
        keys[i] = key
    }

    /** The child of [i] with the smaller key, or [NO_CHILD] when [i] is a leaf. */
    private fun smallerChild(i: Int): Int {
        val left = 2 * i + 1
        return when {
            left >= size -> NO_CHILD
            left + 1 < size && keys[left + 1] < keys[left] -> left + 1
            else -> left
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val NO_CHILD = -1
    }
}
