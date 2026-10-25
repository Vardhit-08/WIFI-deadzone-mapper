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

import androidx.annotation.StringRes
import com.vrem.wifianalyzer.R
import kotlin.math.max
import kotlin.math.roundToInt

const val MINIMUM_READINGS = 3

enum class GridSize(
    @get:StringRes @param:StringRes val textResource: Int,
    val columns: Int,
) {
    SMALL(R.string.heatmap_grid_small, 16),
    MEDIUM(R.string.heatmap_grid_medium, 12),
    LARGE(R.string.heatmap_grid_large, 8),
}

data class Cell(
    val column: Int,
    val row: Int,
)

data class Survey(
    val id: String,
    val ssid: String,
    val timestamp: Long,
    val image: String,
    val columns: Int,
    val readings: Map<Cell, Int>,
)

fun gridRows(
    columns: Int,
    imageWidth: Int,
    imageHeight: Int,
): Int = max(1, (columns.toFloat() * imageHeight / imageWidth).roundToInt())
