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
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.vrem.wifianalyzer.R
import com.vrem.wifianalyzer.databinding.FragmentHeatmapBinding
import com.vrem.wifianalyzer.wifi.model.Strength
import com.vrem.wifianalyzer.wifi.model.WiFiData
import com.vrem.wifianalyzer.wifi.scanner.collectWiFiData
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

private fun View.show(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

private fun Button.enable(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else DISABLED_ALPHA
}

private const val DISABLED_ALPHA = 0.4f

class HeatmapFragment : Fragment() {
    private enum class Screen { HOME, PICK, SETUP, SURVEY, RESULT, PREVIOUS, VIEW }

    private var binding: FragmentHeatmapBinding? = null
    private var pickLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private val backCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = onBack()
        }

    // State lives in the fragment instance, so it survives switching tabs (the view is recreated, the data is not)
    private var screen = Screen.HOME
    private var floorBitmap: Bitmap? = null
    private var columns = GridSize.MEDIUM.columns
    private val readings = LinkedHashMap<Cell, Int>()
    private var surveySsid: String? = null
    private var selected: Cell? = null
    private var heatmap: Bitmap? = null
    private var savedSurveyId: String? = null
    private var previous: List<Survey> = emptyList()
    private var liveSsid: String? = null
    private var liveRssi: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentHeatmapBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val viewBinding = binding ?: return
        val activity = requireActivity()
        pickLauncher =
            activity.activityResultRegistry.register(
                PICK_KEY,
                viewLifecycleOwner,
                ActivityResultContracts.PickVisualMedia(),
            ) { onImagePicked(it) }
        activity.onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        setupListeners(viewBinding)
        viewLifecycleOwner.collectWiFiData { onWiFiData(it) }
        render()
    }

    override fun onDestroyView() {
        binding = null
        pickLauncher = null
        super.onDestroyView()
    }

    private fun setupListeners(b: FragmentHeatmapBinding) {
        b.homeCreate.setOnClickListener { goTo(Screen.PICK) }
        b.homePrevious.setOnClickListener { showPrevious() }
        b.pickPlus.setOnClickListener {
            pickLauncher?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.pickBack.setOnClickListener { onBack() }
        b.setupSmall.setOnClickListener { startSurvey(GridSize.SMALL) }
        b.setupMedium.setOnClickListener { startSurvey(GridSize.MEDIUM) }
        b.setupLarge.setOnClickListener { startSurvey(GridSize.LARGE) }
        b.setupBack.setOnClickListener { onBack() }
        b.workCanvas.onCellTapped = { onCellTapped(it) }
        b.surveyBack.setOnClickListener { onBack() }
        b.surveyReset.setOnClickListener {
            if (readings.isNotEmpty()) confirm(R.string.heatmap_reset_title) { clearReadings() }
        }
        b.surveyGenerate.setOnClickListener { generate() }
        b.resultBack.setOnClickListener { onBack() }
        b.resultSave.setOnClickListener { save() }
        b.resultExport.setOnClickListener { export() }
        b.viewBack.setOnClickListener { onBack() }
        b.viewExport.setOnClickListener { export() }
        b.previousBack.setOnClickListener { onBack() }
        b.previousList.setOnItemClickListener { _, _, position, _ ->
            previous.getOrNull(position)?.let { openSurvey(it) }
        }
        b.previousList.setOnItemLongClickListener { _, _, position, _ ->
            previous.getOrNull(position)?.let { survey ->
                confirm(R.string.heatmap_delete_title) {
                    SurveyStore(requireContext()).delete(survey)
                    showPrevious()
                }
            }
            true
        }
    }

    private fun onWiFiData(wiFiData: WiFiData) {
        val connection = wiFiData.connection()
        val connected = connection.wiFiAdditional.wiFiConnection.connected
        val level = connection.wiFiSignal.level
        liveSsid = if (connected) connection.wiFiIdentifier.ssid else null
        liveRssi = if (connected && level in VALID_RSSI) level else null
        if (screen == Screen.SURVEY) {
            binding?.let { renderStatus(it) }
        }
    }

    private fun onImagePicked(uri: Uri?) {
        if (uri == null || binding == null) return
        val bitmap = decodeFloorplan(uri)
        if (bitmap == null) {
            toast(R.string.heatmap_image_error)
            return
        }
        floorBitmap = bitmap
        goTo(Screen.SETUP)
    }

    private fun decodeFloorplan(uri: Uri): Bitmap? =
        runCatching {
            val resolver = requireContext().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()

    private fun startSurvey(size: GridSize) {
        columns = size.columns
        clearReadings()
        goTo(Screen.SURVEY)
    }

    private fun onCellTapped(cell: Cell) {
        if (selected != cell) {
            selected = cell
            render()
            return
        }
        if (readings.containsKey(cell)) {
            readings.remove(cell)
            selected = null
            savedSurveyId = null
            if (readings.isEmpty()) surveySsid = null
            toast(R.string.heatmap_deleted)
        } else if (!record(cell)) {
            return
        }
        render()
    }

    private fun record(cell: Cell): Boolean {
        val ssid = liveSsid
        val rssi = liveRssi
        if (ssid == null || rssi == null) {
            toast(R.string.heatmap_no_connection)
            return false
        }
        val expected = surveySsid
        if (expected != null && expected != ssid) {
            toast(R.string.heatmap_wrong_network, expected)
            return false
        }
        surveySsid = ssid
        readings[cell] = rssi
        selected = null
        savedSurveyId = null
        toast(R.string.heatmap_recorded, rssi)
        return true
    }

    private fun clearReadings() {
        readings.clear()
        surveySsid = null
        selected = null
        heatmap = null
        savedSurveyId = null
        render()
    }

    private fun generate() {
        if (readings.size < MINIMUM_READINGS) return
        heatmap = buildHeatmap()
        selected = null
        goTo(Screen.RESULT)
    }

    private fun buildHeatmap(): Bitmap? {
        val bitmap = floorBitmap ?: return null
        return HeatmapRenderer.render(readings, columns, gridRows(columns, bitmap.width, bitmap.height))
    }

    private fun save() {
        val bitmap = floorBitmap
        val ssid = surveySsid
        if (bitmap == null || ssid == null || savedSurveyId != null) return
        runCatching {
            val store = SurveyStore(requireContext())
            val id = UUID.randomUUID().toString()
            val image = store.saveFloorplan(bitmap)
            store.save(Survey(id, ssid, System.currentTimeMillis(), image, columns, readings.toMap()))
            id
        }.onSuccess {
            savedSurveyId = it
            toast(R.string.heatmap_saved_toast)
            render()
        }.onFailure {
            toast(R.string.heatmap_save_error)
        }
    }

    private fun export() {
        val bitmap = binding?.workCanvas?.exportBitmap() ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast(R.string.heatmap_export_unsupported)
            return
        }
        val name = "wifi_heatmap_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val exported = HeatmapExporter.exportToGallery(requireContext(), bitmap, name)
        toast(if (exported) R.string.heatmap_exported else R.string.heatmap_export_error)
    }

    private fun showPrevious() {
        previous = SurveyStore(requireContext()).loadAll()
        goTo(Screen.PREVIOUS)
    }

    private fun openSurvey(survey: Survey) {
        val bitmap = SurveyStore(requireContext()).loadFloorplan(survey.image)
        if (bitmap == null) {
            toast(R.string.heatmap_missing_image)
            return
        }
        floorBitmap = bitmap
        columns = survey.columns
        readings.clear()
        readings.putAll(survey.readings)
        surveySsid = survey.ssid
        savedSurveyId = survey.id
        selected = null
        heatmap = buildHeatmap()
        goTo(Screen.VIEW)
    }

    private fun goHome() {
        readings.clear()
        surveySsid = null
        selected = null
        heatmap = null
        savedSurveyId = null
        floorBitmap = null
        goTo(Screen.HOME)
    }

    private fun onBack() {
        when (screen) {
            Screen.HOME -> Unit
            Screen.PICK, Screen.PREVIOUS -> goHome()
            Screen.SETUP -> {
                floorBitmap = null
                goTo(Screen.PICK)
            }
            Screen.SURVEY ->
                if (readings.isNotEmpty() && savedSurveyId == null) {
                    confirm(R.string.heatmap_discard_title) { goHome() }
                } else {
                    goHome()
                }
            Screen.RESULT -> {
                heatmap = null
                goTo(Screen.SURVEY)
            }
            Screen.VIEW -> {
                readings.clear()
                floorBitmap = null
                heatmap = null
                showPrevious()
            }
        }
    }

    private fun goTo(target: Screen) {
        screen = target
        render()
    }

    private fun render() {
        val b = binding ?: return
        backCallback.isEnabled = screen != Screen.HOME
        val work = screen == Screen.SURVEY || screen == Screen.RESULT || screen == Screen.VIEW
        b.heatmapHome.show(screen == Screen.HOME)
        b.heatmapPick.show(screen == Screen.PICK)
        b.heatmapSetup.show(screen == Screen.SETUP)
        b.heatmapWork.show(work)
        b.heatmapPrevious.show(screen == Screen.PREVIOUS)
        when {
            screen == Screen.SETUP -> b.setupPreview.setImageBitmap(floorBitmap)
            screen == Screen.PREVIOUS -> renderPrevious(b)
            work -> renderWork(b)
        }
    }

    private fun renderWork(b: FragmentHeatmapBinding) {
        val surveying = screen == Screen.SURVEY
        b.workCanvas.setFloor(floorBitmap, columns)
        b.workCanvas.setReadings(readings)
        b.workCanvas.setHeatmap(if (surveying) null else heatmap)
        b.workCanvas.selectedCell = selected
        b.workCanvas.tapEnabled = surveying
        b.surveyBar.show(surveying)
        b.resultBar.show(screen == Screen.RESULT)
        b.viewBar.show(screen == Screen.VIEW)
        val ready = readings.size >= MINIMUM_READINGS
        b.surveyGenerate.enable(ready)
        b.surveyGenerate.text =
            if (ready) {
                getString(R.string.heatmap_generate)
            } else {
                getString(R.string.heatmap_generate_progress, readings.size, MINIMUM_READINGS)
            }
        val saved = savedSurveyId != null
        b.resultSave.enable(!saved)
        b.resultSave.setText(if (saved) R.string.heatmap_saved else R.string.heatmap_save)
        renderStatus(b)
        val hint = hintResource()
        b.workHint.text =
            if (hint == R.string.heatmap_hint_idle) getString(hint, MINIMUM_READINGS) else getString(hint)
    }

    @StringRes
    private fun hintResource(): Int {
        val cell = selected
        return when {
            screen != Screen.SURVEY -> R.string.heatmap_hint_result
            cell == null -> R.string.heatmap_hint_idle
            readings.containsKey(cell) -> R.string.heatmap_hint_delete
            else -> R.string.heatmap_hint_record
        }
    }

    private fun renderStatus(b: FragmentHeatmapBinding) {
        val context = requireContext()
        val status: TextView = b.workStatus
        val ssid = liveSsid
        val rssi = liveRssi
        if (screen != Screen.SURVEY) {
            status.text = getString(R.string.heatmap_status_result, surveySsid.orEmpty(), readings.size)
            status.setTextColor(ContextCompat.getColor(context, R.color.selected))
        } else if (ssid != null && rssi != null) {
            status.text = getString(R.string.heatmap_status_connected, ssid, rssi, readings.size)
            status.setTextColor(ContextCompat.getColor(context, Strength.colorForRssi(rssi)))
        } else {
            status.text = getString(R.string.heatmap_status_not_connected, readings.size)
            status.setTextColor(ContextCompat.getColor(context, R.color.error))
        }
    }

    private fun renderPrevious(b: FragmentHeatmapBinding) {
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        b.previousEmpty.show(previous.isEmpty())
        b.previousList.show(previous.isNotEmpty())
        b.previousList.adapter =
            object : ArrayAdapter<Survey>(
                requireContext(),
                android.R.layout.simple_list_item_2,
                android.R.id.text1,
                previous,
            ) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup,
                ): View {
                    val view = super.getView(position, convertView, parent)
                    val survey = getItem(position) ?: return view
                    view.findViewById<TextView>(android.R.id.text1).text = survey.ssid
                    view.findViewById<TextView>(android.R.id.text2).text =
                        context.getString(
                            R.string.heatmap_previous_item,
                            format.format(Date(survey.timestamp)),
                            survey.readings.size,
                            context.getString(gridSizeText(survey.columns)),
                        )
                    return view
                }
            }
    }

    @StringRes
    private fun gridSizeText(columns: Int): Int {
        val size = GridSize.entries.firstOrNull { it.columns == columns }
        return size?.textResource ?: R.string.heatmap_grid_medium
    }

    private fun confirm(
        @StringRes title: Int,
        action: () -> Unit,
    ) {
        AlertDialog
            .Builder(requireContext())
            .setTitle(title)
            .setPositiveButton(R.string.heatmap_yes) { _, _ -> action() }
            .setNegativeButton(R.string.heatmap_cancel, null)
            .show()
    }

    private fun toast(
        @StringRes message: Int,
        vararg args: Any,
    ) {
        context?.let { Toast.makeText(it, getString(message, *args), Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val PICK_KEY = "heatmap_pick_floorplan"
        private const val MAX_IMAGE_SIDE = 1800
        private val VALID_RSSI = -99..-1
    }
}
