package com.example.btmonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var audioManager: AudioManager
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRunning = false
    private var loopThread: Thread? = null

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView

    // 16kHz mono PCM16 — compatibile con BT SCO wideband
    private val SAMPLE_RATE = 16000
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    tvStatus.text = "BT SCO connesso — registrazione in corso"
                    startAudioLoop()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    tvStatus.text = "BT SCO disconnesso"
                    stopAudioLoop()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)

        btnToggle.setOnClickListener {
            if (!isRunning) startMonitoring() else stopMonitoring()
        }
    }

    private fun startMonitoring() {
        if (!checkPermissions()) return

        isRunning = true
        btnToggle.text = "Stop"
        tvStatus.text = "Connessione BT SCO…"

        // Imposta modalità comunicazione e avvia SCO
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        registerReceiver(
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    private fun stopMonitoring() {
        isRunning = false
        stopAudioLoop()

        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.mode = AudioManager.MODE_NORMAL

        try { unregisterReceiver(scoReceiver) } catch (_: Exception) {}

        btnToggle.text = "Start"
        tvStatus.text = "Pronto"
    }

    private fun startAudioLoop() {
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING),
            AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        ) * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .setEncoding(ENCODING)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        audioRecord?.startRecording()
        audioTrack?.play()

        loopThread = Thread {
            val buffer = ShortArray(bufSize / 2)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) audioTrack?.write(buffer, 0, read)
            }
        }.also { it.start() }
    }

    private fun stopAudioLoop() {
        loopThread?.interrupt()
        loopThread = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
    }

    private fun checkPermissions(): Boolean {
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) true
        else { ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1); false }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) stopMonitoring()
    }
}
