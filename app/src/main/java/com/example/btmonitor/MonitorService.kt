package com.example.btmonitor

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val CHANNEL_ID = "bt_monitor_channel"
        const val NOTIF_ID = 1
        var isRunning = false
        var micSource = MediaRecorder.AudioSource.MIC
    }
    
    private lateinit var audioManager: AudioManager
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var loopThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val SAMPLE_RATE = 44100
    private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    handler.postDelayed({
                        audioManager.isBluetoothScoOn = true
                        updateNotification("BT connesso — ascolto attivo")
                        startAudioLoop()
                    }, 500)
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    updateNotification("BT disconnesso")
                    stopAudioLoop()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> { stopMonitoring(); stopSelf() }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        isRunning = true

        // WakeLock per tenere CPU attiva in standby
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "BTMonitor::WakeLock"
        ).also { it.acquire(60 * 60 * 1000L) } // max 1 ora

        startForeground(NOTIF_ID, buildNotification("Connessione BT SCO…"))

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        registerReceiver(
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )
        audioManager.startBluetoothSco()
    }

    private fun stopMonitoring() {
        isRunning = false
        stopAudioLoop()

        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false

        try { unregisterReceiver(scoReceiver) } catch (_: Exception) {}
        wakeLock?.release()
        wakeLock = null
    }

    private fun startAudioLoop() {
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING),
            AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        ) * 2

        audioRecord = AudioRecord(
            micSource,
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "BT Monitor",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BT Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        isRunning = false
    }
}
