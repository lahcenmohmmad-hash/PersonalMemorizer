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

class MemorizerService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private var handler: Handler? = null
    private var audioFilePath: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager
    
    // التعديل 1: التكرار الذكي (SRS)
    // 10 ثواني، دقيقة، 5 دقائق، ثم كل 10 دقائق للأبد
    private val intervals = listOf(10000L, 60000L, 300000L, 600000L) 
    private var intervalIndex = 0

    override fun onCreate() {
        super.onCreate()
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            
            // التعديل 2: قفل المعالج بقوة لمنع النوم
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Memorizer:BrainwashLock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // قفل لمدة 24 ساعة
            
            handler = Handler(Looper.getMainLooper())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            audioFilePath = intent?.getStringExtra("filePath")
            startForegroundNotification()
            
            if (audioFilePath != null) {
                intervalIndex = 0 // البدء من الأول
                playAudioAggressive()
            }
        } catch (e: Exception) {
            stopSelf()
        }
        // التعديل 3: إعادة التشغيل فوراً إذا قتله النظام
        return START_REDELIVER_INTENT
    }

    private fun startForegroundNotification() {
        val channelId = "memorizer_channel_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Memorizer Service", NotificationManager.IMPORTANCE_HIGH) // أهمية عالية
            channel.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Memorizer Active")
            .setContentText("Running in background (Don't close)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // تثبيت الإشعار
            .setCategory(NotificationCompat.CATEGORY_ALARM) // معاملته كمنبه
            .build()

        startForeground(1, notification)
    }

    private fun playAudioAggressive() {
        if (audioFilePath == null) return

        try {
            // التعديل 4: طلب الصوت كـ "منبه" وليس موسيقى لفرض التشغيل
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM) // حيلة ليعمل كمنبه
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attr)
                    .setOnAudioFocusChangeListener(this) // الاستماع للمقاطعة
                    .build()
            } else null

            // طلب الصوت
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(this, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFilePath)
                setAudioAttributes(attr) // استخدام خصائص المنبه
                prepare()
                start()
                
                setOnCompletionListener {
                    // التعديل 5: عند الانتهاء، ننتظر المؤقت التالي
                    scheduleNext()
                }
                
                setOnErrorListener { _, _, _ ->
                    scheduleNext() // محاولة أخرى في حال الخطأ
                    true
                }
            }
        } catch (e: Exception) {
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        // حساب مدة الانتظار
        val delay = intervals[intervalIndex]
        
        // التعديل 6: تكرار المؤقت الأخير للأبد (Brainwashing Loop)
        // بدلاً من التوقف، نبقى في آخر توقيت
        if (intervalIndex < intervals.size - 1) {
            intervalIndex++
        }

        handler?.removeCallbacksAndMessages(null)
        handler?.postDelayed({
            playAudioAggressive()
        }, delay)
    }

    // التعديل 7: التعامل مع سرقة الصوت (تطبيقات أخرى)
    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // تطبيق آخر أخذ الصوت بالكامل (مثل مكالمة)
                // لا نفعل شيئاً، سنحاول مرة أخرى في الموعد القادم
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // فقدان مؤقت (رسالة واتساب) -> لا نتوقف
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // استعدنا الصوت
                if (mediaPlayer?.isPlaying == false) {
                   // يمكننا استئناف التشغيل إذا أردنا
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            handler?.removeCallbacksAndMessages(null)
            mediaPlayer?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
