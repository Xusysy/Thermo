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
    private lateinit var overlay: ThermalOverlayView
    private lateinit var modeButton: Button

    private var tempSensor: Sensor? = null
    private var lastRealTemp: Float? = null

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    // Если карта отображается зеркально по вертикали, поменяй на -1f
    private val pitchSign = 1f

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        tempSensor = findTemperatureSensor()

        previewView = PreviewView(this)
        overlay = ThermalOverlayView(this).apply { demo = tempSensor == null }

        modeButton = Button(this).apply {
            text = "Режим: Измерение"
            setOnClickListener { toggleMode() }
        }
        val clearButton = Button(this).apply {
            text = "Сброс"
            setOnClickListener { overlay.clear() }
        }
        val sensorsButton = Button(this).apply {
            text = "Датчики"
            setOnClickListener { showSensors() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            addView(modeButton)
            addView(clearButton)
            addView(sensorsButton)
        }

        val root = FrameLayout(this).apply {
            addView(
                previewView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                bar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun toggleMode() {
        if (overlay.mode == ThermalOverlayView.Mode.MEASURE) {
            overlay.mode = ThermalOverlayView.Mode.HEATMAP
            modeButton.text = "Режим: Тепловая карта"
        } else {
            overlay.mode = ThermalOverlayView.Mode.MEASURE
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

    /** Ищем датчик температуры объекта (ИК-термометр) среди всех датчиков устройства. */
    private fun findTemperatureSensor(): Sensor? {
        val all = sensorManager.getSensorList(Sensor.TYPE_ALL)
        return all.firstOrNull {
            val s = (it.name + " " + it.stringType).lowercase()
            (s.contains("thermo") || s.contains("object") || s.contains("infrared") || s.contains("mlx")) &&
                it.type != Sensor.TYPE_AMBIENT_TEMPERATURE
        }
    }

    private fun showSensors() {
        val list = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .joinToString("\n") { "${it.name}\n  ${it.stringType}" }
        val found = tempSensor?.name ?: "не найден (работает демо-режим)"
        AlertDialog.Builder(this)
            .setTitle("Термодатчик: $found")
            .setMessage(list)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        tempSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // Камера смотрит "в экран телефона насквозь" (вдоль -Z устройства)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped
                )
                SensorManager.getOrientation(remapped, orientation)
                val yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val pitch = pitchSign * Math.toDegrees(orientation[1].toDouble()).toFloat()

                val temp = if (tempSensor != null) lastRealTemp else demoTemp(yaw, pitch)
                overlay.update(yaw, pitch, temp)
            }
            else -> {
                if (event.sensor == tempSensor) lastRealTemp = event.values[0]
            }
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
