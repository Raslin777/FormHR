package com.ivionics.formhr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** Keeps direct watch-sensor HR and the FORM bridge alive without owning an exercise. */
class HeartRateForegroundService : Service() {
    private lateinit var bridge: PublicAddressBridgePeripheral
    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null

    @Volatile
    private var sessionRequested = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!sessionRequested || event.sensor.type != Sensor.TYPE_HEART_RATE) return
            val bpm = event.values.firstOrNull()?.roundToInt() ?: return
            if (bpm !in MIN_HEART_RATE..MAX_HEART_RATE) return
            FormHrSession.heartRate(bpm)
            FormHrSession.sensorStatus("Direct watch HR active in background")
            bridge.updateHeartRate(bpm)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            if (!sessionRequested || sensor.type != Sensor.TYPE_HEART_RATE) return
            val status = when (accuracy) {
                SensorManager.SENSOR_STATUS_NO_CONTACT -> "Waiting for skin contact…"
                SensorManager.SENSOR_STATUS_UNRELIABLE -> "Waiting for a reliable HR reading…"
                else -> "Direct watch HR active in background"
            }
            FormHrSession.sensorStatus(status)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = getSystemService(SensorManager::class.java)
        bridge = PublicAddressBridgePeripheral(
            onStatus = FormHrSession::peripheralStatus,
            onEvent = FormHrSession::event,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            else -> startSession()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sessionRequested = false
        sensorManager.unregisterListener(sensorListener)
        bridge.close()
        super.onDestroy()
    }

    private fun startSession() {
        if (sessionRequested) return
        sessionRequested = true
        FormHrSession.begin()
        startForeground(NOTIFICATION_ID, buildNotification())
        bridge.start()

        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (sensor == null) {
            fail("This watch does not expose a heart-rate sensor")
            return
        }
        heartRateSensor = sensor
        val registered = sensorManager.registerListener(
            sensorListener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            0,
        )
        if (!registered) {
            fail("Could not register the watch heart-rate sensor")
            return
        }
        val delivery = if (sensor.isWakeUpSensor) "wake-up" else "standard"
        FormHrSession.sensorStatus("Waiting for direct watch HR…")
        FormHrSession.event("Direct $delivery HR sensor registered")
    }

    private fun stopSession() {
        if (!sessionRequested) {
            finishService()
            return
        }
        sessionRequested = false
        sensorManager.unregisterListener(sensorListener)
        heartRateSensor = null
        bridge.stop()
        FormHrSession.stopped("Watch sensor stopped")
        finishService()
    }

    private fun fail(message: String) {
        sessionRequested = false
        sensorManager.unregisterListener(sensorListener)
        heartRateSensor = null
        bridge.stop()
        FormHrSession.failed(message)
        finishService()
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, HeartRateForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("FORM HR is active")
            .setContentText("Pixel Watch heart rate continues without owning a workout")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Notification.CATEGORY_WORKOUT
                } else {
                    Notification.CATEGORY_SERVICE
                },
            )
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active heart-rate broadcast",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Pixel Watch heart rate streaming to FORM"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_START = "com.ivionics.formhr.action.START"
        private const val ACTION_STOP = "com.ivionics.formhr.action.STOP"
        private const val CHANNEL_ID = "formhr_active_session"
        private const val NOTIFICATION_ID = 37
        private const val MIN_HEART_RATE = 30
        private const val MAX_HEART_RATE = 240

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HeartRateForegroundService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HeartRateForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
