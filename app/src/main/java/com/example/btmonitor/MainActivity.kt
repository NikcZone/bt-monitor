package com.example.btmonitor
import android.media.MediaRecorder
import android.widget.RadioGroup
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)

        btnToggle.setOnClickListener {
            if (!MonitorService.isRunning) startMonitoring() else stopMonitoring()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        if (MonitorService.isRunning) {
            btnToggle.text = "Stop"
            tvStatus.text = "Servizio attivo"
        } else {
            btnToggle.text = "Start"
            tvStatus.text = "Pronto"
        }
    }

    private fun startMonitoring() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            return
        }

        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
        }
        var rb = findViewById<RadioGroup>(R.id.rgMicMode)
        MonitorService.micSource = if (rb.checkedRadioButtonId == R.id.rbMic)
	MediaRecorder.AudioSource.MIC
        else
	MediaRecorder.AudioSource.VOICE_COMMUNICATION

        startForegroundService(intent)
        updateUI()
    }

    private fun stopMonitoring() {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP
        }
        startService(intent)
        btnToggle.text = "Start"
        tvStatus.text = "Pronto"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startMonitoring()
        }
    }
}
