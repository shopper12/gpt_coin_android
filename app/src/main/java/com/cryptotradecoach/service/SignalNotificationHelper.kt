package com.cryptotradecoach.service

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
import androidx.core.content.ContextCompat
import com.cryptotradecoach.HomeActivity
import com.cryptotradecoach.MainActivity
import com.cryptotradecoach.data.AppUpdateRepository
import com.cryptotradecoach.data.local.StrategyEventType
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import java.util.Locale

class SignalNotificationHelper(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    "백그라운드 시장 감시",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STRATEGY,
                    "코인 전략 변화",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_GLOBAL_MARKET,
                    "지금 확인할 거래기회",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "전세계 시장에서 실제로 확인할 가치가 있는 신규 거래기회만 알립니다."
                    enableVibration(true)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_APP_UPDATE,
                    "앱 업데이트",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    fun foregroundNotification(scanIntervalMs: Long): Notification {
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("시장 감시 중")
            .setContentText("앱을 계속 보고 있을 필요 없습니다. 의미 있는 변화만 알립니다.")
            .setContentIntent(openMoneyDashboardPendingIntent(REQUEST_CODE_SERVICE))
            .setOngoing(true)
            .build()
    }

    fun notifyStrategyEvent(event: StrategyHistoryEntity, id: Int) {
        val title = when (event.eventType) {
            StrategyEventType.NEW_ACTIVE -> "${event.symbol} · 실행조건 확인"
            StrategyEventType.RANK_UP -> "${event.symbol} · 우선순위 상승"
            StrategyEventType.PRICE_PLAN_CHANGED -> "${event.symbol} · 진입/손절 변경"
            StrategyEventType.WATCH_ONLY -> "${event.symbol} · 지금은 관찰만"
            StrategyEventType.INVALIDATED -> "${event.symbol} · 전략 폐기"
            StrategyEventType.TARGET1_HIT -> "${event.symbol} · 1차 목표 도달"
            StrategyEventType.TRAILING_STOP_HIT -> "${event.symbol} · 이익보호 조건 도달"
            StrategyEventType.HIT_TARGET -> "${event.symbol} · 목표가 도달"
            StrategyEventType.STOPPED_OUT -> "${event.symbol} · 손절 조건 도달"
            StrategyEventType.EXPIRED -> "${event.symbol} · 전략 만료"
            else -> "${event.symbol} · 전략 변화"
        }
        val text = event.message
        val detail = listOfNotNull(event.message, event.newSummary).joinToString("\n")
        val notification = NotificationCompat.Builder(context, CHANNEL_STRATEGY)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openCoinDetailPendingIntent(id))
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(id, notification)
    }

    fun notifyGlobalMarketSignal(
        notificationId: Int,
        ticker: String,
        name: String,
        direction: String,
        score: Double,
        currentPrice: Double,
        currency: String,
        entryLow: Double,
        entryHigh: Double,
        stopLoss: Double,
        target1: Double,
        target2: Double,
        reason: String,
    ) {
        val directionText = directionKorean(direction)
        val scoreText = String.format(Locale.US, "%.0f", score)
        val title = "확인할 것 · ${name.ifBlank { ticker }} $directionText · ${scoreText}점"
        val entryText = "진입 ${formatPrice(entryLow, currency)}~${formatPrice(entryHigh, currency)} · 손절 ${formatPrice(stopLoss, currency)}"
        val detail = buildString {
            append("지금 행동: 진입구간이면 손절을 먼저 확인, 아니면 기다리기\n")
            append("현재 ").append(formatPrice(currentPrice, currency)).append(" · ").append(entryText).append("\n")
            append("목표 ").append(formatPrice(target1, currency)).append(" → ").append(formatPrice(target2, currency))
            if (reason.isNotBlank()) append("\n왜? ").append(reason)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_GLOBAL_MARKET)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(entryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(openMoneyDashboardPendingIntent(notificationId))
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(notificationId, notification)
    }

    fun notifyAppUpdateAvailable(info: AppUpdateRepository.ReleaseApkInfo) {
        val text = "현재 ${info.currentVersionCode}, 최신 ${info.versionCode} (${info.versionName})"
        val notification = NotificationCompat.Builder(context, CHANNEL_APP_UPDATE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("내 돈 대시보드 업데이트")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n앱의 설정에서 최신 APK를 설치할 수 있습니다."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openMoneyDashboardPendingIntent(REQUEST_CODE_APP_UPDATE))
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(NOTIFICATION_ID_APP_UPDATE, notification)
    }

    private fun notifyIfAllowed(id: Int, notification: Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(id, notification)
    }

    private fun openMoneyDashboardPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, requestCode, intent, pendingIntentFlags())
    }

    private fun openCoinDetailPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }

    private fun directionKorean(value: String): String = when (value.uppercase()) {
        "LONG" -> "상승"
        "SHORT" -> "하락"
        "INVERSE" -> "인버스"
        "DEFENSIVE" -> "방어"
        else -> value
    }

    private fun formatPrice(value: Double, currency: String): String {
        if (value <= 0.0) return "-"
        val decimals = when {
            value >= 1_000 -> 0
            value >= 10 -> 2
            else -> 4
        }
        return String.format(Locale.US, "%,.${decimals}f %s", value, currency)
    }

    companion object {
        private const val REQUEST_CODE_SERVICE = 1
        private const val REQUEST_CODE_APP_UPDATE = 2
        private const val NOTIFICATION_ID_APP_UPDATE = 80
        const val CHANNEL_SERVICE = "scanner_service"
        const val CHANNEL_STRATEGY = "strategy_events"
        const val CHANNEL_GLOBAL_MARKET = "global_market_signals"
        const val CHANNEL_APP_UPDATE = "app_updates"
    }
}
