package ru.mydataexpress.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ru.mydataexpress.android.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val openDatabase = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_DATABASE_URI, uri.toString())
            .apply()

        showSelectedDatabase(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectDatabaseButton.setOnClickListener {
            openDatabase.launch(
                arrayOf(
                    "application/octet-stream",
                    "application/x-firebird",
                    "*/*"
                )
            )
        }

        binding.checkRuntimeButton.setOnClickListener {
            binding.runtimeStatusText.text = buildRuntimeReport()
        }

        binding.startButton.setOnClickListener {
            binding.runtimeStatusText.text =
                "Запуск пока заблокирован: Wine/Box64 runtime ещё не установлен."
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_DATABASE_URI, null)
            ?.let(Uri::parse)
            ?.let(::showSelectedDatabase)

        binding.runtimeStatusText.text = buildRuntimeReport()
    }

    private fun showSelectedDatabase(uri: Uri) {
        binding.selectedFileText.text = uri.toString()
    }

    private fun buildRuntimeReport(): String {
        val runtimeRoot = File(filesDir, "runtime")
        val required = listOf(
            "box64/bin/box64",
            "wine/bin/wine",
            "prefix/drive_c/DataExpress/DataExpress.exe",
            "runtime-manifest.json"
        )

        val checks = required.joinToString("\n") { relative ->
            val present = File(runtimeRoot, relative).exists()
            "${if (present) "[OK]" else "[--]"} $relative"
        }

        val ready = required.all { File(runtimeRoot, it).exists() }
        binding.startButton.isEnabled = ready

        return buildString {
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Runtime: ${runtimeRoot.absolutePath}")
            appendLine()
            append(checks)
            appendLine()
            appendLine()
            append(if (ready) "Runtime готов к пробному запуску." else "Runtime ещё не подготовлен.")
        }
    }

    private companion object {
        const val PREFS = "dataexpress_android"
        const val KEY_DATABASE_URI = "database_uri"
    }
}
