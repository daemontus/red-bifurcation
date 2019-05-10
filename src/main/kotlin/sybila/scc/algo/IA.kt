package sybila.scc.algo

import java.util.*

/*
    Implementation of interval arithmetic (used when computing abstraction)
 */

val Interval.low
    get() = this[0]
val Interval.high
    get() = this[1]

operator fun Interval.plus(that: Interval): Interval = doubleArrayOf(
    this.low + that.low, this.high + that.high
)

operator fun Interval.minus(that: Interval): Interval = doubleArrayOf(
    this.low - that.high, this.high - that.low
)

operator fun Interval.times(that: Interval): Interval = doubleArrayOf(
    min(this.low * that.low, this.low * that.high, this.high * that.low, this.high * that.high),
    max(this.low * that.low, this.low * that.high, this.high * that.low, this.high * that.high)
)

fun Interval.round(places: Double): Interval {
    val precision = Math.pow(10.0, places)
    return doubleArrayOf(low.roundDown(precision), high.roundUp(precision))
}

private fun Double.roundUp(precision: Double) = Math.ceil(this * precision) / precision
private fun Double.roundDown(precision: Double) = Math.floor(this * precision) / precision


val Interval.mid: Double
    get() = low + (high - low) / 2.0

operator fun Double.minus(that: Interval): Interval = doubleArrayOf(
    this - that.high, this - that.low
)

operator fun Double.times(that: Interval): Interval = doubleArrayOf(
    min(this * that.low, this * that.high), max(this * that.high, this * that.low)
)

operator fun Double.plus(that: Interval): Interval = doubleArrayOf(
    this + that.low, this + that.high
)

fun Interval.boundDown(bound: Double): Interval = when {
    bound <= low -> this
    high <= bound -> doubleArrayOf(bound, bound)
    else -> doubleArrayOf(bound, high)
}

fun Interval.boundUp(bound: Double): Interval = when {
    high <= bound -> this
    bound <= low -> doubleArrayOf(bound, bound)
    else -> doubleArrayOf(low, bound)
}

fun Interval.restrict(bounds: Interval): Interval {
    val low = max(this.low, bounds.low)
    val high = min(this.high, bounds.high)
    if (low >= high) error("$this not within $bounds")
    return doubleArrayOf(low, high)
}

fun Interval.inverse(): List<Interval> {
    val x1 = this.low; val x2 = this.high
    return if (x1 == 0.0 && x2 == 0.0) {
        // Division by pure zero, we are screwed
        listOf(doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY))
    } else if (x1 == 0.0) {
        // 0.0 .... x2
        listOf(doubleArrayOf(1.0/x2, Double.POSITIVE_INFINITY))
    } else if (x2 == 0.0) {
        // x1 .... 0.0
        listOf(doubleArrayOf(Double.NEGATIVE_INFINITY, 1.0/x1))
    } else if (x1 < 0.0 && 0.0 < x2) {
        // x1 ... 0.0 ... x2 - SPLIT!
        listOf(
            doubleArrayOf(Double.NEGATIVE_INFINITY, 1.0/x1),
            doubleArrayOf(1.0/x2, Double.POSITIVE_INFINITY)
        )
    } else {
        // 0.0 ... x1 ... x2 or x1 ... x2 ... 0.0 - switch values
        listOf(doubleArrayOf(1.0/x2, 1.0/x1))
    }
}

fun Interval.asString() = Arrays.toString(this)

infix fun Interval.intersect(that: Interval): Interval? {
    val low = max(this.low, that.low)
    val high = min(this.high, that.high)
    return if (low >= high) null
    else doubleArrayOf(low, high)
}

operator fun Interval.contains(number: Double): Boolean = this.low <= number && number <= this.high

operator fun Interval.div(y: Interval): List<Interval> = y.inverse().map { it * this }

private fun min(vararg x: Double): Double = x.fold(Double.POSITIVE_INFINITY) { a, i -> if (a < i) a else i }
private fun max(vararg x: Double): Double = x.fold(Double.NEGATIVE_INFINITY) { a, i -> if (a > i) a else i }
