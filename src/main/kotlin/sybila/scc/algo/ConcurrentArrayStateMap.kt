package sybila.scc.algo

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

class ConcurrentArrayStateMap<P : Any>(
    val capacity: Int, private val solver: Solver<P>
) {

    private val sizeAtomic = AtomicInteger(0)

    val size: Int
        get() = sizeAtomic.get()

    private val data = AtomicReferenceArray<P?>(capacity)

    fun getOrNull(state: State): P? = data[state]

    fun get(state: State): P = data[state] ?: solver.zero

    fun union(state: State, value: P): Boolean {
        solver.run {
            if (value.isEmpty()) return false
            var current: P?
            do {
                current = data[state]
                val c = current ?: zero
                val union = c or value
                if (current != null && union subset current) return false
            } while (!data.compareAndSet(state, current, union))
            if (current == null) sizeAtomic.incrementAndGet()
            return true
        }
    }


    override fun toString(): String {
        return (0 until capacity).mapNotNull { data.get(it)?.let { p -> it to p } }.joinToString()
    }
}