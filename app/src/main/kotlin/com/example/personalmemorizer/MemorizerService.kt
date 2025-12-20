package com.example.personalmemorizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File

class MemorizerService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var handler: Handler? = null
    private var audioFilePath: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager
    
    private val intervals = listOf(10000L, 60000L, 300000L, 1800000L) // SRS Intervals
    private var intervalIndex = 0

    override fun onCreate() {
        super.onCreate()
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Memorizer:WakeLock")
            wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 Hours
            handler = Handler(Looper.getMainLooper())
        } catch (e: Exception) {
            // Prevent crash on initialization
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            audioFilePath = intent?.getStringExtra("filePath")
            startForegroundNotification()
            
            if (audioFilePath != null) {
                intervalIndex = 0
                playAudioSafe()
            }
        } catch (e: Exception) {
            stopSelf() // Stop cleanly instead of crashing
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "memorizer_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Memorizer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Memorizer Running")
            .setContentText("Saving info to long-term memory...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun playAudioSafe() {
        if (audioFilePath == null) return

        try {
            // Audio Focus & Ducking
            val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
            } else null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }

            // تشغيل الملف بأمان
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFilePath) // القراءة من ملف داخلي مباشر
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                prepare()
                start()
                setOnCompletionListener {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        audioManager.abandonAudioFocusRequest(focusRequest!!)
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.abandonAudioFocus(null)
                    }
                    scheduleNext()
                }
                // في حال حدوث خطأ أثناء التشغيل، لا تنهار!
                setOnErrorListener { _, _, _ ->
                    scheduleNext() // حاول مرة أخرى لاحقاً
                    true
                }
            }
        } catch (e: Exception) {
            // Log error but don't crash
            e.printStackTrace()
            scheduleNext() // Retry later
        }
    }

    private fun scheduleNext() {
        val delay = if (intervalIndex < intervals.size) intervals[intervalIndex] else intervals.last()
        if (intervalIndex < intervals.size - 1) intervalIndex++
        
        handler?.postDelayed({
            playAudioSafe()
        }, delay)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            handler?.removeCallbacksAndMessages(null)
            mediaPlayer?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
