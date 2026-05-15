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
import com.cryptotradecoach.data.local.StrategyEventType
import com.cryptotradecoach.data.local.StrategyHistoryEntity

class SignalNotificationHelper(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    "Coin Scanner Service",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STRATEGY,
                    "Strategy Events",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    fun foregroundNotification(scanIntervalMs: Long): Notification {
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("코인 스캐너 실행 중")
            .setContentText("Upbit KRW 시장을 ${scanIntervalMs / 1000}초 주기로 자동 스캔합니다.")
            .setContentIntent(openAppPendingIntent(REQUEST_CODE_SERVICE))
            .setOngoing(true)
            .build()
    }

    fun notifyStrategyEvent(event: StrategyHistoryEntity, id: Int) {
        val title = when (event.eventType) {
            StrategyEventType.NEW_ACTIVE -> "${event.symbol} 신규 전략"
            StrategyEventType.RANK_UP -> "${event.symbol} 순위 상승"
            StrategyEventType.PRICE_PLAN_CHANGED -> "${event.symbol} 매매전략 변경"
            StrategyEventType.INVALIDATED -> "${event.symbol} 전략 무효화"
            StrategyEventType.HIT_TARGET -> "${event.symbol} 목표가 도달"
            StrategyEventType.STOPPED_OUT -> "${event.symbol} 손절가 도달"
            StrategyEventType.EXPIRED -> "${event.symbol} 전략 만료"
            else -> "${event.symbol} 전략 알림"
        }
        val text = event.message
        val detail = listOfNotNull(event.message, event.newSummary).joinToString("\n")
        val notification = NotificationCompat.Builder(context, CHANNEL_STRATEGY)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
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
        const val CHANNEL_STRATEGY = "strategy_events"
    }
}
