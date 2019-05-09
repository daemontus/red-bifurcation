package sybila.scc.algo.basic

import sybila.scc.algo.*
import sybila.simulation.Params
import sybila.simulation.ParamsData
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class WeightModel(
    states: Int = 10000, params: Params = ParamsData(),
    weight: Interval = doubleArrayOf(0.1, 0.2),
    maxB: Double = 3750.0,
    override val solver: Solver<IParams> = ISolver()
) : Model<IParams>, Params by params {

    private val stateSpace: Interval = doubleArrayOf(0.0, maxB)

    override val stateCount: Int = states

    private val stateIntervals: Array<Interval> = run {
        val stateSize = (stateSpace.high - stateSpace.low) / states
        Array(states) { s ->
            if (s == states) doubleArrayOf(stateSpace.low + s*stateSize, stateSpace.high)
            else doubleArrayOf(stateSpace.low + s*stateSize, stateSpace.low + (s+1)*stateSize)
        }
    }

    fun stateInterval(s: State): Interval = stateIntervals[s]

    private val successors: Map<Int, List<Pair<State, IParams>>>
    private val predecessors: Map<Int, List<Pair<State, IParams>>>

    // monotonic increasing
    private fun dropRate(avrQueue: Double): Double = when {
        avrQueue < qMin -> 0.0
        avrQueue > qMax -> 1.0
        else -> ((avrQueue - qMin) / (qMax - qMin)) * pMax
    }

    private val pLow = run {
        val frac = (connections * packetSize * k) / (delay * linkCapacity + bufferSize * packetSize)
        frac * frac
    }

    private val pHigh = run {
        val frac = (connections * packetSize * k) / (delay * linkCapacity)
        frac * frac
    }

    // monotonic decreasing
    private fun currentQueue(dropRate: Double): Double = when {
        dropRate < pLow -> bufferSize
        dropRate > pHigh -> 0.0
        else -> {
            ((connections * k) / Math.sqrt(dropRate)) - ((linkCapacity * delay) / packetSize)
        }
    }

    private fun dropRate(avrQueue: Interval): Interval
            = doubleArrayOf(dropRate(avrQueue.low), dropRate(avrQueue.high))

    private fun currentQueue(dropRate: Interval): Interval
            = doubleArrayOf(currentQueue(dropRate.high), currentQueue(dropRate.low))    // reversed because decreasing

    init {
        // Compute transition function:
        val transitions: List<Triple<Int, Int, IParams>> = (0 until stateCount).toList().mapParallel { s ->
            if (s % 100 == 0) println("Transition progress $s/${stateCount}")
            val currentAvrQueue = stateIntervals[s]
            val queue = currentQueue(dropRate(currentAvrQueue))
            // First, filter all states that this can actually lead to.
            val maximalNextAvrQueue = ((1.0 - weight) * currentAvrQueue + weight * queue).restrict(stateSpace)
            val fromsState = maximalNextAvrQueue.low.find()
            val toState = maximalNextAvrQueue.high.find()
            (fromsState..toState).mapNotNull { t ->
                (stateIntervals[t] intersect maximalNextAvrQueue)?.let { nextAvrQueue ->
                    // nextAvrQueue = (1-w) * currentAvrQueue + w * queue ->
                    // w = (nextAvrQueue - currentAvrQueue) / (queue - currentAvrQueue)
                    val wList = ((nextAvrQueue - currentAvrQueue) / (queue - currentAvrQueue)).mapNotNull { it.round(4.0).asIParams(weight) }
                    if (wList.isEmpty()) null else {
                        val w = solver.run { wList.merge { a, b -> a or b } }
                        Triple(s, t, w)
                    }
                } // if it is null, the intersection is a single point - we don't care
            }
        }.flatten()
        successors = transitions.groupBy({ it.first }, { it.second to it.third }).withDefault { emptyList() }
        predecessors = transitions.groupBy({ it.second }, { it.first to it.third }).withDefault { emptyList() }
    }

    private fun Double.find(): Int {
        val find = this
        if (find < stateSpace.low) return 0
        if (find > stateSpace.high) return stateCount - 1
        var l = 0; var r = stateIntervals.size - 1
        while (true) {
            val mid = l + (r - l) / 2
            val midInterval = stateIntervals[mid]
            if (find in midInterval) return mid
            if (find in stateIntervals[l]) return l
            if (find in stateIntervals[r]) return r
            if (find < midInterval.low) {
                r = mid
            }
            if (find > midInterval.high) {
                l = mid
            }
            if (l >= r) error("WTF: cannot find $find")
        }
    }

    override fun successors(s: State): List<Pair<State, IParams>> = successors.getValue(s)
    override fun predecessors(s: State): List<Pair<State, IParams>> = predecessors.getValue(s)
}

fun main() {
    val start = System.currentTimeMillis()
    val minX = 0.1
    val maxX = 0.2
    val maxB = 1000.0
    val image = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
    val model = WeightModel(weight = doubleArrayOf(minX, maxX), maxB = maxB)
    model.findComponents { component ->
        //println("==== Component ===")
        for (s in 0 until model.stateCount) {
            val p = component.getOrNull(s)
            if (p != null) {
                val pixelYLow = Math.floor(1000 * model.stateInterval(s).low / maxB).toInt()
                val pixelYHigh = Math.ceil(1000 * model.stateInterval(s).high / maxB).toInt()
                for (pixelY in pixelYLow..pixelYHigh) {
                    p.drawRow(image = image, y = 1000 - pixelY, pixelsX = 1000, min = minX, max = maxX)
                }
                //println("State $s:${model.stateInterval(s).asString()} for $p")
            }
        }
    }

    println("Elapsed: ${System.currentTimeMillis() - start}")

    println("Write image...")
    ImageIO.write(image, "PNG", File("synth_2.png"))
}