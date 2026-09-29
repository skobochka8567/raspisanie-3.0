package com.skob.raspisanie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderIndex = intent.getIntExtra(AlarmScheduler.EXTRA_REMINDER_INDEX, 0)

        val data = Data.Builder()
            .putInt(ScheduleWorker.KEY_REMINDER_INDEX, reminderIndex)
            .build()
        val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(request)

        // Сразу планируем это же напоминание на завтра
        AlarmScheduler.rescheduleTomorrow(context, reminderIndex)
    }
}
