package sybila.simulation

data class ParamsData(
    override val connections: Double = 250.0,
    override val k: Double = Math.sqrt(1.5),
    override val linkCapacity: Double = 75.0 * 1000.0 * 1000.0,
    override val delay: Double = 0.1,
    override val packetSize: Double = 4.0 * 1000.0,
    override val weight: Double = 0.15,
    override val qMin: Double = 250.0,
    override val qMax: Double = 750.0,
    override val bufferSize: Double = 3750.0,
    override val pMax: Double = 0.1
) : Params

interface Params {
    val connections: Double
    val k: Double
    val linkCapacity: Double
    val delay: Double
    val packetSize: Double
    val weight: Double
    val qMin: Double
    val qMax: Double
    val bufferSize: Double
    val pMax: Double
    val qTarget: Double
            get() = qMin + (qMax - qMin) / 2
}