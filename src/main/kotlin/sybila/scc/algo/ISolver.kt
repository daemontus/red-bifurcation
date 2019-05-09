package sybila.scc.algo

import java.awt.Color
import java.awt.image.BufferedImage
import java.lang.StringBuilder
import java.util.*
import kotlin.collections.ArrayList

/**
 * Represents a set of interval cells.
 */
class IParams(
    val thresholds: DoubleArray,
    val valid: BitSet
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IParams

        if (!thresholds.contentEquals(other.thresholds)) return false
        if (valid != other.valid) return false

        return true
    }

    override fun hashCode(): Int {
        var result = thresholds.contentHashCode()
        result = 31 * result + valid.hashCode()
        return result
    }

    fun drawRow(image: BufferedImage, y: Int, pixelsX: Int, min: Double, max: Double) {
        if (thresholds.isEmpty()) {
            if (valid[0]) {
                for (x in 0 until pixelsX) image.setRGB(x,y, Color.WHITE.rgb)
            }
            return
        }
        // there are at least two cells at this point
        var x = 0
        for (cell in 0 until thresholds.size) {
            val cellEnd = thresholds[cell]
            val cellEndPixel = kotlin.math.min(pixelsX, (pixelsX.toDouble() * (cellEnd - min)/(max - min)).toInt())
            //println("cell end: $cellEnd as $cellEndPixel")
            val valid = valid[cell]
            while (x <= cellEndPixel) {
                if (valid) image.setRGB(x,y, Color.WHITE.rgb)
                x += 1
            }
        }
        // handle last cell
        if (valid[thresholds.size]) {
            while (x < pixelsX) {
                image.setRGB(x,y, Color.WHITE.rgb)
                x += 1
            }
        }
    }

    override fun toString(): String {
        val s = StringBuilder()
        s.append("[")
        for (cell in 0..thresholds.size) {
            if (cell != 0) s.append("|${thresholds[cell - 1]}|")
            s.append(if (valid.get(cell)) "*" else "_")
        }
        s.append("]")
        return s.toString()
    }
}

fun Interval.asIParams(bounds: Interval): IParams? {
    if (this.low.isNaN() || this.high.isNaN()) return IParams(DoubleArray(0), BitSet().apply { set(0) })
    return (bounds intersect this)?.let { intersection ->
        when {
            intersection.low == bounds.low && intersection.high == bounds.high -> {
                IParams(doubleArrayOf(), BitSet().apply { set(0) })
            }
            intersection.low == bounds.low -> {
                IParams(doubleArrayOf(intersection.high), BitSet().apply { set(0) })
            }
            intersection.high == bounds.high -> {
                IParams(doubleArrayOf(intersection.low), BitSet().apply { set(1) })
            }
            else -> {
                IParams(doubleArrayOf(intersection.low, intersection.high), BitSet().apply { set(1) })
            }
        }
    }
}

class ISolver : Solver<IParams> {

    override val zero: IParams = IParams(DoubleArray(0), BitSet())
    override val one: IParams = IParams(DoubleArray(0), BitSet().apply { set(0) })

    override fun IParams.and(that: IParams): IParams {
        if (this.isEmpty() || that.isEmpty()) return zero
        val allThresholds = this.thresholds.merge(that.thresholds)
        val allValid = BitSet()
        var iThis = 0; var iThat = 0    // i points to the corresponding cell in the original set
        for (cell in 0 until allThresholds.size) {
            if (this.valid[iThis] && that.valid[iThat]) allValid.set(cell)
            // thresholds[i] is the upper threshold of the original cell - if it is the threshold of this
            // merged cell, then move to next cell.
            if (iThis < this.thresholds.size && this.thresholds[iThis] == allThresholds[cell]) iThis += 1
            if (iThat < that.thresholds.size && that.thresholds[iThat] == allThresholds[cell]) iThat += 1
        }
        // handle last cell explicitly
        run {
            val cell = allThresholds.size
            if (this.valid[iThis] && that.valid[iThat]) allValid.set(cell)
        }
        if (valid.isEmpty) return zero
        //println("$this and $that =...= ${IParams(allThresholds, allValid)}")
        return simplify(allThresholds, allValid)
    }

    override fun IParams.or(that: IParams): IParams {
        if (this.isEmpty()) return that
        if (that.isEmpty()) return this
        val allThresholds = this.thresholds.merge(that.thresholds)
        val allValid = BitSet()
        var iThis = 0; var iThat = 0    // i points to the corresponding cell in the original set
        for (cell in 0 until allThresholds.size) {
            if (this.valid[iThis] || that.valid[iThat]) allValid.set(cell)
            // thresholds[i] is the upper threshold of the original cell - if it is the threshold of this
            // merged cell, then move to next cell.
            if (iThis < this.thresholds.size && this.thresholds[iThis] == allThresholds[cell]) iThis += 1
            if (iThat < that.thresholds.size && that.thresholds[iThat] == allThresholds[cell]) iThat += 1
        }
        // handle last cell explicitly
        run {
            val cell = allThresholds.size
            if (this.valid[iThis] || that.valid[iThat]) allValid.set(cell)
        }
        if (valid.isEmpty) return zero
        return simplify(allThresholds, allValid)
    }

    override fun IParams.subset(that: IParams): Boolean {
        if (this.isEmpty()) return true     // empty is subset of everything
        if (that.isEmpty()) return false    // but nothing is subset of empty (except for empty handled above)
        val allThresholds = this.thresholds.merge(that.thresholds)
        var iThis = 0; var iThat = 0    // i points to the corresponding cell in the original set
        for (cell in 0 until allThresholds.size) {
            // we are testing a => b, i.e. !a || b
            // this has to hold everywhere, so if it does not hold in some cell, return false
            if (this.valid[iThis] && !that.valid[iThat]) return false
            // thresholds[i] is the upper threshold of the original cell - if it is the threshold of this
            // merged cell, then move to next cell.
            if (iThis < this.thresholds.size && this.thresholds[iThis] == allThresholds[cell]) iThis += 1
            if (iThat < that.thresholds.size && that.thresholds[iThat] == allThresholds[cell]) iThat += 1
        }
        // handle last cell explicitly
        if (this.valid[iThis] && !that.valid[iThat]) return false
        return true
    }

    override fun IParams.not(): IParams {
        if (this.isEmpty()) return one
        val newValid = this.valid.clone() as BitSet
        newValid.flip(0, thresholds.size + 1)   // t thresholds creates t + 1 cells because we also have bounds
        return IParams(thresholds, newValid)
    }

    override fun IParams.isEmpty(): Boolean = this.valid.isEmpty

    override fun IParams.isNotEmpty(): Boolean = !this.valid.isEmpty

    private fun simplify(thresholds: DoubleArray, valid: BitSet): IParams {
        val reducedThresholds = ArrayList<Double>()
        val reducedValid = BitSet()
        for (cellBefore in thresholds.indices) {
            // check if threshold is necessary
            val cellAfter = cellBefore+1
            if (valid[cellBefore] != valid[cellAfter]) {
                // index of cell ending at the threshold which we end shortly
                val reducedCell = reducedThresholds.size
                reducedThresholds.add(thresholds[cellBefore])
                if (valid[cellBefore]) {
                    reducedValid.set(reducedCell)
                }
            }
        }
        if (valid[thresholds.size]) {
            // if last cell is valid, mark it as valid in the reduced set as well
            reducedValid.set(reducedThresholds.size)
        }
        return IParams(reducedThresholds.toDoubleArray(), reducedValid)
    }

    // merge two sorted double arrays
    private fun DoubleArray.merge(that: DoubleArray): DoubleArray {
        val result = ArrayList<Double>()
        var iThis = 0
        var iThat = 0
        while (iThis < this.size && iThat < that.size) {
            when {
                this[iThis] < that[iThat] -> {
                    result.add(this[iThis]); iThis += 1
                }
                this[iThis] > that[iThat] -> {
                    result.add(that[iThat]); iThat += 1
                }
                else -> {
                    result.add(this[iThis])
                    iThis += 1
                    iThat += 1
                }
            }
        }
        while (iThis < this.size) {
            result.add(this[iThis]); iThis += 1
        }
        while (iThat < that.size) {
            result.add(that[iThat]); iThat += 1
        }
        return result.toDoubleArray()
    }
}