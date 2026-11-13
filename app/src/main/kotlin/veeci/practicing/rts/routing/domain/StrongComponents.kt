package veeci.practicing.rts.routing.domain

/**
 * Strongly connected components with Tarjan's algorithm, written without recursion.
 *
 * A recursive version calls itself once per node on a long chain of roads. A few hundred thousand nested
 * calls overflow the JVM thread stack, so this version keeps its own call stack in two int arrays:
 * the node being visited and the next of its edges to try.
 */
internal object StrongComponents {
    /** Returns the nodes of the largest component as a mask. Ties go to the component found first. */
    fun largest(graph: RoadGraph): BooleanArray {
        val labels = Tarjan(graph).run()
        val sizes = IntArray(labels.count)
        labels.componentOf.forEach { sizes[it]++ }
        val biggest = sizes.indices.maxByOrNull { sizes[it] } ?: return BooleanArray(0)
        return BooleanArray(graph.nodeCount) { labels.componentOf[it] == biggest }
    }

    /** Component id of every node, from 0 until [count]. */
    class Labels(
        val componentOf: IntArray,
        val count: Int,
    )

    class Tarjan(
        private val graph: RoadGraph,
    ) {
        private val n = graph.nodeCount
        private val index = IntArray(n) { UNVISITED }
        private val low = IntArray(n)
        private val componentOf = IntArray(n) { UNVISITED }
        private val onStack = BooleanArray(n)
        private val stack = IntArray(n)
        private var stackSize = 0
        private val callNode = IntArray(n)
        private val callEdge = IntArray(n)
        private var callSize = 0
        private var nextIndex = 0
        private var componentCount = 0

        fun run(): Labels {
            for (start in 0 until n) {
                if (index[start] == UNVISITED) visitFrom(start)
            }
            return Labels(componentOf, componentCount)
        }

        private fun visitFrom(start: Int) {
            enter(start)
            while (callSize > 0) {
                val v = callNode[callSize - 1]
                val e = callEdge[callSize - 1]
                if (e < graph.edgeOffsets[v + 1]) {
                    callEdge[callSize - 1] = e + 1
                    val w = graph.edgeTargets[e]
                    if (index[w] == UNVISITED) {
                        enter(w)
                    } else if (onStack[w]) {
                        low[v] = minOf(low[v], index[w])
                    }
                } else {
                    leave(v)
                }
            }
        }

        /** The "call": number the node and push it on both stacks. */
        private fun enter(v: Int) {
            index[v] = nextIndex
            low[v] = nextIndex
            nextIndex++
            stack[stackSize++] = v
            onStack[v] = true
            callNode[callSize] = v
            callEdge[callSize] = graph.edgeOffsets[v]
            callSize++
        }

        /** The "return": close a component if [v] is its root, then pass [v]'s low link to its caller. */
        private fun leave(v: Int) {
            callSize--
            if (low[v] == index[v]) {
                do {
                    val w = stack[--stackSize]
                    onStack[w] = false
                    componentOf[w] = componentCount
                } while (w != v)
                componentCount++
            }
            if (callSize > 0) {
                val parent = callNode[callSize - 1]
                low[parent] = minOf(low[parent], low[v])
            }
        }

        private companion object {
            const val UNVISITED = -1
        }
    }
}
