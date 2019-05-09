package sybila.simulation

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class FeedbackREDSimulation(
    params: Params
) : Params by params {

    private val mul = 0.25
    private val discount = 0.9998

    fun nextFeedback(feedback: Double, avrQueue: Double, nextAvrQueue: Double): Double {
        return (nextAvrQueue - avrQueue) + discount * feedback
    }

    // BASIC
    private fun dropRate(avrQueue: Double): Double = when {
        avrQueue < qMin -> 0.0
        avrQueue > qMax -> 1.0
        else -> ((avrQueue - qMin) / (qMax - qMin)) * pMax
    }

    /*
    GENTLE
    fun dropRate(averageQueue: Double): Double = when {
        averageQueue < qMin -> 0.0
        qMin <= averageQueue && averageQueue < qMax -> ((averageQueue - qMin) / (qMax - qMin)) * pMax
        qMax <= averageQueue && averageQueue < 2.0 * qMax -> {
            pMax + (1.0 - pMax)/(qMax) * (averageQueue - qMax)
        }
        else -> 1.0
    }*/

    private val pLow = run {
        val frac = (connections * packetSize * k) / (delay * linkCapacity + bufferSize * packetSize)
        frac * frac
    }

    private val pHigh = run {
        val frac = (connections * packetSize * k) / (delay * linkCapacity)
        frac * frac
    }

    private fun currentQueue(dropRate: Double): Double = when {
        dropRate < pLow -> bufferSize
        dropRate > pHigh -> 0.0
        else -> {
            ((connections * k) / Math.sqrt(dropRate)) - ((linkCapacity * delay) / packetSize)
        }
    }

    fun nextAvrQueue(avrQueue: Double, feedback: Double): Double {
        return (1.0 - weight) * avrQueue + weight * currentQueue(dropRate(avrQueue)) + mul * feedback
    }

}

private const val algo = "feedback"

private fun BufferedImage.writeSimulation(x: Int, minY: Double, maxY: Double, simulation: FeedbackREDSimulation) {
    var avrQueue: Double = simulation.bufferSize / 2.0
    var feedback = 0.0

    // first, stabilise the simulation
    repeat(1_000_000) {
        val next = simulation.nextAvrQueue(avrQueue, feedback)
        feedback = simulation.nextFeedback(feedback, avrQueue, next)
        //if (it % 1000 == 0) println("Queue: $avrQueue (stable ${next == avrQueue})")
        avrQueue = next
    }

    // then draw
    repeat(1_000) {
        val next = simulation.nextAvrQueue(avrQueue, feedback)
        feedback = simulation.nextFeedback(feedback, avrQueue, next)
        val pixel = (1000.0 * ((next - minY)/maxY)).toInt()
        if (pixel < 0 || pixel >= 1000.0) error("Invalid pixel: $pixel")
        setRGB(x, 1000 - pixel - 1, Color.WHITE.rgb)
        avrQueue = next
    }
}

fun main() {
    /*val simulation = FeedbackREDSimulation(ParamsData())
    var avrQueue: Double = simulation.bufferSize / 2.0
    var feedback = 0.0
    println("Feedback $feedback; Queue: $avrQueue")
    repeat(1_000) {
        val next = simulation.nextAvrQueue(avrQueue, feedback)
        feedback = simulation.nextFeedback(feedback, avrQueue, next)
        avrQueue = next
        println("Feedback $feedback; Queue: $avrQueue")
    }*/
    simulation("delay", 0.001, 1.0) { ParamsData(delay = it) }
    println("Done delay")
    simulation("packet", 1024.0, 8192.0) { ParamsData(packetSize = it) }
    println("Done packet")
    simulation("qMax", 500.0, 900.0) { ParamsData(qMax = it) }
    println("Done qMax")
    simulation("qMin", 100.0, 500.0) { ParamsData(qMin = it) }
    println("Done qMin")
    simulation("connections", 200.0, 300.0) { ParamsData(connections = it) }
    println("Done connections")
    simulation("weight", 0.1, 0.5, maxY = 2000.0) { ParamsData(weight = it) }
    println("Done weight")
}

private fun simulation(prop: String, min: Double, max: Double, minY: Double = 0.0, maxY: Double = 1500.0, builder: (Double) -> Params) {
    val image = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
    val step = (max - min) / 1000
    for (x in 0 until 1000) {
        if (x % 100 == 0) println("Progress $x/1000")
        val value = min + step * x
        val simulation = FeedbackREDSimulation(builder(value))
        image.writeSimulation(x, minY, maxY, simulation)
    }

    ImageIO.write(image, "PNG", File("${algo}_$prop[$min,$max]_B[${minY.toInt()},${maxY.toInt()}].png"))
}