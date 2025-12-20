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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class MemorizerService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var silentTrack: AudioTrack? = null
    private var audioFilePath: String? = null
    private lateinit var audioManager: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val intervals = listOf(10000L, 60000L, 300000L, 600000L)
    private var intervalIndex = 0

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Memorizer:EternalLock")
        wakeLock?.setReferenceCounted(false)
        
        setupSilentAudio()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // --- استقبال أمر القتل ---
        if (action == "ACTION_STOP") {
            killService()
            return START_NOT_STICKY
        }

        val filePath = intent?.getStringExtra("filePath")
        if (filePath != null) {
            // تفعيل القفل فقط عند البدء
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
            
            audioFilePath = filePath
            intervalIndex = 0
            createNotificationChannel()
            startForeground(1, buildNotification("Started"))
            playRealAudio()
        }
        
        return START_STICKY
    }

    // --- دالة الانتحار (Clean Exit) ---
    private fun killService() {
        try {
            // 1. إيقاف العدادات
            handler.removeCallbacksAndMessages(null)
            
            // 2. إيقاف الصوت الحقيقي
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null

            // 3. إيقاف الصمت
            if (silentTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                silentTrack?.stop()
            }
            silentTrack?.release()
            silentTrack = null

            // 4. تحرير القفل
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }

            // 5. إزالة الإشعار وقتل الخدمة
            stopForeground(true)
            stopSelf()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playRealAudio() {
        if (audioFilePath == null) return
        
        pauseSilentAudio()
        updateNotification("🔊 Memorizing now...")

        try {
            requestFocusCall()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFilePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                prepare()
                start()
                setOnCompletionListener {
                    abandonFocusCall()
                    startWaitingPeriod()
                }
                setOnErrorListener { _, _, _ ->
                    startWaitingPeriod()
                    true
                }
            }
        } catch (e: Exception) {
            startWaitingPeriod()
        }
    }

    private fun startWaitingPeriod() {
        val delay = intervals[intervalIndex]
        if (intervalIndex < intervals.size - 1) intervalIndex++

        updateNotification("⏳ Next in: ${delay / 1000} sec")
        playSilentAudio() // إبقاء الهاتف مستيقظاً

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            playRealAudio()
        }, delay)
    }

    private fun setupSilentAudio() {
        try {
            val sampleRate = 44100
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            silentTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val silentData = ByteArray(bufferSize)
            silentTrack?.write(silentData, 0, silentData.size)
            silentTrack?.setLoopPoints(0, bufferSize / 2, -1)
        } catch (e: Exception) {}
    }

    private fun playSilentAudio() {
        try {
            if (silentTrack?.state == AudioTrack.STATE_INITIALIZED && silentTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                silentTrack?.play()
            }
        } catch (e: Exception) {}
    }

    private fun pauseSilentAudio() {
        try {
            if (silentTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                silentTrack?.pause()
            }
        } catch (e: Exception) {}
    }

    private fun requestFocusCall() {
        val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
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
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonFocusCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
             val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
             audioManager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun buildNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "memorizer_eternal")
            .setContentTitle("Memorizer Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("memorizer_eternal", "Memorizer Background", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        killService() // ضمان التنظيف حتى لو تم تدمير الخدمة من النظام
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
