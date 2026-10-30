package com.cielvitrine.app

/** Pure sizing and generation rules shared by the Android photo renderer and JVM regressions. */
internal object PhotoPresentationRules {
    const val MAX_DECODE_PIXELS = 3_000_000L

    fun sampleFor(rawWidth: Int, rawHeight: Int, viewWidth: Int, viewHeight: Int, orientation: Int): Int {
        require(rawWidth > 0 && rawHeight > 0 && orientation in 1..8)
        val swapped = orientation in 5..8
        val targetWidth = (if (swapped) viewHeight else viewWidth).coerceAtLeast(1)
        val targetHeight = (if (swapped) viewWidth else viewHeight).coerceAtLeast(1)
        // A fit-centred image is constrained by its larger ratio. Round down here to
        // retain enough detail, then round UP separately for the hard allocation cap.
        val limit = maxOf(rawWidth.toDouble() / targetWidth, rawHeight.toDouble() / targetHeight)
        var sample = 1
        while (sample.toLong() * 2 <= limit) sample *= 2
        while (pixels(rawWidth, rawHeight, sample) > MAX_DECODE_PIXELS) sample *= 2
        return sample
    }

    fun pixels(width: Int, height: Int, sample: Int): Long =
        ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample)

    fun boundedDetailSample(width:Int,height:Int,requested:Int,availableBytes:Long):Int {
        require(width>0 && height>0 && requested>0)
        check(availableBytes>=16) { "Image working set occupied" }
        var sample=requested
        while(pixels(width,height,sample)>availableBytes/16)sample=Math.multiplyExact(sample,2)
        return sample
    }

    /** Translation that leaves the image point under a gesture centroid stationary. */
    fun focalOffset(offset: Float, focusFromCenter: Float, factor: Float, pan: Float): Float =
        offset * factor + focusFromCenter * (1f - factor) + pan

    fun orientedPoint(x: Float, y: Float, width: Int, height: Int, orientation: Int): Pair<Float, Float> = when(orientation) {
        1 -> x to y
        2 -> width - x to y
        3 -> width - x to height - y
        4 -> x to height - y
        5 -> y to x
        6 -> height - y to x
        7 -> height - y to width - x
        8 -> y to width - x
        else -> error("Invalid EXIF orientation")
    }
}

internal class PhotoGeneration {
    private var value = 0L
    @Synchronized fun advance(): Long { value = Math.addExact(value, 1L); return value }
    @Synchronized fun isCurrent(expected: Long): Boolean = value == expected
}
