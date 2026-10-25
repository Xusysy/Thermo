package com.example.thermocam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Поверх камеры рисует прицел и (в режиме тепловой карты) цветные клетки.
 * Датчик меряет одну точку, поэтому карта набирается "сканированием":
 * каждое измерение привязывается к направлению камеры (yaw/pitch) и рисуется там, где было снято.
 */
class ThermalOverlayView(context: Context) : View(context) {

    enum class Mode { MEASURE, HEATMAP }

    var mode = Mode.MEASURE
        set(value) { field = value; invalidate() }

    // Примерное поле зрения основной камеры, градусы
    private val hFov = 65f
    private val vFov = 50f

    // Размер клетки карты в градусах
    private val cell = 2.5f

    private var yaw = 0f
    private var pitch = 0f
    private var currentTemp: Float? = null
    var demo = false
    var debug: String = ""

    private val cells = HashMap<Long, Float>()

    private val cellPaint = Paint().apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 72f; textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 36f; textAlign = Paint.Align.LEFT
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val barPaint = Paint()

    fun update(yaw: Float, pitch: Float, temp: Float?) {
        this.yaw = yaw
        this.pitch = pitch
        this.currentTemp = temp
        if (mode == Mode.HEATMAP && temp != null) {
            val key = key(floor(yaw / cell).toInt(), floor(pitch / cell).toInt())
            cells[key] = temp
        }
        invalidate()
    }

    fun clear() {
        cells.clear()
        invalidate()
    }

    private fun key(ix: Int, iy: Int): Long = (ix.toLong() shl 32) or (iy.toLong() and 0xffffffffL)

    private fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        if (mode == Mode.HEATMAP && cells.isNotEmpty()) {
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            for (t in cells.values) { if (t < min) min = t; if (t > max) max = t }
            val range = (max - min).coerceAtLeast(1f)

            val cw = cell / hFov * w
            val ch = cell / vFov * h
            for ((k, t) in cells) {
                val ix = (k shr 32).toInt()
                val iy = k.toInt()
                val dx = angleDiff(ix * cell + cell / 2, yaw)
                val dy = iy * cell + cell / 2 - pitch
                if (abs(dx) > hFov && abs(dy) > vFov) continue
                val sx = cx + dx / hFov * w
                val sy = cy - dy / vFov * h
                cellPaint.color = heatColor((t - min) / range, 170)
                canvas.drawRect(sx - cw / 2, sy - ch / 2, sx + cw / 2, sy + ch / 2, cellPaint)
            }

            // Шкала справа
            val barLeft = w - 70f
            val barTop = h * 0.2f
            val barBottom = h * 0.7f
            val steps = 50
            val stepH = (barBottom - barTop) / steps
            for (i in 0 until steps) {
                barPaint.color = heatColor(1f - i / (steps - 1f), 255)
                canvas.drawRect(barLeft, barTop + i * stepH, barLeft + 30f, barTop + (i + 1) * stepH + 1f, barPaint)
            }
            smallText.textAlign = Paint.Align.RIGHT
            canvas.drawText("${max.roundToInt()}°", barLeft - 10f, barTop + 30f, smallText)
            canvas.drawText("${min.roundToInt()}°", barLeft - 10f, barBottom, smallText)
            smallText.textAlign = Paint.Align.LEFT
        }

        // Прицел
        val r = 40f
        canvas.drawCircle(cx, cy, r, linePaint)
        canvas.drawLine(cx - r - 20, cy, cx - r + 10, cy, linePaint)
        canvas.drawLine(cx + r - 10, cy, cx + r + 20, cy, linePaint)
        canvas.drawLine(cx, cy - r - 20, cx, cy - r + 10, linePaint)
        canvas.drawLine(cx, cy + r - 10, cx, cy + r + 20, linePaint)

        val label = currentTemp?.let { String.format("%.1f °C", it) } ?: "-- °C"
        canvas.drawText(label, cx, cy + r + 100f, textPaint)

        if (demo) {
            canvas.drawText("ДЕМО: настоящий датчик не найден", 30f, 120f, smallText)
        }
        if (mode == Mode.HEATMAP) {
            canvas.drawText("Води телефоном по объекту, карта заполнится", 30f, 170f, smallText)
        }
        if (debug.isNotEmpty()) {
            var ty = 230f
            for (line in debug.split("\n")) {
                canvas.drawText(line, 30f, ty, smallText)
                ty += 44f
            }
        }
    }

    /** 0 = холодно (синий), 1 = горячо (красный) */
    private fun heatColor(v: Float, alpha: Int): Int {
        val hue = (1f - v.coerceIn(0f, 1f)) * 240f
        val c = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
    }
}
