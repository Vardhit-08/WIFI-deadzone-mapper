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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.offset
import org.junit.Test

class HeatmapIdwTest {
    @Test
    fun exactSampleReturnsItsValueWithFullAlpha() {
        // Arrange
        val samples = listOf(HeatmapIdw.Sample(0.5f, 0.5f, -60))
        // Act
        val actual = HeatmapIdw.interpolate(samples, 0.5f, 0.5f)
        // Assert
        assertThat(actual).isNotNull()
        assertThat(actual!!.rssi).isCloseTo(-60f, offset(0.01f))
        assertThat(actual.alpha).isCloseTo(1f, offset(0.01f))
    }

    @Test
    fun noSampleInsideRadiusReturnsNull() {
        // Arrange
        val samples = listOf(HeatmapIdw.Sample(0.5f, 0.5f, -60))
        // Act
        val actual = HeatmapIdw.interpolate(samples, 10f, 10f)
        // Assert
        assertThat(actual).isNull()
    }

    @Test
    fun alphaFadesBetweenCoreAndRadius() {
        // Arrange
        val samples = listOf(HeatmapIdw.Sample(0.5f, 0.5f, -60))
        val radius = HeatmapIdw.INFLUENCE_CELLS
        val distance = radius * (HeatmapIdw.CORE_FRACTION + 1f) / 2f
        // Act
        val actual = HeatmapIdw.interpolate(samples, 0.5f + distance, 0.5f)
        // Assert
        assertThat(actual!!.alpha).isCloseTo(0.5f, offset(0.01f))
    }

    @Test
    fun equidistantSamplesAreAveraged() {
        // Arrange
        val samples =
            listOf(
                HeatmapIdw.Sample(0.5f, 0.5f, -50),
                HeatmapIdw.Sample(2.5f, 0.5f, -70),
            )
        // Act
        val actual = HeatmapIdw.interpolate(samples, 1.5f, 0.5f)
        // Assert
        assertThat(actual!!.rssi).isCloseTo(-60f, offset(0.01f))
    }

    @Test
    fun nearerSampleDominates() {
        // Arrange
        val samples =
            listOf(
                HeatmapIdw.Sample(0.5f, 0.5f, -50),
                HeatmapIdw.Sample(3.5f, 0.5f, -90),
            )
        // Act
        val actual = HeatmapIdw.interpolate(samples, 1.5f, 0.5f)
        // Assert
        assertThat(actual!!.rssi).isGreaterThan(-60f)
    }

    @Test
    fun fractionIsClampedToZeroAndOne() {
        assertThat(HeatmapIdw.fraction(-120f)).isEqualTo(0f)
        assertThat(HeatmapIdw.fraction(-10f)).isEqualTo(1f)
        assertThat(HeatmapIdw.fraction(-67.5f)).isCloseTo(0.5f, offset(0.01f))
    }

    @Test
    fun gridRowsKeepsSquareCells() {
        assertThat(gridRows(12, 1200, 1800)).isEqualTo(18)
        assertThat(gridRows(8, 800, 100)).isEqualTo(1)
    }
}
