package sybila.scc.algo.ared

import sybila.scc.algo.*
import sybila.simulation.Params
import sybila.simulation.ParamsData
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class WeightModel(
    private val states: Int = 500,
    private val pMaxThresholds: Int = 500,
    params: Params = ParamsData(),
    weight: Interval = doubleArrayOf(0.1, 0.2),
    maxB: Double = 3750.0,
    override val solver: Solver<IParams> = ISolver()
) : Model<IParams>, Params by params {

    private val pMaxBounds = doubleArrayOf(0.01,0.5)
    private val queueBounds: Interval = doubleArrayOf(0.0, maxB)

    override val stateCount: Int = states * pMaxThresholds

    private val queueIntervals: Array<Interval> = run {
        val stateSize = (queueBounds.high - queueBounds.low) / states
        Array(states) { s ->
            if (s == states) doubleArrayOf(queueBounds.low + s*stateSize, queueBounds.high)
            else doubleArrayOf(queueBounds.low + s*stateSize, queueBounds.low + (s+1)*stateSize)
        }
    }

    private val pMaxIntervals: Array<Interval> = run {
        val stateSize = (pMaxBounds.high - pMaxBounds.low) / pMaxThresholds
        Array(pMaxThresholds) { s ->
            if (s == states) doubleArrayOf(pMaxBounds.low + s*stateSize, pMaxBounds.high)
            else doubleArrayOf(pMaxBounds.low + s*stateSize, pMaxBounds.low + (s+1)*stateSize)
        }
    }

    fun makeState(queueIndex: Int, pMaxIndex: Int): Int = pMaxIndex * states + queueIndex
    fun queueInterval(s: State): Interval = queueIntervals[s % states]
    fun pMaxInterval(s: State): Interval = pMaxIntervals[s / states]

    private val successors: Map<Int, List<Pair<State, IParams>>>
    private val predecessors: Map<Int, List<Pair<State, IParams>>>

    // monotonic increasing
    private fun dropRate(avrQueue: Double, pMax: Interval): Interval = when {
        avrQueue < qMin -> doubleArrayOf(0.0, 0.0)
        avrQueue > qMax -> doubleArrayOf(1.0, 1.0)
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

    private fun dropRate(avrQueue: Interval, pMax: Interval): Interval
            = doubleArrayOf(dropRate(avrQueue.low, pMax).low, dropRate(avrQueue.high, pMax).high)

    private fun currentQueue(dropRate: Interval): Interval
            = doubleArrayOf(currentQueue(dropRate.high), currentQueue(dropRate.low))    // reversed because decreasing

    private val alpha = 0.005
    private val beta = 0.9

    private fun nextPMax(avrQueue: Interval, pMax: Interval): Interval {
        return when {
            qTarget < avrQueue.low -> (alpha + pMax).boundUp(pMaxBounds.high)
            avrQueue.high < qTarget -> (beta * pMax).boundDown(pMaxBounds.low)
            else -> doubleArrayOf(pMax.low * beta, pMax.high + alpha)   // inside target!
        }
    }

    init {
        // Compute transition function:
        val transitions: List<Triple<Int, Int, IParams>> = (0 until stateCount).toList().mapParallel { s ->
            if (s % 100 == 0) println("Transition progress $s/${stateCount}")
            val currentAvrQueue = queueInterval(s)
            val pMax = pMaxInterval(s)
            val nextPMax = nextPMax(currentAvrQueue, pMax)
            val pMaxFrom = nextPMax.low.findPMax()
            val pMaxTo = nextPMax.high.findPMax()
            val queue = currentQueue(dropRate(currentAvrQueue, pMax))
            // First, filter all states that this can actually lead to.
            val maximalNextAvrQueue = ((1.0 - weight) * currentAvrQueue + weight * queue).restrict(queueBounds)
            val fromsState = maximalNextAvrQueue.low.findQueue()
            val toState = maximalNextAvrQueue.high.findQueue()
            (fromsState..toState).mapNotNull { t ->
                (queueIntervals[t] intersect maximalNextAvrQueue)?.let { nextAvrQueue ->
                    // nextAvrQueue = (1-w) * currentAvrQueue + w * queue ->
                    // w = (nextAvrQueue - currentAvrQueue) / (queue - currentAvrQueue)
                    val wList = ((nextAvrQueue - currentAvrQueue) / (queue - currentAvrQueue)).mapNotNull { it.round(4.0).asIParams(weight) }
                    if (wList.isEmpty()) null else {
                        val w = solver.run { wList.merge { a, b -> a or b } }
                        (pMaxFrom..pMaxTo).map { p ->
                            Triple(s, makeState(t, p), w)
                        }
                    }
                } // if it is null, the intersection is a single point - we don't care
            }.flatten()
        }.flatten()
        successors = transitions.groupBy({ it.first }, { it.second to it.third }).withDefault { emptyList() }
        predecessors = transitions.groupBy({ it.second }, { it.first to it.third }).withDefault { emptyList() }
    }

    private fun Double.findQueue(): Int {
        val find = this
        if (find < queueBounds.low) return 0
        if (find > queueBounds.high) return states - 1
        var l = 0; var r = queueIntervals.size - 1
        while (true) {
            val mid = l + (r - l) / 2
            val midInterval = queueIntervals[mid]
            if (find in midInterval) return mid
            if (find in queueIntervals[l]) return l
            if (find in queueIntervals[r]) return r
            if (find < midInterval.low) {
                r = mid
            }
            if (find > midInterval.high) {
                l = mid
            }
            if (l >= r) error("WTF: cannot findQueue $find")
        }
    }

    private fun Double.findPMax(): Int {
        val find = this
        if (find < pMaxBounds.low) return 0
        if (find > pMaxBounds.high) return pMaxThresholds - 1
        var l = 0; var r = pMaxIntervals.size - 1
        while (true) {
            val mid = l + (r - l) / 2
            val midInterval = pMaxIntervals[mid]
            if (find in midInterval) return mid
            if (find in pMaxIntervals[l]) return l
            if (find in pMaxIntervals[r]) return r
            if (find < midInterval.low) {
                r = mid
            }
            if (find > midInterval.high) {
                l = mid
            }
            if (l >= r) error("WTF: cannot findQueue $find")
        }
    }

    override fun successors(s: State): List<Pair<State, IParams>> = successors.getValue(s)
    override fun predecessors(s: State): List<Pair<State, IParams>> = predecessors.getValue(s)
}

fun main() {
    val start = System.currentTimeMillis()
    val minX = 0.1
    val maxX = 0.5
    val maxB = 2000.0
    val image = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
    val model = WeightModel(weight = doubleArrayOf(minX, maxX), maxB = maxB)
    model.findComponents { component ->
        //println("==== Component ===")
        for (s in 0 until model.stateCount) {
            val p = component.getOrNull(s)
            if (p != null) {
                val pixelYLow = Math.floor(1000 * model.queueInterval(s).low / maxB).toInt()
                val pixelYHigh = Math.ceil(1000 * model.queueInterval(s).high / maxB).toInt()
                for (pixelY in pixelYLow..pixelYHigh) {
                    p.drawRow(image = image, y = 1000 - pixelY, pixelsX = 1000, min = minX, max = maxX)
                }
                //println("State $s:${model.queueInterval(s).asString()} for $p")
            }
        }
    }

    println("Elapsed: ${System.currentTimeMillis() - start}")

    println("Write image...")
    ImageIO.write(image, "PNG", File("ared.png"))
}