package com.example.personalmemorizer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var cachedAudioFile: File? = null

    // نستخدم OpenDocument لأنه أكثر توافقاً
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                // الحل الجذري: نسخ الملف إلى ذاكرة التطبيق لتجنب مشاكل الصلاحيات
                val copiedFile = copyFileToCache(uri)
                if (copiedFile != null) {
                    cachedAudioFile = copiedFile
                    Toast.makeText(this, "Audio Ready: ${copiedFile.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to copy file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()

        findViewById<Button>(R.id.btnFixBattery).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Not supported on this device", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSelectAudio).setOnClickListener {
            filePickerLauncher.launch(arrayOf("audio/*"))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (cachedAudioFile == null || !cachedAudioFile!!.exists()) {
                Toast.makeText(this, "Please select an audio file first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val intent = Intent(this, MemorizerService::class.java)
                // نرسل مسار الملف الداخلي المضمون
                intent.putExtra("filePath", cachedAudioFile!!.absolutePath)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "Starting Brainwashing...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error starting: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, MemorizerService::class.java))
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    // دالة لنسخ الملف وتجنب الانهيار
    private fun copyFileToCache(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            // الحصول على اسم الملف
            var fileName = "audio_temp.mp3"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) fileName = cursor.getString(index)
                }
            }
            
            val file = File(cacheDir, fileName)
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
