package com.skob.raspisanie

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

object AlarmScheduler {

    const val EXTRA_REMINDER_INDEX = "reminder_index"

    // Ставит все напоминания из конфига на ближайшее подходящее время
    fun scheduleAll(context: Context) {
        val config = ScheduleEngine.loadConfig(context)
        config.reminders.forEachIndexed { index, reminder ->
            var next = LocalDateTime.now()
                .withHour(reminder.hour)
                .withMinute(reminder.minute)
                .withSecond(0)
                .withNano(0)
            if (next.isBefore(LocalDateTime.now())) {
                next = next.plusDays(1)
            }
            scheduleAt(context, index, next)
        }
    }

    // Переставляет одно конкретное напоминание на завтра (вызывается сразу после его срабатывания)
    fun rescheduleTomorrow(context: Context, reminderIndex: Int) {
        val config = ScheduleEngine.loadConfig(context)
        val reminder = config.reminders.getOrNull(reminderIndex) ?: return
        val next = LocalDateTime.now()
            .withHour(reminder.hour)
            .withMinute(reminder.minute)
            .withSecond(0)
            .withNano(0)
            .plusDays(1)
        scheduleAt(context, reminderIndex, next)
    }

    private fun scheduleAt(context: Context, reminderIndex: Int, next: LocalDateTime) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
        intent.putExtra(EXTRA_REMINDER_INDEX, reminderIndex)
        val pendingIntent = PendingIntent.getBroadcast(
            context, reminderIndex, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }
}
