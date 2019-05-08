package sybila.simulation

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class BasicREDSimulation(
    params: Params
) : Params by params {

    val alpha = 0.005
    val beta = 0.9

    fun nextPMax(avrQueue: Double, pMax: Double) = when {
        avrQueue < qTarget -> pMax + alpha
        avrQueue > qTarget -> pMax * beta
        else -> pMax
    }

    // BASIC
    fun dropRate(avrQueue: Double, pMax: Double): Double = when {
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

    fun currentQueue(dropRate: Double): Double = when {
        dropRate < pLow -> bufferSize
        dropRate > pHigh -> 0.0
        else -> {
            ((connections * k) / Math.sqrt(dropRate)) - ((linkCapacity * delay) / packetSize)
        }
    }

    fun nextAvrQueue(avrQueue: Double, pMax: Double): Double = (1.0 - weight) * avrQueue + weight * currentQueue(dropRate(avrQueue, pMax))

}

val algo = "adaptive"

fun BufferedImage.writeSimulation(x: Int, minY: Double, maxY: Double, simulation: BasicREDSimulation) {
    var avrQueue: Double = simulation.bufferSize / 2.0
    var pMax: Double = 0.1

    // first, stabilise the simulation
    repeat(1_000_000) {
        val next = simulation.nextAvrQueue(avrQueue, pMax)
        pMax = simulation.nextPMax(avrQueue, pMax)
        //if (it % 1000 == 0) println("Queue: $avrQueue (stable ${next == avrQueue})")
        avrQueue = next
    }

    // then draw
    repeat(1_000) {
        val next = simulation.nextAvrQueue(avrQueue, pMax)
        pMax = simulation.nextPMax(avrQueue, pMax)
        val pixel = (1000.0 * ((next - minY)/maxY)).toInt()
        if (pixel < 0 || pixel >= 1000.0) error("Invalid pixel: $pixel")
        setRGB(x, 1000 - pixel - 1, Color.WHITE.rgb)
        avrQueue = next
    }
}

fun main() {
    simulation("delay", 0.001, 1.0) { IParams(delay = it) }
    println("Done delay")
    simulation("packet", 1024.0, 8192.0) { IParams(packetSize = it) }
    println("Done packet")
    //simulation("pMax", 0.05, 0.3) { IParams(pMax = it) }
    //println("Done pMax")
    simulation("qMax", 500.0, 900.0) { IParams(qMax = it) }
    println("Done qMax")
    simulation("qMin", 100.0, 500.0) { IParams(qMin = it) }
    println("Done qMin")
    simulation("connections", 200.0, 300.0) { IParams(connections = it) }
    println("Done connections")
    simulation("weight", 0.1, 0.2) { IParams(weight = it) }
    println("Done weight")
}

fun simulation(prop: String, min: Double, max: Double, minY: Double = 0.0, maxY: Double = 1500.0, builder: (Double) -> Params) {
    val image = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
    val step = (max - min) / 1000
    for (x in 0 until 1000) {
        if (x % 100 == 0) println("Progress $x/1000")
        val value = min + step * x
        val simulation = BasicREDSimulation(builder(value))
        image.writeSimulation(x, minY, maxY, simulation)
    }

    ImageIO.write(image, "PNG", File("${algo}_$prop[$min,$max]_B[${minY.toInt()},${maxY.toInt()}].png"))
}