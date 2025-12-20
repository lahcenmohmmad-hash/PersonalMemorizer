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
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MemorizerService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private var audioFilePath: String? = null
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var alarmManager: AlarmManager
    private var wakeLock: PowerManager.WakeLock? = null
    
    // متغير للتحكم في خيط العداد
    @Volatile private var isCounting = false
    private var currentThread: Thread? = null

    // التوقيتات: 10 ثواني، دقيقة، 5 دقائق، 10 دقائق
    private val intervals = listOf(10000L, 60000L, 300000L, 600000L)
    private var intervalIndex = 0

    companion object {
        const val CHANNEL_ID = "memorizer_nuclear_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_ALARM_TRIGGER = "ACTION_ALARM_TRIGGER"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // قفل قوي جداً للمعالج
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Memorizer:NuclearLock")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // قفل مستمر
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val filePath = intent?.getStringExtra("filePath")

        // إذا كان الاستدعاء بسبب المنبه (شبكة الأمان)
        if (action == ACTION_ALARM_TRIGGER) {
            playAudioForcefully()
            return START_STICKY
        }

        // إذا كان بدء تشغيل جديد
        if (filePath != null) {
            audioFilePath = filePath
            intervalIndex = 0
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("Starting brainwash..."))
            playAudioForcefully()
        }

        return START_STICKY
    }

    // تشغيل الصوت بالقوة
    private fun playAudioForcefully() {
        // إيقاف العداد السابق
        isCounting = false
        currentThread?.interrupt()

        // محاولة استعادة المسار من الكاش إذا فقدناه (يحدث عند قتل النظام للخدمة)
        if (audioFilePath == null) {
             val file = File(cacheDir, "audio_temp.mp3") // اسم افتراضي
             // ملاحظة: في النسخة السابقة من MainActivity لم نثبت الاسم، 
             // لكن طالما الخدمة حية سيبقى المتغير. هذه احتياط فقط.
        }
        if (audioFilePath == null) return

        updateNotification("Playing Audio Now!", false)

        try {
            // طلب إيقاف الآخرين (Pause)
            val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
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
                        scheduleNextRun()
                    }
                    setOnErrorListener { _, _, _ ->
                        scheduleNextRun() 
                        true
                    }
                }
            } else {
                // فشل الصوت؟ لا مشكلة، نجدول المحاولة القادمة قريباً
                startBackgroundCountdown(10000L)
            }
        } catch (e: Exception) {
            startBackgroundCountdown(10000L)
        }
    }

    private fun scheduleNextRun() {
        val delay = intervals[intervalIndex]
        if (intervalIndex < intervals.size - 1) intervalIndex++
        
        // 1. تفعيل المنبه (شبكة الأمان) في حال تجمد العداد
        setSafetyAlarm(delay)
        
        // 2. تشغيل العداد المرئي
        startBackgroundCountdown(delay)
    }

    // إعداد منبه النظام (يوقظ الميت)
    private fun setSafetyAlarm(delayMs: Long) {
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        val intent = Intent(this, MemorizerService::class.java).apply {
            action = ACTION_ALARM_TRIGGER
        }
        val pendingIntent = PendingIntent.getService(
            this, 999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        }
    }

    // عداد يعمل في الخلفية (Thread) بدلاً من الواجهة
    private fun startBackgroundCountdown(durationMs: Long) {
        isCounting = false
        currentThread?.interrupt()
        
        isCounting = true
        val endTime = System.currentTimeMillis() + durationMs
        
        currentThread = thread(start = true) {
            while (isCounting && System.currentTimeMillis() < endTime) {
                try {
                    val millisLeft = endTime - System.currentTimeMillis()
                    
                    // تحديث الإشعار كل ثانية
                    val timeString = String.format("%02d:%02d", 
                        TimeUnit.MILLISECONDS.toMinutes(millisLeft),
                        TimeUnit.MILLISECONDS.toSeconds(millisLeft) % 60
                    )
                    
                    updateNotification("Next play in: $timeString", true)
                    
                    Thread.sleep(1000) // النوم لمدة ثانية
                } catch (e: InterruptedException) {
                    isCounting = false
                } catch (e: Exception) {
                    // تجاهل الأخطاء العابرة
                }
            }
            
            // إذا انتهى الوقت ولم يتم إيقافنا يدوياً
            if (isCounting) {
                // نطلب من الخيط الرئيسي تشغيل الصوت
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                mainHandler.post {
                    playAudioForcefully()
                }
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Memorizer Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String, silent: Boolean) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Memorizer Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        
        if (silent) builder.setSilent(true)
        
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Memorizer Nuclear", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            try { mediaPlayer?.pause() } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCounting = false
        currentThread?.interrupt()
        mediaPlayer?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
