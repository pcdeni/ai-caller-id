package com.pcdeni.aicallerid.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pcdeni.aicallerid.MainActivity
import com.pcdeni.aicallerid.R
import com.pcdeni.aicallerid.model.CallerIntel
import com.pcdeni.aicallerid.overlay.OverlayService

object NotificationHelper {

    const val CHANNEL_ID = "caller_intel"

    fun ensureChannel(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }

    fun postIntel(context: Context, intel: CallerIntel) {
        val notification = baseBuilder(context)
            .setContentTitle("${intel.callerName} · ${intel.riskLevel}")
            .setContentText(intel.conciseSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(intel.conciseSummary))
            .build()
        post(context, intel.phoneNumber, notification)
    }

    fun postScanPrompt(context: Context, number: String) {
        val scanIntent = Intent(context, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SCAN_NOW)
            .putExtra(OverlayService.EXTRA_NUMBER, number)
        val scanPending = PendingIntent.getService(
            context,
            number.hashCode(),
            scanIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = baseBuilder(context)
            .setContentTitle(context.getString(R.string.notif_scan_prompt_title))
            .setContentText(number)
            .addAction(0, context.getString(R.string.overlay_scan_action), scanPending)
            .build()
        post(context, number, notification)
    }

    fun postError(context: Context, number: String, message: String) {
        val notification = baseBuilder(context)
            .setContentTitle(number)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()
        post(context, number, notification)
    }

    private fun baseBuilder(context: Context): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
    }

    private fun post(context: Context, number: String, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // ID derived from the number so repeat calls from the same caller update in place.
        NotificationManagerCompat.from(context).notify(number.hashCode(), notification)
    }
}
