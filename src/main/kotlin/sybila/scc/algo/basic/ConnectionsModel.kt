package sybila.scc.algo.basic

import sybila.scc.algo.*
import sybila.simulation.Params
import sybila.simulation.ParamsData
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.*
import javax.imageio.ImageIO

class ConnectionsModel(
    states: Int = 1000, params: Params = ParamsData(),
    private val connectionsInt: Interval = doubleArrayOf(200.0, 300.0),
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
        val frac = (1/(delay * linkCapacity + bufferSize * packetSize)) * (packetSize * k * connectionsInt)
        frac * frac
    }

    private val pHigh = run {
        val frac = (1/(delay * linkCapacity)) * (packetSize * k * connectionsInt)
        frac * frac
    }

    // monotonic decreasing
    /*private fun currentQueue(dropRate: Double): Double = when {
        dropRate < pLow -> bufferSize
        dropRate > pHigh -> 0.0
        else -> {
            ((connections * k) / Math.sqrt(dropRate)) - ((linkCapacity * delay) / packetSize)
        }
    }*/

    private fun dropRate(avrQueue: Interval): Interval
            = doubleArrayOf(dropRate(avrQueue.low), dropRate(avrQueue.high))

    private fun currentQueueMax(dropRate: Interval): Interval {
        val sub = (linkCapacity * delay) / packetSize
        val a = k * connectionsInt * doubleArrayOf(Math.sqrt(dropRate.low), Math.sqrt(dropRate.high)).inverse().let {
            if (it.size != 1) error("Wtf: inverse of ${doubleArrayOf(Math.sqrt(dropRate.low), Math.sqrt(dropRate.high))}")
            it.first()
        }
        return (a - doubleArrayOf(sub, sub)).boundDown(0.0).boundUp(bufferSize)
    }

    init {
        // Compute transition function:
        val transitions: List<Triple<Int, Int, IParams>> = (0 until stateCount).toList().mapParallel { s ->
            if (s % 100 == 0) println("Transition progress $s/${stateCount}")
            val currentAvrQueue = stateIntervals[s]
            val drop = dropRate(currentAvrQueue)
            val queue = currentQueueMax(dropRate(currentAvrQueue))
            // First, filter all states that this can actually lead to.
            val maximalNextAvrQueue = ((1.0 - weight) * currentAvrQueue + weight * queue)/*.also {
                if (it.low == Double.NEGATIVE_INFINITY) {
                    println("Current: $${Arrays.toString(currentAvrQueue)}; Drop rate ${Arrays.toString(dropRate(currentAvrQueue))}")
                    println("Queue: ${Arrays.toString(queue)}")
                }
            }*/.restrict(stateSpace)
            val fromsState = maximalNextAvrQueue.low.find()
            val toState = maximalNextAvrQueue.high.find()
            (fromsState..toState).mapNotNull { t ->
                (stateIntervals[t] intersect maximalNextAvrQueue)?.let { nextAvrQueue ->
                    // We have to consider three piece-wise cases. Fortunately, in two cases, the function is constant.
                    // And, another point: The two constant cases always leads to the same two values,
                    // it is therefore easy to test whether these two cases can lead

                    // We first determine thresholds in n such that drop <= pL and drop >= pU. These values
                    // will partition the parameter space into three regions where we subsequently test each function
                    // if it provides requested results.

                    // drop <= pL happens when drop is completely below p_low or they have an intersection
                    // drop[low] <= pL = ((n*m*k)/(d*c + m*B))^2
                    // sqrt(drop[low]) * ((d*c + m*B) / (m*k)) <= n
                    val thresholdLow = Math.sqrt(drop.low) * ((delay*linkCapacity + packetSize*bufferSize) / (packetSize*k))
                    // pU <= drop happens when drop is completely above pU or they have an intersection
                    // drop[high] >= pU = ((n*m*k)/(d*c))^2
                    // sqrt(drop[high]) * ((d*c)/(m*k)) >= n
                    val thresholdHigh = Math.sqrt(drop.high) * ((delay*linkCapacity)/(packetSize*k))

                    // tL <= n -> drop <= pL
                    val caseOne = doubleArrayOf(thresholdLow, Double.POSITIVE_INFINITY).intersect(connectionsInt)
                    // tL > n > tH -> pL <= drop <= pU, note that such value does not need to exist
                    val moreThan = Math.sqrt(drop.low) * ((delay*linkCapacity)/(packetSize*k))
                    val lessThan = Math.sqrt(drop.high) * ((delay*linkCapacity + bufferSize*packetSize)/(packetSize*k))
                    val caseTwo = doubleArrayOf(moreThan, lessThan).intersect(connectionsInt)
                    // n <= tU -> pU <= drop
                    val caseThree = doubleArrayOf(Double.NEGATIVE_INFINITY, thresholdHigh).intersect(connectionsInt)

                    // Now we have have the bounds on parameters, next we check if the transitions are even possible.

                    // First and last case is simple, we just check if the fixed new queue value can lead to a valid jump.
                    // Also, the function is monotonous increasing, so we just need to evaluate it.
                    var transitionParams = solver.zero
                    caseOne?.let { bound ->
                        //val result = ((1-w).toIR() times fromQ) plus (w * b).toIR()
                        val result = doubleArrayOf(
                            (1-weight) * currentAvrQueue.low + (weight*bufferSize),
                            (1-weight) * currentAvrQueue.high + (weight*bufferSize)
                        )
                        if (result.intersect(nextAvrQueue) != null) {
                            solver.run {
                                val p = bound.round(1.0).asIParams(connectionsInt)
                                if (p != null) transitionParams = transitionParams or p
                            }
                        }
                    }
                    caseThree?.let { bound ->
                        //val result = ((1-w).toIR() times fromQ)
                        val result = doubleArrayOf(
                            (1-weight) * currentAvrQueue.low,
                            (1-weight) * currentAvrQueue.high
                        )
                        if (result.intersect(nextAvrQueue) != null) {
                            solver.run {
                                val p = bound.round(1.0).asIParams(connectionsInt)
                                if (p != null) transitionParams = transitionParams or p
                            }
                        }
                    }
                    caseTwo?.let { bound ->
                        val pLPart = (packetSize*k/(delay*linkCapacity + bufferSize*packetSize)) * bound
                        val pUPart = (packetSize*k/(delay*linkCapacity)) * bound
                        val pL = pLPart * pLPart
                        val pU = pUPart * pUPart
                        val cutOffBottom = Math.max(drop.low, pL.low)
                        val cutOffTop = Math.min(drop.high, pU.high)
                        if (cutOffBottom < drop.high && cutOffTop > drop.low) {
                            val reducedDrop = doubleArrayOf(cutOffBottom, cutOffTop)
                            // Case two is a bit more tricky, since we also want to reduce the bound second time
                            // to remove parameters which can jump out of toQ.
                            // First we need to compute the value we want to obtain from our function in order to jump to toQ
                            // i.e. invert the rolling average.
                            // toQ = (1-w)*fromQ + w*nextQ
                            // (toQ - (1-w)*fromQ)/w = nextQ
                            val nextQ = doubleArrayOf(
                                (nextAvrQueue.low - (1-weight)*currentAvrQueue.low)/weight,
                                (nextAvrQueue.high - (1-weight)*currentAvrQueue.high)/weight
                            )
                            // Note: we know the function is monotone and decreasing in drop, hence we can simplify the reduction
                            // to the two endpoints of drop interval. We also know it is increasing in n (on the considered interval).
                            // nextQ = (n*k)/sqrt(drop) - c*d/m
                            // nextQ[low] = (n*k)/sqrt(drop[high]) - c*d/m
                            // (nextQ[low] + c*d/m) * sqrt(drop[high]) / k = n[low]
                            // (nextQ[high] + c*d/m) * sqrt(drop[low]) / k = n[high]
                            val sqrtP = doubleArrayOf(Math.sqrt(reducedDrop.low), Math.sqrt(reducedDrop.high))
                            val n = (1/k) * sqrtP * ((linkCapacity*delay/packetSize) + nextQ)
                            //val high = (nextQ.getL(0) + (c*d/m)) * Math.sqrt(reducedDrop.getH(0)) / k
                            //val low = (nextQ.getH(0) + (c*d/m)) * Math.sqrt(reducedDrop.getL(0)) / k
                            val restricted = n.intersect(bound)?.round(1.0)
                            if (restricted != null) solver.run {
                                val p = restricted.asIParams(connectionsInt)
                                if (p != null) transitionParams = transitionParams or p
                            }
                        }
                    }

                    if (solver.run { transitionParams.isNotEmpty() }) {
                        Triple(s, t, transitionParams)
                    } else null
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
    val minX = 100.0
    val maxX = 300.0
    val maxB = 900.0
    val image = BufferedImage(1000, 1500, BufferedImage.TYPE_INT_ARGB)
    val model = ConnectionsModel(connectionsInt = doubleArrayOf(minX, maxX), maxB = maxB)
    model.findComponents { component ->
        //println("==== Component ===")
        for (s in 0 until model.stateCount) {
            val p = component.getOrNull(s)
            if (p != null) {
                val pixelYLow = Math.floor(1500 * model.stateInterval(s).low / maxB).toInt()
                val pixelYHigh = Math.ceil(1500 * model.stateInterval(s).high / maxB).toInt()
                for (pixelY in pixelYLow..pixelYHigh) {
                    p.drawRow(image = image, y = 1500 - pixelY, pixelsX = 1000, min = minX, max = maxX)
                }
                //println("State $s:${model.queueInterval(s).asString()} for $p")
            }
        }
    }

    println("Elapsed: ${System.currentTimeMillis() - start}")

    println("Write image...")
    ImageIO.write(image, "PNG", File("basic_connections.png"))
}