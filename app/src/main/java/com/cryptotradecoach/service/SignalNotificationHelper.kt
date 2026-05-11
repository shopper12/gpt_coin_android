package com.cryptotradecoach.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.cryptotradecoach.MainActivity
import com.cryptotradecoach.data.Signal
import com.cryptotradecoach.data.local.MissedSignalEntity

class SignalNotificationHelper(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Coin Scanner Service",
                NotificationManager.IMPORTANCE_LOW,
            )
            val signalChannel = NotificationChannel(
                CHANNEL_SIGNAL,
                "Coin Signals",
                NotificationManager.IMPORTANCE_HIGH,
            )
            val changeChannel = NotificationChannel(
                CHANNEL_STRATEGY_CHANGE,
                "Strategy Changes",
                NotificationManager.IMPORTANCE_HIGH,
            )
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(signalChannel)
            manager.createNotificationChannel(changeChannel)
        }
    }

    fun foregroundNotification(): Notification {
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Coin scanner running")
            .setContentText("Scanning Upbit KRW markets every 30 seconds.")
            .setContentIntent(openAppPendingIntent(REQUEST_CODE_SERVICE))
            .setOngoing(true)
            .build()
    }

    fun notifySignal(signal: Signal, id: Int) {
        val text = "Entry ${signal.entryPrice.format()} / Stop ${signal.stopLossPrice.format()} / Target ${signal.targetPrice.format()}"
        val notification = NotificationCompat.Builder(context, CHANNEL_SIGNAL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("${signal.market} ${signal.strategyName}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${signal.reason}\n$text"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPendingIntent(id))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    fun notifyStrategyChanged(signal: Signal, previousStrategy: String, id: Int) {
        val text = "$previousStrategy -> ${signal.strategyName}"
        val notification = NotificationCompat.Builder(context, CHANNEL_STRATEGY_CHANGE)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("${signal.market} strategy changed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${signal.reason}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPendingIntent(id))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    fun notifyMissedSignal(missed: MissedSignalEntity, id: Int) {
        val text = "${missed.market} ${missed.changeRate.format()}% missed: ${missed.suggestedStrategy}"
        val notification = NotificationCompat.Builder(context, CHANNEL_SIGNAL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Missed signal detected")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${missed.missedReason}\n${missed.suggestedRuleAfter}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPendingIntent(id))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    private fun openAppPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    companion object {
        private const val REQUEST_CODE_SERVICE = 1
        const val CHANNEL_SERVICE = "scanner_service"
        const val CHANNEL_SIGNAL = "scanner_signal"
        const val CHANNEL_STRATEGY_CHANGE = "strategy_change"
    }
}

private fun Double.format(): String = String.format("%,.2f", this)
