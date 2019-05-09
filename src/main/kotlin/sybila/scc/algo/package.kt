package sybila.scc.algo

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias State = Int                   // Each state is represented by its integer ID
typealias Interval = DoubleArray        // Interval is a two element array of doubles
typealias StateMap<P> = ConcurrentArrayStateMap<P>

val parallelism = Runtime.getRuntime().availableProcessors()
val pool: ExecutorService = Executors.newWorkStealingPool(parallelism)

fun <T, R> List<T>.mapParallel(action: (T) -> R): List<R> {
    return this.map {
        pool.submit<R> { action(it) }
    }.map { it.get() }
}

fun parallel(action: () -> Unit) {
    (1..parallelism).map {
        pool.submit(action)
    }.map { it.get() }
}

inline fun <T> List<T>.mergePairs(merge: (T, T) -> T): List<T> {
    val result = ArrayList<T>(this.size + 1)
    var i = 0
    while (i+1 < size) {
        result.add(merge(this[i], this[i+1]))
        i += 2
    }
    if (size % 2 == 1) {
        result.add(this.last())
    }
    return result
}

interface Solver<P> {

    val zero: P
    val one: P

    infix fun P.and(that: P): P
    infix fun P.or(that: P): P
    infix fun P.subset(that: P): Boolean
    fun P.not(): P

    fun P.isEmpty(): Boolean
    fun P.isNotEmpty(): Boolean

    fun List<P>.merge(action: (P, P) -> P): P {
        var items = this
        while (items.size > 1) {
            items = items.mergePairs(action)
        }
        return items[0]
    }

}

interface Model<P : Any> {

    val stateCount: Int
    val solver: Solver<P>

    fun successors(s: State): List<Pair<State, P>>
    fun predecessors(s: State): List<Pair<State, P>>

    fun makeStateMap(): StateMap<P> = StateMap(stateCount, solver)

    fun findComponents(onComponents: (StateMap<P>) -> Unit) = solver.run {
        // First, detect all sinks - this will prune A LOT of state space...
        val sinks = makeStateMap()
        println("Detecting sinks!")
        (0 until stateCount).toList().mapParallel { s ->
            if (s%10000 == 0) println("Sink progress $s/$stateCount")
            val hasNext = successors(s)
                .mapNotNull { (t, p) -> p.takeIf { t != s } }   // skip loops
                .merge { a, b -> a or b }
            val isSink = hasNext.not()
            if (isSink.isNotEmpty()) {
                sinks.union(s, isSink)
                val map = makeStateMap()
                map.union(s, isSink)
                onComponents(map)
            }
        }
        val canReachSink = sinks.reachBackward()
        //val canReachSink = newMap()
        val workQueue = ArrayList<StateMap<P>>()
        val groundZero = canReachSink.invert()
        if (groundZero.size > 0) workQueue.add(groundZero)
        while (workQueue.isNotEmpty()) {
            val universe = workQueue.removeAt(workQueue.lastIndex)
            println("Universe state count: ${universe.size} Remaining work queue: ${workQueue.size}")
            val pivots = universe.findPivots()
            println("Pivots state count: ${pivots.size}")

            // Find universe of terminal components reachable from pivot (and the component containing pivot)
            val forward = pivots.reachForward(universe)
            val currentComponent = pivots.reachBackward(forward)
            val reachableTerminalComponents = forward.subtract(currentComponent)

            // current component can be terminal for some subset of parameters
            val terminal = allColours(reachableTerminalComponents).not()

            if (terminal.isNotEmpty()) {
                onComponents(currentComponent.restrict(terminal))
            }

            if (reachableTerminalComponents.size > 0) {
                workQueue.add(reachableTerminalComponents)
            }

            // Find universe of terminal components not reachable from pivot
            val basinOfReachableComponents = forward.reachBackward(universe)
            val unreachableComponents = universe.subtract(basinOfReachableComponents)
            if (unreachableComponents.size > 0) {
                workQueue.add(unreachableComponents)
            }
        }
    }

    private fun StateMap<P>.reachBackward(guard: StateMap<P>? = null): StateMap<P> {
        val shouldUpdate = RepeatingConcurrentStateQueue(stateCount)
        val result = makeStateMap()
        // init reach
        for (s in 0 until stateCount) {
            val c = this.getOrNull(s)
            if (c != null) {
                result.union(s, this.get(s))
                shouldUpdate.set(s)
            }
        }
        println("Start reach backward.")
        // repeat
        parallel {
            var state = shouldUpdate.next(0)
            while (state > -1) {
                while (state > -1) {
                    solver.run {
                        for ((source, edgeParams) in predecessors(state)) {
                            // bring colors from source state, bounded by guard
                            val bound = if (guard == null) result.get(state) else {
                                result.get(state) and guard.get(source)
                            }
                            // update target -> if changed, mark it as working
                            val changed = result.union(source, edgeParams and bound)
                            if (changed) {
                                shouldUpdate.set(source)
                            }
                        }
                    }
                    state = shouldUpdate.next(state + 1)
                }
                // double check - maybe someone added another thing
                state = shouldUpdate.next(0)
            }
        }

        return result
    }

    private fun StateMap<P>.reachForward(guard: StateMap<P>? = null): StateMap<P> {
        val shouldUpdate = RepeatingConcurrentStateQueue(stateCount)
        val result = makeStateMap()
        // init reach
        for (s in 0 until stateCount) {
            val c = this.getOrNull(s)
            if (c != null) {
                result.union(s, this.get(s))
                shouldUpdate.set(s)
            }
        }
        println("Start reach forward.")
        // repeat
        parallel {
            var state = shouldUpdate.next(0)
            while (state > -1) {
                while (state > -1) {
                    solver.run {
                        for ((target, edgeParams) in successors(state)) {
                            // bring colors from source state, bounded by guard
                            val bound = if (guard == null) result.get(state) else {
                                result.get(state) and guard.get(target)
                            }
                            // update target -> if changed, mark it as working
                            val changed = result.union(target, edgeParams and bound)
                            if (changed) {
                                shouldUpdate.set(target)
                            }
                        }
                    }
                    state = shouldUpdate.next(state + 1)
                }
                state = shouldUpdate.next(0)
            }
        }

        return result
    }

    private fun StateMap<P>.subtract(that: StateMap<P>): StateMap<P> = solver.run {
        val left = this@subtract
        val result = makeStateMap()
        (0 until stateCount).toList()
            .map { s -> Triple(s, left.get(s), that.get(s)) }
            .mapParallel { (s, a, b) ->
                (s to (a and b.not()) ).takeIf { it.second.isNotEmpty() }
            }
            .filterNotNull()
            .forEach { (s, p) ->
                result.union(s, p)
            }
        return result
    }

    private fun StateMap<P>.invert(): StateMap<P> = solver.run {
        val result = makeStateMap()
        (0 until stateCount).toList()
            .map { s -> s to get(s) }
            .mapParallel { (s, c) ->
                (s to solver.run { c.not() }).takeIf { it.second.isNotEmpty() }
            }
            .filterNotNull()
            .forEach { (s, p) ->
                result.union(s, p)
            }
        return result
    }

    private fun StateMap<P>.restrict(colours: P): StateMap<P> {
        val result = makeStateMap()
        (0 until stateCount).toList()
            .mapNotNull { s -> getOrNull(s)?.let { s to it } }
            .mapParallel { (s, c) ->
                s to solver.run { c and colours }
            }
            .forEach { (s, p) ->
                result.union(s, p)
            }
        return result
    }

    private fun allColours(map: StateMap<P>): P = solver.run {
        val list = (0 until stateCount).mapNotNull { map.getOrNull(it) }
        return if (list.isEmpty()) zero else {
            list.merge { a, b -> a or b }
        }
    }

    fun StateMap<P>.findPivots(): StateMap<P> = solver.run {
        val map = this@findPivots
        val result = makeStateMap()
        var toCover = allColours(map)
        var remaining = (0 until stateCount)
            .mapNotNull { s -> map.getOrNull(s)?.let { s to (it) } }
        while (toCover.isNotEmpty()) {
            // there must be a gain in the first element of remaining because we remove all empty elements
            val (s, gain) = remaining.first().let { (s, p) -> s to (p and toCover) }
            toCover = toCover and gain.not()
            result.union(s, gain)
            remaining = remaining.mapParallel { (s, p) ->
                (s to (p and toCover)).takeIf { it.second.isNotEmpty() }
            }.filterNotNull()
        }
        result
    }

}