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
    private var isSelectingSecondFile = false

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) { }
                
                val targetName = if (isSelectingSecondFile) "media_file_2" else "media_file_1"
                val copiedFile = copyFileToCache(uri, targetName)
                
                if (copiedFile != null) {
                    val msg = if (isSelectingSecondFile) "File B Set" else "File A Set"
                    Toast.makeText(this, "$msg: ${copiedFile.name}", Toast.LENGTH_SHORT).show()
                    if (isSelectingSecondFile) cachedFile2 = copiedFile else cachedFile1 = copiedFile
                }
            } catch (e: Exception) {
                 Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()
        checkOverlayPermission() // التأكد من إذن الفيديو العائم

        findViewById<Button>(R.id.btnFixBattery).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {}
        }

        findViewById<Button>(R.id.btnSelectAudio1).setOnClickListener {
            isSelectingSecondFile = false
            filePickerLauncher.launch(arrayOf("video/*", "audio/*")) // نركز على الفيديو
        }

        findViewById<Button>(R.id.btnSelectAudio2).setOnClickListener {
            isSelectingSecondFile = true
            filePickerLauncher.launch(arrayOf("video/*", "audio/*"))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant Overlay Permission first!", Toast.LENGTH_LONG).show()
                checkOverlayPermission()
                return@setOnClickListener
            }

            if (cachedFile1 == null) {
                Toast.makeText(this, "Select File A!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, MemorizerService::class.java)
            intent.action = "ACTION_START"
            intent.putExtra("filePath1", cachedFile1!!.absolutePath)
            if (cachedFile2 != null) intent.putExtra("filePath2", cachedFile2!!.absolutePath)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Video Brainwashing Started!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            val intent = Intent(this, MemorizerService::class.java)
            intent.action = "ACTION_STOP"
            startService(intent)
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun copyFileToCache(uri: Uri, baseName: String): File? {
        return try {
            var extension = "mp4" // نفترض أنه فيديو
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        val name = cursor.getString(index)
                        if (name.contains(".")) extension = name.substringAfterLast(".")
                    }
                }
            }
            val file = File(cacheDir, "$baseName.$extension")
            val outputStream = FileOutputStream(file)
            contentResolver.openInputStream(uri)?.copyTo(outputStream)
            file
        } catch (e: Exception) { null }
    }
}
