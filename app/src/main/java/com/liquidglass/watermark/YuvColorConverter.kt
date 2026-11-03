package com.liquidglass.watermark

/** Fixed point SDR YUV conversion, using Android's color-standard values. */
internal class YuvColorConverter(standard: Int, fullRange: Boolean) {
    private val yScale = if (fullRange) 256 else 298
    private val yOffset = if (fullRange) 0 else 16
    private val coefficients = when (standard) {
        1 -> if (fullRange) intArrayOf(403, 48, 120, 475) else intArrayOf(459, 55, 136, 541) // 709
        6 -> if (fullRange) intArrayOf(377, 42, 146, 482) else intArrayOf(430, 48, 167, 548) // 2020
        else -> if (fullRange) intArrayOf(359, 88, 183, 454) else intArrayOf(409, 100, 208, 516) // 601
    }

    fun argb(y: Int, u: Int, v: Int): Int {
        val luminance = yScale * (y - yOffset)
        val cb = u - 128
        val cr = v - 128
        val r = ((luminance + coefficients[0] * cr + 128) shr 8).coerceIn(0, 255)
        val g = ((luminance - coefficients[1] * cb - coefficients[2] * cr + 128) shr 8).coerceIn(0, 255)
        val b = ((luminance + coefficients[3] * cb + 128) shr 8).coerceIn(0, 255)
        return 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
    }
}
