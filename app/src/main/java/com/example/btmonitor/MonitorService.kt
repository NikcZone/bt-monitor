package com.example.btmonitor

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_REC_START = "REC_START"
        const val ACTION_REC_PAUSE = "REC_PAUSE"
        const val ACTION_REC_STOP = "REC_STOP"
        const val CHANNEL_ID = "bt_monitor_channel"
        const val NOTIF_ID = 1
        var isRunning = false
        var isRecording = false
        var isPaused = false
        var micSource = MediaRecorder.AudioSource.MIC
        var lastRecordingPath = ""
    }

    private lateinit var audioManager: AudioManager
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var loopThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    // AAC recording
    private var mediaCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var outputFile: File? = null

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
            ACTION_REC_START -> startRecording()
            ACTION_REC_PAUSE -> togglePause()
            ACTION_REC_STOP -> stopRecording()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        isRunning = true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "BTMonitor::WakeLock"
        ).also { it.acquire(60 * 60 * 1000L) }

        startForeground(NOTIF_ID, buildNotification("Connessione BT SCO…"))
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        audioManager.startBluetoothSco()
    }

    private fun stopMonitoring() {
        if (isRecording) stopRecording()
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

        audioRecord = AudioRecord(micSource, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize)

        // Forza microfono interno
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val phoneMic = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        phoneMic?.let { audioRecord?.preferredDevice = it }

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
            bufSize, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        audioRecord?.startRecording()
        audioTrack?.play()

        loopThread = Thread {
            val buffer = ShortArray(bufSize / 2)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    audioTrack?.write(buffer, 0, read)
                    if (isRecording && !isPaused) {
                        encodeAudio(buffer, read)
                    }
                }
            }
        }.also { it.start() }
    }

    private fun stopAudioLoop() {
        loopThread?.interrupt()
        loopThread = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
    }

    // ---- AAC Recording ----

    private fun startRecording() {
        if (!isRunning) return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        dir.mkdirs()
        outputFile = File(dir, "rec_$timestamp.aac")
        lastRecordingPath = outputFile!!.absolutePath

        // Setup MediaCodec AAC encoder
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec?.start()

        muxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = -1
        muxerStarted = false

        isRecording = true
        isPaused = false
        updateNotification("● REC in corso")
    }

    private fun togglePause() {
        if (!isRecording) return
        isPaused = !isPaused
        updateNotification(if (isPaused) "⏸ REC in pausa" else "● REC in corso")
    }

    private fun stopRecording() {
        isRecording = false
        isPaused = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            if (muxerStarted) muxer?.stop()
            muxer?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        muxer = null
        muxerStarted = false
        updateNotification("BT connesso — ascolto attivo")
    }

    private fun encodeAudio(buffer: ShortArray, readSize: Int) {
        val codec = mediaCodec ?: return
        val mx = muxer ?: return

        // Convert ShortArray to ByteArray
        val byteBuffer = java.nio.ByteBuffer.allocate(readSize * 2)
        byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until readSize) byteBuffer.putShort(buffer[i])
        val bytes = byteBuffer.array()

        val inputIndex = codec.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val inputBuf = codec.getInputBuffer(inputIndex)!!
            inputBuf.clear()
            inputBuf.put(bytes)
            codec.queueInputBuffer(inputIndex, 0, bytes.size, System.nanoTime() / 1000, 0)
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
        while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = mx.addTrack(codec.outputFormat)
                mx.start()
                muxerStarted = true
            } else if (outputIndex >= 0) {
                val outputBuf = codec.getOutputBuffer(outputIndex)!!
                if (muxerStarted && bufferInfo.size > 0) {
                    mx.writeSampleData(trackIndex, outputBuf, bufferInfo)
                }
                codec.releaseOutputBuffer(outputIndex, false)
            }
            outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "BT Monitor", NotificationManager.IMPORTANCE_LOW)
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
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        isRunning = false
    }
}
