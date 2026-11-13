package veeci.practicing.rts.routing.domain.search

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class IntDoubleHeapTest {
    @Test
    fun `pops keys in ascending order and grows past its initial capacity`() =
        runTest {
            val keyLists = Arb.list(Arb.double(0.0, 1e6, includeNaNs = false), 0..300)
            checkAll(PropTestConfig(seed = 20_261_113L), keyLists) { keys ->
                val heap = IntDoubleHeap(initialCapacity = 4)
                keys.forEachIndexed { node, key -> heap.push(node, key) }

                val popped = generateSequence { if (heap.isEmpty()) null else keys[heap.pop()] }.toList()

                // Primitive <= treats -0.0 and 0.0 as equal keys, so check order that way, not with sorted().
                popped.zipWithNext().all { (a, b) -> a <= b } shouldBe true
                popped.sorted() shouldContainExactly keys.sorted()
            }
        }

    @Test
    fun `a node pushed twice comes out twice, smaller key first`() {
        val heap = IntDoubleHeap()
        heap.push(7, 10.0)
        heap.push(3, 5.0)
        heap.push(7, 2.0)

        heap.pop() shouldBe 7
        heap.pop() shouldBe 3
        heap.pop() shouldBe 7
        heap.isEmpty() shouldBe true
    }

    @Test
    fun `pop on an empty heap fails`() {
        shouldThrow<IllegalStateException> { IntDoubleHeap().pop() }
    }
}
