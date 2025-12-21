package com.example.personalmemorizer

import android.Manifest
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

    private var cachedFile1: File? = null
    private var cachedFile2: File? = null
    
    // متغير لتحديد أي زر تم ضغطه (الأول أم الثاني)
    private var isSelectingSecondFile = false

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                
                // حفظ باسم مختلف حسب الزر المضغوط
                val targetName = if (isSelectingSecondFile) "audio_2.mp3" else "audio_1.mp3"
                val copiedFile = copyFileToCache(uri, targetName)
                
                if (copiedFile != null) {
                    if (isSelectingSecondFile) {
                        cachedFile2 = copiedFile
                        Toast.makeText(this, "File B Selected: ${copiedFile.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        cachedFile1 = copiedFile
                        Toast.makeText(this, "File A Selected: ${copiedFile.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                 Toast.makeText(this, "Error selecting file", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Not needed", Toast.LENGTH_SHORT).show()
            }
        }

        // اختيار الملف الأول
        findViewById<Button>(R.id.btnSelectAudio1).setOnClickListener {
            isSelectingSecondFile = false
            filePickerLauncher.launch(arrayOf("audio/*"))
        }

        // اختيار الملف الثاني
        findViewById<Button>(R.id.btnSelectAudio2).setOnClickListener {
            isSelectingSecondFile = true
            filePickerLauncher.launch(arrayOf("audio/*"))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (cachedFile1 == null || !cachedFile1!!.exists()) {
                Toast.makeText(this, "Select File A at least!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val intent = Intent(this, MemorizerService::class.java)
                intent.action = "ACTION_START"
                // إرسال المسارين (الثاني قد يكون null وهذا عادي)
                intent.putExtra("filePath1", cachedFile1!!.absolutePath)
                if (cachedFile2 != null && cachedFile2!!.exists()) {
                    intent.putExtra("filePath2", cachedFile2!!.absolutePath)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                val msg = if (cachedFile2 != null) "Alternating Mode Started!" else "Single Mode Started!"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            val intent = Intent(this, MemorizerService::class.java)
            intent.action = "ACTION_STOP"
            startService(intent)
            Toast.makeText(this, "Stopping...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun copyFileToCache(uri: Uri, targetName: String): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val file = File(cacheDir, targetName)
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            file
        } catch (e: Exception) { null }
    }
}
