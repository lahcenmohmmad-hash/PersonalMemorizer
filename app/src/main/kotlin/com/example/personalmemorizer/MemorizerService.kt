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
import android.os.CountDownTimer
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.TimeUnit

class MemorizerService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private var audioFilePath: String? = null
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    
    // كائن العداد التنازلي
    private var countDownTimer: CountDownTimer? = null

    // التوقيتات: 10 ثواني، دقيقة، 5 دقائق، 10 دقائق
    private val intervals = listOf(10000L, 60000L, 300000L, 600000L)
    private var intervalIndex = 0

    companion object {
        const val CHANNEL_ID = "memorizer_live_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // قفل المعالج لمنع النوم أثناء العد التنازلي
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Memorizer:LiveLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // قفل لمدة 24 ساعة
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val filePath = intent?.getStringExtra("filePath")
        
        // إذا وصلنا مسار جديد، نبدأ من الصفر
        if (filePath != null) {
            audioFilePath = filePath
            intervalIndex = 0
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
            playAudioForcefully()
        } else if (audioFilePath == null) {
            // محاولة استعادة الملف من الكاش إذا أعاد النظام تشغيل الخدمة
            val cacheFile = File(cacheDir, "memorizer_audio.mp3") // اسم افتراضي محتمل
            // أو نعتمد على أن المتغيرات ستبقى حية، لو فقدناها نطلب من المستخدم البدء مجدداً
        }

        return START_STICKY
    }

    // دالة بناء الإشعار مع النص المتغير
    private fun buildNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Memorizer Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true) // هام جداً: يمنع الاهتزاز مع كل تحديث للثواني
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun playAudioForcefully() {
        if (audioFilePath == null) return
        
        // إيقاف العداد أثناء التشغيل
        countDownTimer?.cancel()
        updateNotification("Playing audio now...")

        try {
            // طلب إيقاف التطبيقات الأخرى (YouTube, Facebook) تماماً
            val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) // إيقاف مؤقت للآخرين
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
                        // عند الانتهاء، ابدأ العد التنازلي التالي
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            audioManager.abandonAudioFocusRequest(focusRequest!!)
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.abandonAudioFocus(this@MemorizerService)
                        }
                        startNextCountdown()
                    }
                    setOnErrorListener { _, _, _ ->
                        startNextCountdown() // لو فشل، ابدأ العد فوراً
                        true
                    }
                }
            } else {
                // لو فشل أخذ الصوت، انتظر 10 ثواني وحاول مجدداً
                startCountdown(10000L) 
            }
        } catch (e: Exception) {
            startCountdown(10000L)
        }
    }

    private fun startNextCountdown() {
        val delay = intervals[intervalIndex]
        // زيادة المؤشر للمرة القادمة (مع التثبيت على الأخير)
        if (intervalIndex < intervals.size - 1) intervalIndex++
        
        startCountdown(delay)
    }

    private fun startCountdown(durationMs: Long) {
        countDownTimer?.cancel()
        
        // إنشاء عداد تنازلي يحدث الإشعار كل ثانية
        countDownTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                // تحويل الميلي ثانية إلى دقائق وثواني
                val minutes = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % 60
                val timeString = String.format("%02d:%02d", minutes, seconds)
                
                // تحديث الإشعار ليرى المستخدم الوقت المتبقي
                updateNotification("Next play in: $timeString")
            }

            override fun onFinish() {
                updateNotification("Preparing to play...")
                playAudioForcefully()
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Memorizer Timer", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null) // بدون صوت للإشعار نفسه
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
        countDownTimer?.cancel()
        mediaPlayer?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
