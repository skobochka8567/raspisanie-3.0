package com.skob.raspisanie

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ScheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_REMINDER_INDEX = "reminder_index"
    }

    override suspend fun doWork(): Result {
        return try {
            val config = ScheduleEngine.loadConfig(applicationContext)
            val reminderIndex = inputData.getInt(KEY_REMINDER_INDEX, 0)
            val reminder = config.reminders.getOrNull(reminderIndex) ?: config.reminders.firstOrNull()
            val daysAhead = reminder?.daysAhead ?: 1

            val message = ScheduleEngine.buildMessage(config, daysAhead)
            val title = if (daysAhead >= 1) "Расписание на завтра" else "Доброе утро"

            applicationContext.getSharedPreferences("raspisanie", Context.MODE_PRIVATE)
                .edit()
                .putString("last_title", title)
                .putString("last_message", message)
                .apply()

            NotificationHelper.show(applicationContext, title, message)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
