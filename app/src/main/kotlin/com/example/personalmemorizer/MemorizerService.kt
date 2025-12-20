package com.example.personalmemorizer

import android.app.AlarmManager
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
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class MemorizerService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private var audioFilePath: String? = null
    private lateinit var audioManager: AudioManager
    private lateinit var alarmManager: AlarmManager
    private var wakeLock: PowerManager.WakeLock? = null
    
    // التوقيتات: 10 ثواني، دقيقة، 5 دقائق، ثم كل 10 دقائق
    private val intervals = listOf(10000L, 60000L, 300000L, 600000L)
    private var intervalIndex = 0

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_PLAY_NOW = "ACTION_PLAY_NOW"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Memorizer:CoreLock")
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        // التعامل مع الأوامر
        when (action) {
            ACTION_START -> {
                audioFilePath = intent.getStringExtra("filePath")
                if (audioFilePath != null) {
                    intervalIndex = 0
                    startForegroundServiceNotif()
                    playAudioAggressive() // تشغيل فوري
                }
            }
            ACTION_PLAY_NOW -> {
                // استيقظ من المنبه!
                // استعادة المسار من الذاكرة إذا كان مفقوداً
                if (audioFilePath == null) {
                   // محاولة البحث في الكاش عن آخر ملف
                   val file = java.io.File(cacheDir, "memorizer_audio.mp3")
                   if (file.exists()) audioFilePath = file.absolutePath
                }
                startForegroundServiceNotif()
                playAudioAggressive()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotif() {
        val channelId = "memorizer_core"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Memorizer Core", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Memorizer Active")
            .setContentText("Next revision scheduled...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun playAudioAggressive() {
        if (audioFilePath == null) return

        wakeLock?.acquire(60000L) // استيقظ لمدة دقيقة

        try {
            // 1. طلب إيقاف الآخرين (PAUSE others) وليس خفض صوتهم فقط
            // هذا يضمن أن يوتيوب/تيك توك سيتوقفون وتسمع أنت بوضوح
            val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) // تغيير هام: حذف Ducking
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(this)
                    .build()
            } else null

            val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }

            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(audioFilePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            audioManager.abandonAudioFocusRequest(focusRequest!!)
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.abandonAudioFocus(this@MemorizerService)
                        }
                        scheduleNextAlarm() // جدولة المرة القادمة
                    }
                    setOnErrorListener { _, _, _ ->
                        scheduleNextAlarm()
                        true
                    }
                }
            } else {
                // فشل الحصول على الصوت (مكالمة مثلاً)، نحاول مرة أخرى قريباً
                scheduleRetry()
            }
        } catch (e: Exception) {
            scheduleRetry()
        }
    }

    private fun scheduleNextAlarm() {
        // تحديد الوقت القادم
        val delay = intervals[intervalIndex]
        
        // الانتقال للمرحلة التالية (مع الثبات في النهاية)
        if (intervalIndex < intervals.size - 1) intervalIndex++

        setAlarm(delay)
    }

    private fun scheduleRetry() {
        setAlarm(10000L) // المحاولة بعد 10 ثواني
    }

    private fun setAlarm(delayMs: Long) {
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        
        val intent = Intent(this, MemorizerService::class.java).apply {
            action = ACTION_PLAY_NOW
        }
        val pendingIntent = PendingIntent.getService(
            this, 
            999, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // استخدام المنبه الدقيق (يوقظ الهاتف من النوم العميق)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        }
        
        // نطلق القفل للحفاظ على البطارية حتى الموعد القادم
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onAudioFocusChange(focusChange: Int) {
        // إدارة المقاطعات
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            try { mediaPlayer?.stop() } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // إلغاء المنبهات عند الإيقاف اليدوي
        val intent = Intent(this, MemorizerService::class.java).apply { action = ACTION_PLAY_NOW }
        val pendingIntent = PendingIntent.getService(this, 999, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
        mediaPlayer?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
