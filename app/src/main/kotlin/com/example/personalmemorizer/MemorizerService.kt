package com.example.personalmemorizer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemorizerService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var audioManager: AudioManager
    private lateinit var alarmManager: AlarmManager
    private lateinit var prefs: SharedPreferences
    private var wakeLock: PowerManager.WakeLock? = null

    // التوقيتات (SRS)
    private val intervals = listOf(10000L, 60000L, 300000L, 600000L)

    companion object {
        const val CHANNEL_ID = "memorizer_adhan_mode"
        const val ACTION_WAKE_UP = "ACTION_WAKE_UP"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        prefs = getSharedPreferences("MemorizerPrefs", Context.MODE_PRIVATE)
        
        // قفل مؤقت فقط وقت التشغيل
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Memorizer:AdhanLock")
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        // جلب المسار (إما من النية أو من الذاكرة المحفوظة)
        var filePath = intent?.getStringExtra("filePath")
        if (filePath == null) {
            filePath = prefs.getString("saved_file_path", null)
        } else {
            // حفظ المسار الجديد للمستقبل
            prefs.edit().putString("saved_file_path", filePath).putInt("interval_index", 0).apply()
        }

        if (filePath != null) {
            createNotificationChannel()
            startForeground(1, buildNotification("Playing audio...", true))
            
            // تشغيل الصوت فوراً
            playAudio(filePath)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun playAudio(path: String) {
        wakeLock?.acquire(2 * 60 * 1000L) // استيقظ لمدة دقيقتين كحد أقصى

        try {
            // طلب إيقاف التطبيقات الأخرى بقوة (مثل الأذان)
            val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM) // معاملته كمنبه
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(this)
                    .build()
            } else null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(this, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                prepare()
                start()
                setOnCompletionListener {
                    // انتهى التشغيل، الآن نجدول الأذان القادم ونغلق
                    scheduleNextAlarm()
                    stopSelf() 
                }
                setOnErrorListener { _, _, _ ->
                    scheduleNextAlarm()
                    stopSelf()
                    true
                }
            }
        } catch (e: Exception) {
            scheduleNextAlarm()
            stopSelf()
        }
    }

    private fun scheduleNextAlarm() {
        // حساب الموعد القادم
        var index = prefs.getInt("interval_index", 0)
        val delay = intervals[index]
        
        // زيادة المؤشر للمرة القادمة
        if (index < intervals.size - 1) index++
        prefs.edit().putInt("interval_index", index).apply()

        val triggerTime = System.currentTimeMillis() + delay
        val dateString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(triggerTime))

        // تحديث الإشعار ليخبر المستخدم بالموعد القادم قبل أن نموت
        val notification = buildNotification("Next play at: $dateString", false)
        getSystemService(NotificationManager::class.java).notify(1, notification)

        // جدولة المنبه (تكنولوجيا الأذان)
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // استخدام setAlarmClock وهو الأقوى في أندرويد (يظهر أيقونة المنبه في البار العلوي)
        val alarmInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
        alarmManager.setAlarmClock(alarmInfo, pendingIntent)
    }

    private fun buildNotification(text: String, isPlaying: Boolean): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isPlaying) "Memorizer Speaking" else "Memorizer Sleeping")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(isPlaying) // تثبيت الإشعار فقط أثناء التشغيل
            .setCategory(if (isPlaying) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Memorizer Adhan", NotificationManager.IMPORTANCE_HIGH)
            channel.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {} // الأذان لا يتوقف

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
