package com.example.thermocam

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var previewView: PreviewView
    private lateinit var thermalOverlay: ThermalOverlayView
    private lateinit var modeButton: Button

    // Все найденные ИК-датчики (fir_temperature, fir_extended_temperature)
    private var firSensors: List<Sensor> = emptyList()
    private var hasRealSensor = false

    // Диагностика по каждому датчику
    private val registerInfo = HashMap<String, String>()
    private val eventCount = HashMap<String, Int>()
    private val lastValues = HashMap<String, String>()
    private val lastArr = HashMap<String, FloatArray>()
    private var valueIndex = 0

    private var yaw = 0f
    private var pitch = 0f

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    // Если карта отображается зеркально по вертикали, поменяй на -1f
    private val pitchSign = 1f

    private val firPermission = "com.google.sensor.permission.FAR_INFRARED_TEMPERATURE"

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) startCamera()
            registerFirSensors()
        }

    // Для датчиков с одноразовым режимом (one-shot)
    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            handleValues(event.sensor, event.values)
            // Запрашиваем следующее измерение
            sensorManager.requestTriggerSensor(this, event.sensor)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        firSensors = findFirSensors()
        hasRealSensor = firSensors.isNotEmpty()

        previewView = PreviewView(this)
        thermalOverlay = ThermalOverlayView(this)
        thermalOverlay.demo = !hasRealSensor

        modeButton = Button(this)
        modeButton.text = "Режим: Измерение"
        modeButton.setOnClickListener { toggleMode() }

        val clearButton = Button(this)
        clearButton.text = "Сброс"
        clearButton.setOnClickListener { thermalOverlay.clear() }

        val sensorsButton = Button(this)
        sensorsButton.text = "Датчики"
        sensorsButton.setOnClickListener { showSensors() }

        val channelButton = Button(this)
        channelButton.text = "Канал: 0"
        channelButton.setOnClickListener {
            valueIndex = (valueIndex + 1) % 3
            channelButton.text = "Канал: $valueIndex"
            thermalOverlay.clear()
        }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        bar.setBackgroundColor(Color.argb(120, 0, 0, 0))
        bar.addView(modeButton)
        bar.addView(clearButton)
        bar.addView(sensorsButton)
        bar.addView(channelButton)

        val root = FrameLayout(this)
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            thermalOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        setContentView(root)

        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else needed.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, firPermission) !=
            PackageManager.PERMISSION_GRANTED
        ) needed.add(firPermission)
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun toggleMode() {
        if (thermalOverlay.mode == ThermalOverlayView.Mode.MEASURE) {
            thermalOverlay.mode = ThermalOverlayView.Mode.HEATMAP
            modeButton.text = "Режим: Тепловая карта"
        } else {
            thermalOverlay.mode = ThermalOverlayView.Mode.MEASURE
            modeButton.text = "Режим: Измерение"
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Ищем ИК-термометры среди всех датчиков. Основной (fir_temperature) идёт первым. */
    private fun findFirSensors(): List<Sensor> {
        val all = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val fir = all.filter {
            val s = (it.name + " " + it.stringType).lowercase()
            s.contains("fir_temperature") || s.contains("fir_extended_temperature") ||
                s.contains("mlx") || s.contains("infrared")
        }
        return fir.sortedBy { if (it.stringType.contains("extended")) 1 else 0 }
    }

    private fun modeName(s: Sensor): String = when (s.reportingMode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "on-change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "one-shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special"
        else -> "?"
    }

    private fun key(s: Sensor) = s.name

    private fun debugText(): String {
        if (firSensors.isEmpty()) return ""
        val sb = StringBuilder()
        val perm = if (ContextCompat.checkSelfPermission(this, firPermission) ==
            PackageManager.PERMISSION_GRANTED) "granted" else "DENIED"
        sb.append("permission: $perm\n")
        for (s in firSensors) {
            val k = key(s)
            val short = if (s.stringType.contains("extended")) "EXT" else "FIR"
            sb.append("$short ${modeName(s)} reg=${registerInfo[k] ?: "-"} ")
            sb.append("n=${eventCount[k] ?: 0} v=${lastValues[k] ?: "-"}\n")
        }
        return sb.toString().trimEnd()
    }

    private fun handleValues(sensor: Sensor, values: FloatArray) {
        val k = key(sensor)
        eventCount[k] = (eventCount[k] ?: 0) + 1
        lastValues[k] = values.take(3).joinToString(",") { String.format("%.2f", it) }
        lastArr[k] = values.copyOf()
    }

    private fun currentTemp(): Float? {
        for (s in firSensors) {
            val a = lastArr[key(s)]
            if (a != null && valueIndex < a.size) {
                var v = a[valueIndex]
                // Если значение похоже на кельвины, переводим в цельсии
                if (v > 200f) v -= 273.15f
                return v
            }
        }
        return null
    }

    private fun showSensors() {
        val header = StringBuilder()
        header.append(debugText()).append("\n\n")
        for (s in firSensors) {
            header.append("${s.name}\n  type=${s.stringType}\n  mode=${modeName(s)} ")
            header.append("minDelay=${s.minDelay} maxDelay=${s.maxDelay} range=${s.maximumRange}\n\n")
        }
        val list = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .joinToString("\n") { "${it.name}\n  ${it.stringType}" }
        val found = if (hasRealSensor) "найден" else "не найден (демо)"
        AlertDialog.Builder(this)
            .setTitle("ИК-датчик: $found")
            .setMessage(header.toString() + list)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        registerFirSensors()
    }

    private fun registerFirSensors() {
        for (s in firSensors) {
            val k = key(s)
            val ok = if (s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
                sensorManager.requestTriggerSensor(triggerListener, s)
            } else {
                sensorManager.registerListener(this, s, 50_000)
            }
            registerInfo[k] = if (ok) "ok" else "FAIL"
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        for (s in firSensors) {
            if (s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
                sensorManager.cancelTriggerSensor(triggerListener, s)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            // Камера смотрит "в экран телефона насквозь" (вдоль -Z устройства)
            SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped
            )
            SensorManager.getOrientation(remapped, orientation)
            yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
            pitch = pitchSign * Math.toDegrees(orientation[1].toDouble()).toFloat()

            val temp = if (hasRealSensor) currentTemp() else demoTemp(yaw, pitch)
            thermalOverlay.debug = debugText()
            thermalOverlay.update(yaw, pitch, temp)
        } else {
            handleValues(event.sensor, event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Демо: несколько "горячих" пятен в пространстве, чтобы можно было проверить визуализацию. */
    private fun demoTemp(yaw: Float, pitch: Float): Float {
        fun blob(cy: Float, cp: Float, size: Float): Float {
            var dy = yaw - cy
            while (dy > 180) dy -= 360f
            while (dy < -180) dy += 360f
            val dp = pitch - cp
            return exp(-(dy * dy + dp * dp) / (2 * size * size))
        }
        val a = blob(0f, 0f, 12f)
        val b = blob(25f, 8f, 6f)
        return 22f + 38f * a + 20f * b + 0.5f * sin(yaw / 7f) * cos(pitch / 5f)
    }
}
