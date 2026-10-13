package de.rochefort.childmonitor

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaPlayer
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class AudioCalibrationActivity : Activity() {

    private lateinit var captureSeekBar: SeekBar
    private lateinit var playbackSeekBar: SeekBar
    private lateinit var captureValueText: TextView
    private lateinit var playbackValueText: TextView

    private var captureMultiplier = AudioCalibrationPrefs.DEFAULT_CAPTURE_GAIN_MULTIPLIER
    private var playbackMultiplier = AudioCalibrationPrefs.DEFAULT_PLAYBACK_GAIN_MULTIPLIER

    private var monitorService: MonitorService? = null
    private var listenService: ListenService? = null
    private var monitorBound = false
    private var listenBound = false

    private val monitorConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MonitorService.MonitorBinder ?: return
            monitorService = binder.service
            monitorBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            monitorBound = false
            monitorService = null
        }
    }

    private val listenConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ListenService.ListenBinder ?: return
            listenService = binder.service
            listenBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            listenBound = false
            listenService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_calibration)
        ThemeManager.applyTheme(this)

        captureSeekBar = findViewById(R.id.captureSeekBar)
        playbackSeekBar = findViewById(R.id.playbackSeekBar)
        captureValueText = findViewById(R.id.captureGainValue)
        playbackValueText = findViewById(R.id.playbackGainValue)

        captureSeekBar.max = CAPTURE_STEPS
        playbackSeekBar.max = PLAYBACK_STEPS

        captureMultiplier = AudioCalibrationPrefs.getCaptureGainMultiplier(this)
        playbackMultiplier = AudioCalibrationPrefs.getPlaybackGainMultiplier(this)
        captureSeekBar.progress = multiplierToCaptureProgress(captureMultiplier)
        playbackSeekBar.progress = multiplierToPlaybackProgress(playbackMultiplier)
        updateLabels()

        captureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                captureMultiplier = captureProgressToMultiplier(progress)
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                playbackMultiplier = playbackProgressToMultiplier(progress)
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.applyCalibrationButton).setOnClickListener {
            applyCalibration()
        }
        findViewById<Button>(R.id.resetCalibrationButton).setOnClickListener {
            resetCalibration()
        }
        findViewById<Button>(R.id.previewAlertButton).setOnClickListener {
            playPreviewAlert()
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        bindRunningServices()
    }

    override fun onPause() {
        unbindServices()
        super.onPause()
    }

    private fun bindRunningServices() {
        if (!monitorBound && isServiceRunning(MonitorService::class.java)) {
            bindService(Intent(this, MonitorService::class.java), monitorConnection, BIND_AUTO_CREATE)
        }
        if (!listenBound && isServiceRunning(ListenService::class.java)) {
            bindService(Intent(this, ListenService::class.java), listenConnection, BIND_AUTO_CREATE)
        }
    }

    private fun unbindServices() {
        if (monitorBound) {
            unbindService(monitorConnection)
            monitorBound = false
        }
        if (listenBound) {
            unbindService(listenConnection)
            listenBound = false
        }
    }

    private fun applyCalibration() {
        AudioCalibrationPrefs.setCaptureGainMultiplier(this, captureMultiplier)
        AudioCalibrationPrefs.setPlaybackGainMultiplier(this, playbackMultiplier)

        monitorService?.setCaptureCalibrationMultiplier(captureMultiplier)
        listenService?.setPlaybackCalibrationMultiplier(playbackMultiplier)

        AppToast.show(this, "Audio calibration saved.", Toast.LENGTH_SHORT)
    }

    private fun resetCalibration() {
        captureMultiplier = AudioCalibrationPrefs.DEFAULT_CAPTURE_GAIN_MULTIPLIER
        playbackMultiplier = AudioCalibrationPrefs.DEFAULT_PLAYBACK_GAIN_MULTIPLIER
        captureSeekBar.progress = multiplierToCaptureProgress(captureMultiplier)
        playbackSeekBar.progress = multiplierToPlaybackProgress(playbackMultiplier)
        updateLabels()
        applyCalibration()
    }

    private fun playPreviewAlert() {
        val player = MediaPlayer.create(this, R.raw.upward_beep_chromatic_fifths) ?: return
        val volume = ((playbackMultiplier - 1.0f) / 1.5f + 0.6f).coerceIn(0.1f, 1.0f)
        player.setVolume(volume, volume)
        player.setOnCompletionListener { it.release() }
        player.start()
    }

    private fun updateLabels() {
        captureValueText.text = getString(R.string.audio_calibration_capture_gain, captureMultiplier)
        playbackValueText.text = getString(R.string.audio_calibration_playback_gain, playbackMultiplier)
    }

    private fun captureProgressToMultiplier(progress: Int): Float {
        val multiplier = 1.0f + (progress / 20.0f)
        return AudioCalibrationPrefs.clampCapture(multiplier)
    }

    private fun playbackProgressToMultiplier(progress: Int): Float {
        val multiplier = 1.0f + (progress / 20.0f)
        return AudioCalibrationPrefs.clampPlayback(multiplier)
    }

    private fun multiplierToCaptureProgress(multiplier: Float): Int {
        return (((AudioCalibrationPrefs.clampCapture(multiplier) - 1.0f) * 20.0f).toInt())
            .coerceIn(0, CAPTURE_STEPS)
    }

    private fun multiplierToPlaybackProgress(multiplier: Float): Int {
        return (((AudioCalibrationPrefs.clampPlayback(multiplier) - 1.0f) * 20.0f).toInt())
            .coerceIn(0, PLAYBACK_STEPS)
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == serviceClass.name && it.foreground
        }
    }

    companion object {
        private const val CAPTURE_STEPS = 40 // 1.0x .. 3.0x in 0.05 increments
        private const val PLAYBACK_STEPS = 30 // 1.0x .. 2.5x in 0.05 increments
    }
}
