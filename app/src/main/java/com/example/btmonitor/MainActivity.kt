package com.example.btmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var btnRec: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvRecStatus: TextView
    private lateinit var rgMicMode: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        btnRec    = findViewById(R.id.btnRec)
        btnPause  = findViewById(R.id.btnPause)
        btnStop   = findViewById(R.id.btnStop)
        tvStatus  = findViewById(R.id.tvStatus)
        tvRecStatus = findViewById(R.id.tvRecStatus)
        rgMicMode = findViewById(R.id.rgMicMode)

        btnToggle.setOnClickListener {
            if (!MonitorService.isRunning) startMonitoring() else stopMonitoring()
        }
        btnRec.setOnClickListener { sendAction(MonitorService.ACTION_REC_START); updateRecUI() }
        btnPause.setOnClickListener { sendAction(MonitorService.ACTION_REC_PAUSE); updateRecUI() }
        btnStop.setOnClickListener { sendAction(MonitorService.ACTION_REC_STOP); updateRecUI() }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        if (MonitorService.isRunning) {
            btnToggle.text = "Stop Monitor"
            tvStatus.text = "Monitor attivo"
            btnRec.isEnabled = true
        } else {
            btnToggle.text = "Start Monitor"
            tvStatus.text = "Pronto"
            btnRec.isEnabled = false
            btnPause.isEnabled = false
            btnStop.isEnabled = false
        }
        updateRecUI()
    }

    private fun updateRecUI() {
        when {
            MonitorService.isRecording && !MonitorService.isPaused -> {
                tvRecStatus.text = "● REC"
                btnRec.isEnabled = false
                btnPause.isEnabled = true
                btnStop.isEnabled = true
            }
            MonitorService.isRecording && MonitorService.isPaused -> {
                tvRecStatus.text = "⏸ PAUSA"
                btnRec.isEnabled = false
                btnPause.isEnabled = true
                btnStop.isEnabled = true
            }
            else -> {
                tvRecStatus.text = ""
                btnRec.isEnabled = MonitorService.isRunning
                btnPause.isEnabled = false
                btnStop.isEnabled = false
            }
        }
    }

    private fun startMonitoring() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            return
        }

        MonitorService.micSource = if (rgMicMode.checkedRadioButtonId == R.id.rbMic)
            MediaRecorder.AudioSource.MIC
        else
            MediaRecorder.AudioSource.VOICE_COMMUNICATION

        sendAction(MonitorService.ACTION_START)
        updateUI()
    }

    private fun stopMonitoring() {
        sendAction(MonitorService.ACTION_STOP)
        btnToggle.text = "Start Monitor"
        tvStatus.text = "Pronto"
        tvRecStatus.text = ""
        btnRec.isEnabled = false
        btnPause.isEnabled = false
        btnStop.isEnabled = false
    }

    private fun sendAction(action: String) {
        val intent = Intent(this, MonitorService::class.java).apply { this.action = action }
        startForegroundService(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startMonitoring()
    }
}
