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
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Private app storage: filesDir/floorplans/<random>.png and filesDir/surveys/<id>.json
 * A survey stores the measurements (not a picture), the heatmap is redrawn from them.
 */
class SurveyStore(
    private val root: File,
) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val surveysDir: File get() = File(root, SURVEYS).also { it.mkdirs() }
    private val floorplansDir: File get() = File(root, FLOORPLANS).also { it.mkdirs() }

    fun saveFloorplan(bitmap: Bitmap): String {
        val name = "${UUID.randomUUID()}.png"
        File(floorplansDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        return name
    }

    fun loadFloorplan(name: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(File(floorplansDir, File(name).name).absolutePath) }.getOrNull()

    fun save(survey: Survey) {
        val readings = JSONArray()
        survey.readings.forEach { (cell, rssi) ->
            readings.put(JSONObject().put(COLUMN, cell.column).put(ROW, cell.row).put(VALUE, rssi))
        }
        val json =
            JSONObject()
                .put(ID, survey.id)
                .put(SSID, survey.ssid)
                .put(TIMESTAMP, survey.timestamp)
                .put(IMAGE, survey.image)
                .put(COLUMNS, survey.columns)
                .put(READINGS, readings)
        File(surveysDir, "${survey.id}.json").writeText(json.toString())
    }

    fun loadAll(): List<Survey> =
        surveysDir
            .listFiles { file -> file.extension == JSON }
            ?.mapNotNull { parse(it) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()

    fun delete(survey: Survey) {
        File(surveysDir, "${survey.id}.json").delete()
        if (loadAll().none { it.image == survey.image }) {
            File(floorplansDir, File(survey.image).name).delete()
        }
    }

    private fun parse(file: File): Survey? =
        runCatching {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray(READINGS)
            val readings = mutableMapOf<Cell, Int>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                readings[Cell(item.getInt(COLUMN), item.getInt(ROW))] = item.getInt(VALUE)
            }
            Survey(
                json.getString(ID),
                json.getString(SSID),
                json.getLong(TIMESTAMP),
                json.getString(IMAGE),
                json.getInt(COLUMNS),
                readings,
            )
        }.getOrNull()

    companion object {
        private const val SURVEYS = "surveys"
        private const val FLOORPLANS = "floorplans"
        private const val JSON = "json"
        private const val PNG_QUALITY = 100
        private const val ID = "id"
        private const val SSID = "ssid"
        private const val TIMESTAMP = "timestamp"
        private const val IMAGE = "image"
        private const val COLUMNS = "columns"
        private const val READINGS = "readings"
        private const val COLUMN = "c"
        private const val ROW = "r"
        private const val VALUE = "v"
    }
}
