package com.skob.raspisanie

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val enableButton = findViewById<Button>(R.id.enableButton)
        val testButton = findViewById<Button>(R.id.testButton)

        val config = ScheduleEngine.loadConfig(this)
        val remindersText = config.reminders.joinToString("\n") {
            "• ${"%02d:%02d".format(it.hour, it.minute)}"
        }
        statusText.text = "СБОРКА-МЕТКА: v5-компакт\n\n" +
            "Уведомления приходят:\n$remindersText\n\n" +
            "Расписание, адрес и что брать — из config.json внутри проекта."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        enableButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(AlarmManager::class.java)
                if (!alarmManager.canScheduleExactAlarms()) {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            AlarmScheduler.scheduleAll(this)
            statusText.text = "Готово: ежедневные уведомления включены."
        }

        testButton.setOnClickListener {
            val request = OneTimeWorkRequestBuilder<ScheduleWorker>().build()
            WorkManager.getInstance(this).enqueue(request)
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("raspisanie", Context.MODE_PRIVATE)
        val lastTitle = prefs.getString("last_title", null)
        val lastMessage = prefs.getString("last_message", null)
        val lastMessageView = findViewById<TextView>(R.id.lastMessageText)
        lastMessageView.text = if (lastMessage != null) {
            "$lastTitle\n\n$lastMessage"
        } else {
            "Пока не было ни одного уведомления — нажми кнопку теста выше."
        }
    }
}
