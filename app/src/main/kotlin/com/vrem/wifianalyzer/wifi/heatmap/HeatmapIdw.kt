/*
 * WiFiAnalyzer
 * Copyright (C) 2015 - 2026 VREM Software Development <VREMSoftwareDevelopment@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */
package com.vrem.wifianalyzer.wifi.heatmap

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Inverse distance weighting (power 2) over the readings, limited to a fixed influence radius.
 * All coordinates are in grid-cell units, a reading sits at the centre of its cell.
 * Where no reading is within the radius the result is null, so that area stays neutral (no invented data).
 */
object HeatmapIdw {
    const val RSSI_WEAK = -90
    const val RSSI_STRONG = -45
    const val INFLUENCE_CELLS = 2.5f
    const val CORE_FRACTION = 0.4f
    private const val EXACT = 0.0001f

    class Sample(
        val x: Float,
        val y: Float,
        val rssi: Int,
    )

    class Result(
        val rssi: Float,
        val alpha: Float,
    )

    fun fraction(rssi: Float): Float = ((rssi - RSSI_WEAK) / (RSSI_STRONG - RSSI_WEAK).toFloat()).coerceIn(0f, 1f)

    fun interpolate(
        samples: List<Sample>,
        x: Float,
        y: Float,
        radius: Float = INFLUENCE_CELLS,
    ): Result? {
        val radiusSquared = radius * radius
        var weightSum = 0f
        var weightedSum = 0f
        var nearest = Float.MAX_VALUE
        for (sample in samples) {
            val dx = sample.x - x
            val dy = sample.y - y
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared > radiusSquared) continue
            if (distanceSquared < EXACT) return Result(sample.rssi.toFloat(), 1f)
            val weight = 1f / distanceSquared
            weightSum += weight
            weightedSum += weight * sample.rssi
            if (distanceSquared < nearest) nearest = distanceSquared
        }
        if (weightSum == 0f) return null
        val core = radius * CORE_FRACTION
        val distance = sqrt(nearest)
        val alpha = if (distance <= core) 1f else (1f - (distance - core) / (radius - core)).coerceIn(0f, 1f)
        return Result(weightedSum / weightSum, alpha)
    }
}

object HeatmapColors {
    private const val HUE_GREEN = 120f
    private const val HSV_SIZE = 3

    fun color(
        rssi: Float,
        alpha: Int,
        hsv: FloatArray = FloatArray(HSV_SIZE),
    ): Int {
        hsv[0] = HeatmapIdw.fraction(rssi) * HUE_GREEN
        hsv[1] = 1f
        hsv[2] = 1f
        return Color.HSVToColor(alpha, hsv)
    }
}

object HeatmapRenderer {
    private const val RENDER_MAX_SIDE = 200
    private const val MIN_SCALE = 4
    private const val HEAT_ALPHA = 170
    private const val HSV_SIZE = 3

    fun render(
        readings: Map<Cell, Int>,
        columns: Int,
        rows: Int,
    ): Bitmap? {
        if (readings.isEmpty() || columns < 1 || rows < 1) return null
        val scale = (RENDER_MAX_SIDE / max(columns, rows)).coerceAtLeast(MIN_SCALE)
        val width = columns * scale
        val height = rows * scale
        val samples = readings.map { (cell, rssi) -> HeatmapIdw.Sample(cell.column + 0.5f, cell.row + 0.5f, rssi) }
        val hsv = FloatArray(HSV_SIZE)
        val pixels = IntArray(width * height)
        for (j in 0 until height) {
            val y = (j + 0.5f) / scale
            for (i in 0 until width) {
                val x = (i + 0.5f) / scale
                val result = HeatmapIdw.interpolate(samples, x, y) ?: continue
                pixels[j * width + i] = HeatmapColors.color(result.rssi, (result.alpha * HEAT_ALPHA).toInt(), hsv)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
