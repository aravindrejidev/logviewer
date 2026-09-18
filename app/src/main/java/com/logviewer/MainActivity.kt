package com.logviewer

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private var currentLogs: String = ""

    // Only relevant below Android 10 (API 29), where WRITE_EXTERNAL_STORAGE
    // must be requested at runtime before writing into public Downloads.
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                writeLogsToFile()
            } else {
                Toast.makeText(
                    this,
                    "Storage permission is required to save the file",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logTextView)
        val btnFetch: Button = findViewById(R.id.btnFetchLogs)
        val btnClear: Button = findViewById(R.id.btnClear)
        val btnSave: Button = findViewById(R.id.btnSave)

        btnFetch.setOnClickListener { fetchLogs() }
        btnClear.setOnClickListener { clearLogs() }
        btnSave.setOnClickListener { saveLogs() }
    }

    /**
     * Runs "logcat -d" (dump the current buffer and exit) off the main thread
     * so a large log dump can't freeze the UI, then publishes the result.
     */
    private fun fetchLogs() {
        Thread {
            try {
                val process = Runtime.getRuntime().exec("logcat -d")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val builder = StringBuilder()
                reader.forEachLine { line -> builder.append(line).append("\n") }
                reader.close()

                runOnUiThread {
                    currentLogs = builder.toString()
                    logTextView.text = currentLogs.ifBlank { "No logs found." }
                    Toast.makeText(this, "Logs fetched successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to fetch logs: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun clearLogs() {
        currentLogs = ""
        logTextView.text = ""
    }

    private fun saveLogs() {
        if (currentLogs.isBlank()) {
            Toast.makeText(this, "No logs to save. Fetch logs first.", Toast.LENGTH_SHORT).show()
            return
        }

        when {
            // Android 10+ writes through MediaStore; no storage permission needed.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> writeLogsToFile()
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> writeLogsToFile()
            else -> requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun writeLogsToFile() {
        val fileName = "logcat_${System.currentTimeMillis()}.txt"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped-storage-safe path: insert a row into MediaStore's
                // Downloads collection and stream the text into it.
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(currentLogs.toByteArray())
                    }
                    Toast.makeText(this, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Could not create file", Toast.LENGTH_LONG).show()
                }
            } else {
                // Legacy path for API < 29, guarded by the runtime permission above.
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { it.write(currentLogs.toByteArray()) }
                Toast.makeText(this, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error while saving: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
