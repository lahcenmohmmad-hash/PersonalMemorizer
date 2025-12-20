package com.example.personalmemorizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // عندما يرن المنبه، نقوم بتشغيل الخدمة فوراً
        val serviceIntent = Intent(context, MemorizerService::class.java).apply {
            action = "ACTION_WAKE_UP"
            // نمرر مسار الملف المحفوظ في الذاكرة (سنجلبه داخل الخدمة)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
