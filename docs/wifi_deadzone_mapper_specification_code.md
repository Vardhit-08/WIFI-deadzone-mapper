# WiFi Deadzone Mapper — Technical Specification & Complete Implementation Reference

## 1. Project Overview & Architecture
This document provides complete, copy-paste ready implementations for adding an indoor WiFi Deadzone Heatmap Mapper feature to an Android application (e.g. extending `vremsoftwaredevelopment/wifianalyzer`).

### System Requirements & Key Architectural Constraints
* **Platform Target:** Android 8.0 (API 26) through Android 14+ (API 34).
* **Scan Throttling Bypass:** Android 10+ restricts `WifiManager` polling. For project demonstrations, **Wi-Fi scan throttling must be disabled** in *Developer Options*. The app prioritizes active Wi-Fi scan results for the connected BSSID, falling back to connection info RSSI.
* **Aspect-Fit Coordinate Space:** Floor plans vary from portrait (CSE) to widescreen landscape (Instrumentation). All coordinates are defined as normalized fractions $x_{\text{norm}}, y_{\text{norm}} \in [0.0, 1.0]$. Rendering coordinates are scaled using an `Aspect-Fit` destination rect (`drawRect`) inside the canvas, eliminating coordinate drift caused by screen density or letterboxing.
* **Canvas Heatmap Algorithm:** Checks visited checkpoints ($N \ge 3$) and renders soft overlapping radial gradients ($r = 175\text{ px}$) using linear interpolation across the standard WiFi signal spectrum:
  $$\text{Normalized RSSI } \eta = \frac{\text{clamp}(\text{RSSI}, -100, -35) - (-100)}{-35 - (-100)}$$
  $$\text{Hue } H = \eta \times 120^\circ \quad (\text{Deadzone Red: } 0^\circ \longrightarrow \text{Good Signal Green: } 120^\circ)$$
* **Zero Database Overhead:** Local history is managed using structured JSON objects persisted via `SharedPreferences`.

---

## 2. Floor Plan Assets & Normalized Checkpoint Registry

Place the following image files in `app/src/main/res/drawable/`:
* `floor_cse.png` (Portrait, black-outlined walls, void spaces blackened)
* `floor_entc_ground.png` (Square/Landscape, central open courtyard shaded)
* `floor_entc_first.png` (Square/Landscape, upper courtyard shaded)
* `floor_instru.png` (Landscape, landscaped open-to-sky courtyard blackened)

### Checkpoint Coordinates Mapping Table

| Floor ID | Checkpoint ID | Label / Location | $x_{\text{norm}}$ | $y_{\text{norm}}$ |
| :--- | :--- | :--- | :--- | :--- |
| **`cse_ground`** | 1 | Main Entry Corridor | 0.32 | 0.80 |
| | 2 | Central Hall Junction | 0.32 | 0.50 |
| | 3 | North Corridor Left | 0.32 | 0.22 |
| | 4 | North Corridor Right | 0.70 | 0.22 |
| | 5 | Lab Top-Left | 0.15 | 0.10 |
| | 6 | Lab Top-Right | 0.88 | 0.10 |
| | 7 | Center Main Lab | 0.82 | 0.35 |
| | 8 | South-East Lab | 0.70 | 0.70 |
| **`entc_ground`** | 201 | Passage - West Entry | 0.20 | 0.53 |
| | 202 | Passage - Central Courtyard | 0.56 | 0.53 |
| | 203 | Passage - East Stairs | 0.80 | 0.53 |
| | 204 | Digital Electronics Lab | 0.12 | 0.22 |
| | 205 | Electronics Devices Lab | 0.27 | 0.22 |
| | 206 | HOD / Office Area | 0.48 | 0.18 |
| | 207 | M.E. Lecture Hall | 0.62 | 0.18 |
| | 208 | Advanced Comm Lab | 0.83 | 0.25 |
| | 209 | Language Lab | 0.12 | 0.82 |
| | 210 | Communication Engg Lab | 0.28 | 0.82 |
| | 211 | Embedded System Lab | 0.44 | 0.82 |
| | 212 | Data Comm Lab | 0.58 | 0.82 |
| | 213 | CVPR Lab | 0.72 | 0.82 |
| | 214 | DSP Lab | 0.88 | 0.82 |
| **`entc_first`** | 301 | Passage - West Side | 0.20 | 0.53 |
| | 302 | Passage - Central Courtyard | 0.56 | 0.53 |
| | 303 | Passage - East Side | 0.80 | 0.53 |
| | 304 | VLSI Design & Verif Lab | 0.20 | 0.22 |
| | 305 | Server Room | 0.09 | 0.48 |
| | 306 | Faculty Rooms / Office | 0.55 | 0.20 |
| | 307 | Project Lab | 0.78 | 0.25 |
| | 308 | Seminar Hall | 0.13 | 0.82 |
| | 309 | Center of Excellence 1 | 0.27 | 0.82 |
| | 310 | Center of Excellence 2 | 0.41 | 0.82 |
| | 311 | Center of Excellence 3 | 0.56 | 0.82 |
| | 312 | Computer Lab A | 0.70 | 0.82 |
| | 313 | Computer Lab B | 0.87 | 0.82 |
| **`instru_ground`**| 101 | West Corridor (Stairs) | 0.23 | 0.46 |
| | 102 | North Corridor (Cabins) | 0.50 | 0.44 |
| | 103 | East Corridor (Toilets) | 0.78 | 0.46 |
| | 104 | South Corridor (HOD Area) | 0.58 | 0.74 |
| | 105 | Electrical Engg Lab | 0.17 | 0.22 |
| | 106 | Instru & Control Lab | 0.38 | 0.28 |
| | 107 | Analog & Digital Lab | 0.61 | 0.28 |
| | 108 | Class Room - 1 | 0.86 | 0.20 |
| | 109 | UG Computer Lab | 0.85 | 0.68 |
| | 110 | Lab Class Room - 2 | 0.86 | 0.86 |
| | 111 | Bio-Medical Lab | 0.38 | 0.86 |
| | 112 | PLC & DCS Lab | 0.12 | 0.74 |

---

## 3. Data Models & Floorplan Repository

```kotlin
package com.vrem.wifianalyzer.wifi.heatmap

import androidx.annotation.DrawableRes
import com.vrem.wifianalyzer.R

data class Checkpoint(
    val id: Int,
    val name: String,
    val normX: Float,
    val normY: Float
)

data class FloorConfig(
    val id: String,
    val displayName: String,
    @DrawableRes val drawableResId: Int,
    val checkpoints: List<Checkpoint>
)

object FloorplanRepository {
    fun getFloors(): List<FloorConfig> = listOf(
        FloorConfig(
            id = "cse_ground",
            displayName = "CSE Department",
            drawableResId = R.drawable.floor_cse,
            checkpoints = listOf(
                Checkpoint(1, "Main Entry Corridor", 0.32f, 0.80f),
                Checkpoint(2, "Central Hall Junction", 0.32f, 0.50f),
                Checkpoint(3, "North Corridor Left", 0.32f, 0.22f),
                Checkpoint(4, "North Corridor Right", 0.70f, 0.22f),
                Checkpoint(5, "Lab Top-Left", 0.15f, 0.10f),
                Checkpoint(6, "Lab Top-Right", 0.88f, 0.10f),
                Checkpoint(7, "Center Main Lab", 0.82f, 0.35f),
                Checkpoint(8, "South-East Lab", 0.70f, 0.70f)
            )
        ),
        FloorConfig(
            id = "entc_ground",
            displayName = "ENTC Ground Floor",
            drawableResId = R.drawable.floor_entc_ground,
            checkpoints = listOf(
                Checkpoint(201, "Passage - West Entry", 0.20f, 0.53f),
                Checkpoint(202, "Passage - Central Courtyard", 0.56f, 0.53f),
                Checkpoint(203, "Passage - East Stairs", 0.80f, 0.53f),
                Checkpoint(204, "Digital Electronics Lab", 0.12f, 0.22f),
                Checkpoint(205, "Electronics Devices Lab", 0.27f, 0.22f),
                Checkpoint(206, "HOD / Office Area", 0.48f, 0.18f),
                Checkpoint(207, "M.E. Lecture Hall", 0.62f, 0.18f),
                Checkpoint(208, "Advanced Comm Lab", 0.83f, 0.25f),
                Checkpoint(209, "Language Lab", 0.12f, 0.82f),
                Checkpoint(210, "Communication Engg Lab", 0.28f, 0.82f),
                Checkpoint(211, "Embedded System Lab", 0.44f, 0.82f),
                Checkpoint(212, "Data Comm Lab", 0.58f, 0.82f),
                Checkpoint(213, "CVPR Lab", 0.72f, 0.82f),
                Checkpoint(214, "DSP Lab", 0.88f, 0.82f)
            )
        ),
        FloorConfig(
            id = "entc_first",
            displayName = "ENTC First Floor",
            drawableResId = R.drawable.floor_entc_first,
            checkpoints = listOf(
                Checkpoint(301, "Passage - West Side", 0.20f, 0.53f),
                Checkpoint(302, "Passage - Central Courtyard", 0.56f, 0.53f),
                Checkpoint(303, "Passage - East Side", 0.80f, 0.53f),
                Checkpoint(304, "VLSI Design & Verif Lab", 0.20f, 0.22f),
                Checkpoint(305, "Server Room", 0.09f, 0.48f),
                Checkpoint(306, "Faculty Rooms / Office", 0.55f, 0.20f),
                Checkpoint(307, "Project Lab", 0.78f, 0.25f),
                Checkpoint(308, "Seminar Hall", 0.13f, 0.82f),
                Checkpoint(309, "Center of Excellence 1", 0.27f, 0.82f),
                Checkpoint(310, "Center of Excellence 2", 0.41f, 0.82f),
                Checkpoint(311, "Center of Excellence 3", 0.56f, 0.82f),
                Checkpoint(312, "Computer Lab A", 0.70f, 0.82f),
                Checkpoint(313, "Computer Lab B", 0.87f, 0.82f)
            )
        ),
        FloorConfig(
            id = "instru_ground",
            displayName = "Instrumentation Dept",
            drawableResId = R.drawable.floor_instru,
            checkpoints = listOf(
                Checkpoint(101, "West Corridor (Stairs)", 0.23f, 0.46f),
                Checkpoint(102, "North Corridor (Cabins)", 0.50f, 0.44f),
                Checkpoint(103, "East Corridor (Toilets)", 0.78f, 0.46f),
                Checkpoint(104, "South Corridor (HOD Area)", 0.58f, 0.74f),
                Checkpoint(105, "Electrical Engg Lab", 0.17f, 0.22f),
                Checkpoint(106, "Instru & Control Lab", 0.38f, 0.28f),
                Checkpoint(107, "Analog & Digital Lab", 0.61f, 0.28f),
                Checkpoint(108, "Class Room - 1", 0.86f, 0.20f),
                Checkpoint(109, "UG Computer Lab", 0.85f, 0.68f),
                Checkpoint(110, "Lab Class Room - 2", 0.86f, 0.86f),
                Checkpoint(111, "Bio-Medical Lab", 0.38f, 0.86f),
                Checkpoint(112, "PLC & DCS Lab", 0.12f, 0.74f)
            )
        )
    )
}
```

---

## 4. Custom Heatmap View with Drift-Free Transformation

```kotlin
package com.vrem.wifianalyzer.wifi.heatmap

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class HeatmapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var floorConfig: FloorConfig? = null
    private var floorBitmap: Bitmap? = null
    private val drawRect = RectF()

    var isSurveyActive = false
    var showHeatmap = false
    private val recordedReadings = mutableMapOf<Int, Int>()

    var onCheckpointTapped: ((Checkpoint) -> Unit)? = null

    private val touchRadiusPx = 54f
    private val gradientRadiusPx = 185f

    private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.WHITE
    }

    private val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    fun setFloor(config: FloorConfig) {
        this.floorConfig = config
        this.floorBitmap?.recycle()
        this.floorBitmap = BitmapFactory.decodeResource(resources, config.drawableResId)
        this.showHeatmap = false
        this.recordedReadings.clear()
        invalidate()
    }

    fun updateReading(checkpointId: Int, rssi: Int) {
        recordedReadings[checkpointId] = rssi
        invalidate()
    }

    fun setReadings(readings: Map<Int, Int>) {
        recordedReadings.clear()
        recordedReadings.putAll(readings)
        invalidate()
    }

    fun getReadings(): Map<Int, Int> = recordedReadings

    private fun toScreenX(normX: Float): Float = drawRect.left + (normX * drawRect.width())
    private fun toScreenY(normY: Float): Float = drawRect.top + (normY * drawRect.height())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = floorBitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // Maintain aspect ratio without stretching
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val viewRatio = viewW / viewH

        if (viewRatio > bitmapRatio) {
            val drawnWidth = viewH * bitmapRatio
            val left = (viewW - drawnWidth) / 2f
            drawRect.set(left, 0f, left + drawnWidth, viewH)
        } else {
            val drawnHeight = viewW / bitmapRatio
            val top = (viewH - drawnHeight) / 2f
            drawRect.set(0f, top, viewW, top + drawnHeight)
        }

        canvas.drawBitmap(bitmap, null, drawRect, null)

        val config = floorConfig ?: return

        // 1. Draw Radial Gradients
        if (showHeatmap) {
            config.checkpoints.forEach { cp ->
                val rssi = recordedReadings[cp.id]
                if (rssi != null) {
                    val cx = toScreenX(cp.normX)
                    val cy = toScreenY(cp.normY)
                    val color = rssiToColor(rssi)
                    val gradient = RadialGradient(
                        cx, cy, gradientRadiusPx,
                        intArrayOf(color, Color.TRANSPARENT),
                        floatArrayOf(0.0f, 1.0f),
                        Shader.TileMode.CLAMP
                    )
                    val heatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = gradient
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                    }
                    canvas.drawCircle(cx, cy, gradientRadiusPx, heatPaint)
                }
            }
        }

        // 2. Draw Checkpoints and Value Tags
        config.checkpoints.forEach { cp ->
            val cx = toScreenX(cp.normX)
            val cy = toScreenY(cp.normY)
            val rssi = recordedReadings[cp.id]

            if (rssi == null) {
                circleStrokePaint.color = Color.WHITE
                circleStrokePaint.style = Paint.Style.STROKE
                canvas.drawCircle(cx, cy, 16f, circleStrokePaint)
                canvas.drawText(cp.name, cx + 22f, cy + 8f, labelTextPaint)
            } else {
                val color = rssiToColor(rssi)
                circleFillPaint.color = color
                canvas.drawCircle(cx, cy, 20f, circleFillPaint)
                circleStrokePaint.color = Color.BLACK
                canvas.drawCircle(cx, cy, 20f, circleStrokePaint)
                canvas.drawText("${cp.name} ($rssi dBm)", cx + 26f, cy + 8f, labelTextPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSurveyActive || event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        val config = floorConfig ?: return false

        val hit = config.checkpoints.firstOrNull { cp ->
            val cx = toScreenX(cp.normX)
            val cy = toScreenY(cp.normY)
            val dx = event.x - cx
            val dy = event.y - cy
            (dx * dx + dy * dy) <= (touchRadiusPx * touchRadiusPx)
        }

        hit?.let {
            onCheckpointTapped?.invoke(it)
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun rssiToColor(rssi: Int): Int {
        val clamped = rssi.coerceIn(-100, -35)
        val norm = (clamped - (-100)) / (-35f - (-100f)) // 0.0 (dead) to 1.0 (strong)
        val hue = norm * 120f // 0 (Red) -> 60 (Yellow) -> 120 (Green)
        return Color.HSVToColor(170, floatArrayOf(hue, 1.0f, 1.0f))
    }
}
```

---

## 5. Storage Helper: Local JSON Sessions

```kotlin
package com.vrem.wifianalyzer.wifi.heatmap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object HeatmapHistoryStorage {
    private const val PREFS_KEY = "wifi_deadzone_mapper_store"

    fun saveSurvey(
        context: Context,
        floorId: String,
        ssid: String,
        readings: Map<Int, Int>,
        floorCheckpoints: List<Checkpoint>
    ) {
        val prefs = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
        val key = "history_$floorId"
        val existingArray = JSONArray(prefs.getString(key, "[]"))

        val record = JSONObject().apply {
            put("timestamp", SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date()))
            put("ssid", ssid)
            val points = JSONArray()
            readings.forEach { (id, rssi) ->
                floorCheckpoints.firstOrNull { it.id == id }?.let { cp ->
                    points.put(JSONObject().apply {
                        put("id", cp.id)
                        put("name", cp.name)
                        put("normX", cp.normX.toDouble())
                        put("normY", cp.normY.toDouble())
                        put("rssi", rssi)
                    })
                }
            }
            put("points", points)
        }

        existingArray.put(record)
        prefs.edit().putString(key, existingArray.toString()).apply()
    }

    fun loadSurveys(context: Context, floorId: String): List<JSONObject> {
        val prefs = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
        val raw = prefs.getString("history_$floorId", "[]") ?: "[]"
        val jsonArray = JSONArray(raw)
        return (0 until jsonArray.length()).map { jsonArray.getJSONObject(it) }
    }
}
```

---

## 6. Layout: `res/layout/fragment_heatmap.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#121212">

    <!-- Top Action Bar -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingHorizontal="12dp"
        android:background="#1E1E1E">

        <ImageButton
            android:id="@+id/btnHistory"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:src="@android:drawable/ic_menu_recent_history"
            android:tint="#FFFFFF"
            android:contentDescription="Saved Surveys" />

        <Spinner
            android:id="@+id/spinnerFloors"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="8dp" />

        <TextView
            android:id="@+id/tvLiveRssi"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="#00FF66"
            android:textSize="14sp"
            android:textStyle="bold"
            android:padding="6dp"
            android:text="-- dBm" />
    </LinearLayout>

    <!-- Custom Aspect-Fit Canvas -->
    <com.vrem.wifianalyzer.wifi.heatmap.HeatmapCanvasView
        android:id="@+id/heatmapCanvas"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <!-- Bottom Actions Control Bar -->
    <LinearLayout
        android:id="@+id/bottomControls"
        android:layout_width="match_parent"
        android:layout_height="64dp"
        android:orientation="horizontal"
        android:paddingHorizontal="16dp"
        android:paddingVertical="8dp"
        android:gravity="center"
        android:background="#1E1E1E">

        <Button
            android:id="@+id/btnSurveyToggle"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:text="Start Survey" />

        <Button
            android:id="@+id/btnGenerateHeatmap"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:text="Generate Heatmap (0/3)"
            android:visibility="gone"
            android:layout_marginStart="8dp" />

        <Button
            android:id="@+id/btnSaveSession"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:text="Save Heatmap"
            android:visibility="gone" />

        <Button
            android:id="@+id/btnClearReset"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:text="Reset"
            android:visibility="gone"
            android:layout_marginStart="8dp" />
    </LinearLayout>
</LinearLayout>
```

---

## 7. Controller: `HeatmapFragment.kt`

```kotlin
package com.vrem.wifianalyzer.wifi.heatmap

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.vrem.wifianalyzer.R

class HeatmapFragment : Fragment() {

    private lateinit var canvasView: HeatmapCanvasView
    private lateinit var btnToggle: Button
    private lateinit var btnGenerate: Button
    private lateinit var btnSave: Button
    private lateinit var btnReset: Button
    private lateinit var tvLiveRssi: TextView
    private lateinit var floorSpinner: Spinner

    private val floors = FloorplanRepository.getFloors()
    private var currentFloor: FloorConfig = floors.first()
    private var wifiManager: WifiManager? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.fragment_heatmap, container, false)
        wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        canvasView = root.findViewById(R.id.heatmapCanvas)
        btnToggle = root.findViewById(R.id.btnSurveyToggle)
        btnGenerate = root.findViewById(R.id.btnGenerateHeatmap)
        btnSave = root.findViewById(R.id.btnSaveSession)
        btnReset = root.findViewById(R.id.btnClearReset)
        tvLiveRssi = root.findViewById(R.id.tvLiveRssi)
        floorSpinner = root.findViewById(R.id.spinnerFloors)

        setupSpinner()
        setupListeners(root)

        return root
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, floors.map { it.displayName })
        floorSpinner.adapter = adapter
        floorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                currentFloor = floors[pos]
                canvasView.setFloor(currentFloor)
                resetControls()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Bypasses cached connectionInfo RSSI by matching active scan results for the connected BSSID.
     */
    private fun getAccurateLiveRssi(): Int {
        val info = wifiManager?.connectionInfo ?: return -100
        val connectedBssid = info.bssid ?: return info.rssi
        val scan = wifiManager?.scanResults?.firstOrNull { it.BSSID.equals(connectedBssid, ignoreCase = true) }
        val finalRssi = scan?.level ?: info.rssi
        tvLiveRssi.text = "$finalRssi dBm"
        return finalRssi
    }

    private fun setupListeners(root: View) {
        // Tap Checkpoint
        canvasView.onCheckpointTapped = { cp ->
            val rssi = getAccurateLiveRssi()
            canvasView.updateReading(cp.id, rssi)
            Toast.makeText(context, "${cp.name} recorded ($rssi dBm)", Toast.LENGTH_SHORT).show()

            val count = canvasView.getReadings().size
            btnGenerate.isEnabled = count >= 3
            btnGenerate.text = "Generate ($count/3+)"
        }

        // Survey State Toggle
        btnToggle.setOnClickListener {
            if (!canvasView.isSurveyActive) {
                canvasView.isSurveyActive = true
                btnToggle.text = "Cancel Survey"
                btnGenerate.visibility = View.VISIBLE
                btnGenerate.isEnabled = canvasView.getReadings().size >= 3
            } else {
                resetControls()
            }
        }

        // Generate Heatmap
        btnGenerate.setOnClickListener {
            canvasView.isSurveyActive = false
            canvasView.showHeatmap = true
            canvasView.invalidate()

            btnToggle.visibility = View.GONE
            btnGenerate.visibility = View.GONE
            btnSave.visibility = View.VISIBLE
            btnReset.visibility = View.VISIBLE
        }

        // Save Heatmap Locally
        btnSave.setOnClickListener {
            val ssid = wifiManager?.connectionInfo?.ssid?.replace("\"", "") ?: "Connected_WiFi"
            HeatmapHistoryStorage.saveSurvey(
                requireContext(),
                currentFloor.id,
                ssid,
                canvasView.getReadings(),
                currentFloor.checkpoints
            )
            Toast.makeText(context, "Survey saved to device!", Toast.LENGTH_SHORT).show()
            resetControls()
        }

        // Reset
        btnReset.setOnClickListener { resetControls() }

        // History Dialog
        root.findViewById<ImageButton>(R.id.btnHistory).setOnClickListener {
            showHistoryBottomSheet()
        }
    }

    private fun resetControls() {
        canvasView.isSurveyActive = false
        canvasView.showHeatmap = false
        canvasView.setFloor(currentFloor)
        btnToggle.visibility = View.VISIBLE
        btnToggle.text = "Start Survey"
        btnGenerate.visibility = View.GONE
        btnGenerate.text = "Generate (0/3)"
        btnGenerate.isEnabled = false
        btnSave.visibility = View.GONE
        btnReset.visibility = View.GONE
    }

    private fun showHistoryBottomSheet() {
        val sessions = HeatmapHistoryStorage.loadSurveys(requireContext(), currentFloor.id)
        if (sessions.isEmpty()) {
            Toast.makeText(context, "No saved surveys for ${currentFloor.displayName}", Toast.LENGTH_SHORT).show()
            return
        }

        val items = sessions.map { "${it.getString("timestamp")}\nSSID: ${it.getString("ssid")}" }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Saved Surveys — ${currentFloor.displayName}")
            .setItems(items) { _, which ->
                val selected = sessions[which]
                val pointsArr = selected.getJSONArray("points")
                val restoredReadings = mutableMapOf<Int, Int>()

                for (i in 0 until pointsArr.length()) {
                    val p = pointsArr.getJSONObject(i)
                    restoredReadings[p.getInt("id")] = p.getInt("rssi")
                }

                canvasView.setReadings(restoredReadings)
                canvasView.showHeatmap = true
                btnToggle.visibility = View.GONE
                btnGenerate.visibility = View.GONE
                btnSave.visibility = View.GONE
                btnReset.visibility = View.VISIBLE
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        getAccurateLiveRssi()
    }
}
```

---

## 8. Integration & Build Checklist

1. **Copy Images:** Save `floor_cse.png`, `floor_entc_ground.png`, `floor_entc_first.png`, and `floor_instru.png` to `app/src/main/res/drawable/`.
2. **Copy Code:** Place the Kotlin classes into `app/src/main/java/com/vrem/wifianalyzer/wifi/heatmap/` and the XML layout into `app/src/main/res/layout/`.
3. **Register Menu Item:** In `app/src/main/res/menu/navigation.xml`, swap out an unused menu item:
   ```xml
   <item
       android:id="@+id/nav_heatmap"
       android:icon="@android:drawable/ic_dialog_map"
       android:title="Heatmap" />
   ```
4. **Wire Navigation:** In `MainActivity.kt` (or Java switch handler), direct `R.id.nav_heatmap` to load `HeatmapFragment`.
5. **Runtime Settings on Test Device:** Go to **Settings > System > Developer Options > Networking** and turn **Off** *Wi-Fi scan throttling*.