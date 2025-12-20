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

class MemorizerService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private var handler: Handler? = null
    private var audioFilePath: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager
    
    // التوقيتات: 10 ثواني، دقيقة، 5 دقائق، ثم يثبت على 10 دقائق
    private val intervals = listOf(10000L, 60000L, 300000L, 600000L) 
    private var intervalIndex = 0

    override fun onCreate() {
        super.onCreate()
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            
            // قفل المعالج لمنع النوم، لكن نسمح للشاشة بالانطفاء
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Memorizer:LoopLock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
            
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
                intervalIndex = 0
                // نبدأ العملية فوراً
                playAudioSmart()
            }
        } catch (e: Exception) {
           // في حال الخطأ لا نوقف الخدمة
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "memorizer_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Memorizer Background", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Memorizer Active")
            .setContentText("Learning in progress...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun playAudioSmart() {
        if (audioFilePath == null) return

        try {
            // 1. إعداد خصائص الصوت (Media) ليكون طبيعياً ويتحكم به المستخدم
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA) // عاد إلى وضع الوسائط الطبيعي
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            // 2. طلب "Ducking" (خفض صوت التطبيقات الأخرى بدلاً من إيقافها)
            val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build()
            } else null

            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }

            // 3. المنطق: إذا سمح لنا النظام أو سمح لنا بخفض صوت الآخرين -> نشغل
            if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(audioFilePath)
                    setAudioAttributes(audioAttributes) // استخدام إعدادات الصوت الطبيعية
                    setVolume(1.0f, 1.0f) // صوت كامل بالنسبة لإعدادات الهاتف الحالية
                    prepare()
                    start()
                    
                    setOnCompletionListener {
                        // عند الانتهاء بنجاح، ننتقل للتوقيت التالي
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            audioManager.abandonAudioFocusRequest(focusRequest!!)
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.abandonAudioFocus(this@MemorizerService)
                        }
                        scheduleNext(success = true)
                    }
                    
                    setOnErrorListener { _, _, _ ->
                        // في حال فشل الملف، لا ننهار، بل نعيد المحاولة قريباً
                        scheduleNext(success = false)
                        true
                    }
                }
            } else {
                // النظام رفض التشغيل (مثلاً مكالمة هاتفية) -> نحاول لاحقاً
                scheduleNext(success = false)
            }
        } catch (e: Exception) {
            // أي خطأ آخر -> نحاول لاحقاً
            scheduleNext(success = false)
        }
    }

    private fun scheduleNext(success: Boolean) {
        var delay = 10000L // افتراضي 10 ثواني في حالة الفشل

        if (success) {
            // إذا نجحنا، نأخذ التوقيت الطبيعي من القائمة
            delay = intervals[intervalIndex]
            // ننتقل للمرحلة التالية، لكن لا نتوقف عند النهاية (نثبت على آخر توقيت)
            if (intervalIndex < intervals.size - 1) {
                intervalIndex++
            }
        } else {
            // إذا فشلنا (التطبيق مشغول)، نحاول بعد 10 ثواني فقط
            delay = 10000L
        }

        handler?.removeCallbacksAndMessages(null)
        handler?.postDelayed({
            playAudioSmart()
        }, delay)
    }

    override fun onAudioFocusChange(focusChange: Int) {
        // لا نحتاج لفعل شيء معقد هنا، لأننا طلبنا Ducking
        // النظام سيقوم بخفض الصوت تلقائياً
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            // إذا فقدنا الصوت تماماً، نوقف المشغل ونعتمد على الجدولة القادمة
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                }
            } catch (e: Exception) {}
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
