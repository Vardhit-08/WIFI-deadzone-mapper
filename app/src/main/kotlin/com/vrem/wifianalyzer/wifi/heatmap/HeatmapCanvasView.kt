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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.vrem.wifianalyzer.R
import kotlin.math.max
import kotlin.math.min

class HeatmapCanvasView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val density = resources.displayMetrics.density
        private val drawRect = RectF()
        private val cellRect = RectF()
        private val legendRect = RectF()
        private val hsv = FloatArray(HSV_SIZE)

        private var floor: Bitmap? = null
        private var heatmap: Bitmap? = null
        private var columns = 0
        private var rows = 0
        private var readings: Map<Cell, Int> = emptyMap()

        var selectedCell: Cell? = null
        var tapEnabled = false
        var onCellTapped: ((Cell) -> Unit)? = null

        private val weakLabel = HeatmapIdw.RSSI_WEAK.toString()
        private val strongLabel = "${HeatmapIdw.RSSI_STRONG} dBm"

        private var legendShader: Shader? = null
        private var legendShaderLeft = 0f
        private var legendShaderRight = 0f

        private val backdropPaint = Paint().apply { color = Color.WHITE }
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val heatPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val gridPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb(GRID_ALPHA, 92, 107, 192)
            }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotStrokePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
            }
        private val selectedPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = ContextCompat.getColor(context, R.color.selected)
            }
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(SHADOW_RADIUS, 0f, 0f, Color.BLACK)
            }
        private val legendBackPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(LEGEND_ALPHA, 11, 14, 20)
            }
        private val legendBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        fun setFloor(
            bitmap: Bitmap?,
            columns: Int,
        ) {
            floor = bitmap
            this.columns = if (bitmap == null) 0 else columns
            rows = if (bitmap == null) 0 else gridRows(columns, bitmap.width, bitmap.height)
            invalidate()
        }

        fun setReadings(readings: Map<Cell, Int>) {
            this.readings = readings
            invalidate()
        }

        fun setHeatmap(heatmap: Bitmap?) {
            this.heatmap = heatmap
            invalidate()
        }

        fun exportBitmap(): Bitmap? {
            val bitmap = floor ?: return null
            if (heatmap == null) return null
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            drawContent(Canvas(output), rect, bitmap.width / EXPORT_REFERENCE_DP, false)
            return output
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bitmap = floor ?: return
            fitRect(width.toFloat(), height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(), drawRect)
            drawContent(canvas, drawRect, density, true)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!tapEnabled || columns == 0) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    cellAt(event.x, event.y)?.let { onCellTapped?.invoke(it) }
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean = super.performClick()

        private fun cellAt(
            x: Float,
            y: Float,
        ): Cell? {
            val bitmap = floor ?: return null
            if (columns == 0 || rows == 0) return null
            fitRect(width.toFloat(), height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(), drawRect)
            if (!drawRect.contains(x, y)) return null
            val column = ((x - drawRect.left) / drawRect.width() * columns).toInt().coerceIn(0, columns - 1)
            val row = ((y - drawRect.top) / drawRect.height() * rows).toInt().coerceIn(0, rows - 1)
            return Cell(column, row)
        }

        private fun drawContent(
            canvas: Canvas,
            dest: RectF,
            unit: Float,
            interactive: Boolean,
        ) {
            val floorBitmap = floor ?: return
            canvas.drawRect(dest, backdropPaint)
            canvas.drawBitmap(floorBitmap, null, dest, bitmapPaint)
            val heat = heatmap
            if (heat != null) canvas.drawBitmap(heat, null, dest, heatPaint)
            if (columns == 0 || rows == 0) return
            val cellWidth = dest.width() / columns
            val cellHeight = dest.height() / rows
            if (heat == null) drawGrid(canvas, dest, cellWidth, cellHeight, unit)
            drawReadings(canvas, dest, cellWidth, cellHeight, unit, heat != null)
            if (interactive) drawSelection(canvas, dest, cellWidth, cellHeight, unit)
            if (heat != null) drawLegend(canvas, dest, unit)
        }

        private fun drawGrid(
            canvas: Canvas,
            dest: RectF,
            cellWidth: Float,
            cellHeight: Float,
            unit: Float,
        ) {
            gridPaint.strokeWidth = max(1f, unit * GRID_STROKE_DP)
            for (i in 0..columns) {
                val x = dest.left + i * cellWidth
                canvas.drawLine(x, dest.top, x, dest.bottom, gridPaint)
            }
            for (j in 0..rows) {
                val y = dest.top + j * cellHeight
                canvas.drawLine(dest.left, y, dest.right, y, gridPaint)
            }
        }

        private fun drawReadings(
            canvas: Canvas,
            dest: RectF,
            cellWidth: Float,
            cellHeight: Float,
            unit: Float,
            asDots: Boolean,
        ) {
            val side = min(cellWidth, cellHeight)
            dotStrokePaint.strokeWidth = max(1f, unit * DOT_STROKE_DP)
            textPaint.textSize = side * TEXT_FACTOR
            textPaint.setShadowLayer(SHADOW_RADIUS * unit / density, 0f, 0f, Color.BLACK)
            val showText = textPaint.textSize >= MIN_TEXT_DP * unit
            for ((cell, rssi) in readings) {
                cellRect.set(
                    dest.left + cell.column * cellWidth,
                    dest.top + cell.row * cellHeight,
                    dest.left + (cell.column + 1) * cellWidth,
                    dest.top + (cell.row + 1) * cellHeight,
                )
                fillPaint.color = HeatmapColors.color(rssi.toFloat(), CELL_ALPHA, hsv)
                if (asDots) {
                    val radius = max(side * DOT_FACTOR, MIN_DOT_DP * unit)
                    canvas.drawCircle(cellRect.centerX(), cellRect.centerY(), radius, fillPaint)
                    canvas.drawCircle(cellRect.centerX(), cellRect.centerY(), radius, dotStrokePaint)
                } else {
                    val inset = unit * CELL_INSET_DP
                    cellRect.inset(inset, inset)
                    canvas.drawRect(cellRect, fillPaint)
                    if (showText) {
                        val baseline = cellRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                        canvas.drawText(rssi.toString(), cellRect.centerX(), baseline, textPaint)
                    }
                }
            }
        }

        private fun drawSelection(
            canvas: Canvas,
            dest: RectF,
            cellWidth: Float,
            cellHeight: Float,
            unit: Float,
        ) {
            val cell = selectedCell ?: return
            selectedPaint.strokeWidth = unit * SELECTED_STROKE_DP
            cellRect.set(
                dest.left + cell.column * cellWidth,
                dest.top + cell.row * cellHeight,
                dest.left + (cell.column + 1) * cellWidth,
                dest.top + (cell.row + 1) * cellHeight,
            )
            canvas.drawRect(cellRect, selectedPaint)
        }

        private fun drawLegend(
            canvas: Canvas,
            dest: RectF,
            unit: Float,
        ) {
            val pad = LEGEND_PAD_DP * unit
            val barHeight = LEGEND_BAR_DP * unit
            val barWidth = min(dest.width() * LEGEND_WIDTH_FACTOR, LEGEND_MAX_DP * unit)
            val textSize = LEGEND_TEXT_DP * unit
            val left = dest.left + pad * 2
            val right = left + barWidth
            val bottom = dest.bottom - pad * 2
            val barTop = bottom - barHeight
            legendTextPaint.textSize = textSize
            legendRect.set(left - pad, barTop - textSize - pad * 1.5f, right + pad, bottom + pad)
            canvas.drawRoundRect(legendRect, pad, pad, legendBackPaint)
            if (legendShader == null || legendShaderLeft != left || legendShaderRight != right) {
                legendShader = LinearGradient(left, 0f, right, 0f, legendColors(), null, Shader.TileMode.CLAMP)
                legendShaderLeft = left
                legendShaderRight = right
            }
            legendBarPaint.shader = legendShader
            canvas.drawRect(left, barTop, right, bottom, legendBarPaint)
            legendTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(weakLabel, left, barTop - pad * 0.5f, legendTextPaint)
            legendTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(strongLabel, right, barTop - pad * 0.5f, legendTextPaint)
        }

        private fun legendColors(): IntArray {
            val span = (HeatmapIdw.RSSI_STRONG - HeatmapIdw.RSSI_WEAK).toFloat()
            return IntArray(LEGEND_STOPS) {
                val rssi = HeatmapIdw.RSSI_WEAK + span * it / (LEGEND_STOPS - 1)
                HeatmapColors.color(rssi, OPAQUE)
            }
        }

        companion object {
            private const val HSV_SIZE = 3
            private const val OPAQUE = 255
            private const val GRID_ALPHA = 110
            private const val LEGEND_ALPHA = 190
            private const val CELL_ALPHA = 200
            private const val SHADOW_RADIUS = 4f
            private const val GRID_STROKE_DP = 1f
            private const val DOT_STROKE_DP = 1.5f
            private const val SELECTED_STROKE_DP = 3f
            private const val CELL_INSET_DP = 2f
            private const val TEXT_FACTOR = 0.34f
            private const val MIN_TEXT_DP = 8f
            private const val DOT_FACTOR = 0.18f
            private const val MIN_DOT_DP = 4f
            private const val LEGEND_PAD_DP = 6f
            private const val LEGEND_BAR_DP = 10f
            private const val LEGEND_TEXT_DP = 11f
            private const val LEGEND_MAX_DP = 180f
            private const val LEGEND_WIDTH_FACTOR = 0.5f
            private const val LEGEND_STOPS = 5
            private const val EXPORT_REFERENCE_DP = 360f

            internal fun fitRect(
                viewWidth: Float,
                viewHeight: Float,
                bitmapWidth: Float,
                bitmapHeight: Float,
                out: RectF,
            ) {
                val scale = min(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
                val width = bitmapWidth * scale
                val height = bitmapHeight * scale
                val left = (viewWidth - width) / 2f
                val top = (viewHeight - height) / 2f
                out.set(left, top, left + width, top + height)
            }
        }
    }
