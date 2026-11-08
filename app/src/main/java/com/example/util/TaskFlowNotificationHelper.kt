package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.R

/**
 * Manages TaskFlow notification channels and notification builders with official brand styling:
 * - Large Icon: Full-color TaskFlow brand logo (R.drawable.ic_taskflow_logo)
 * - Small Icon: Clean stylized TaskFlow emblem (R.drawable.ic_notification)
 * - Brand Accent Color: #6D28D9 (TaskFlow Purple)
 */
object TaskFlowNotificationHelper {

  const val CHANNEL_ID_REWARDS = "taskflow_rewards_channel"
  const val CHANNEL_NAME_REWARDS = "TaskFlow Rewards & Updates"
  const val CHANNEL_DESC_REWARDS = "Notifications for INR reward credits, approvals, and platform updates"

  fun ensureNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

      val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID_REWARDS)
      if (existingChannel == null) {
        val channel =
          NotificationChannel(
            CHANNEL_ID_REWARDS,
            CHANNEL_NAME_REWARDS,
            NotificationManager.IMPORTANCE_DEFAULT
          ).apply {
            description = CHANNEL_DESC_REWARDS
            enableLights(true)
            lightColor = 0xFF6D28D9.toInt()
            enableVibration(true)
          }
        notificationManager.createNotificationChannel(channel)
      }
    }
  }

  fun createBrandedNotificationBuilder(
    context: Context,
    channelId: String = CHANNEL_ID_REWARDS
  ): NotificationCompat.Builder {
    ensureNotificationChannels(context)

    val largeLogoBitmap =
      runCatching {
        BitmapFactory.decodeResource(context.resources, R.drawable.ic_taskflow_logo)
      }.getOrNull()

    return NotificationCompat.Builder(context, channelId)
      .setSmallIcon(R.drawable.ic_notification)
      .apply {
        if (largeLogoBitmap != null) {
          setLargeIcon(largeLogoBitmap)
        }
      }
      .setColor(0xFF6D28D9.toInt())
      .setAutoCancel(true)
  }
}
