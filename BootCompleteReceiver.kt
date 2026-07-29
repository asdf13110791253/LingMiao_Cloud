package com.lingmiao.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lingmiao.core.log.LingMiaoLogger
import com.lingmiao.service.FloatingAssistService

/**
 * 开机自启Receiver - 保持服务存活
 */
class BootCompleteReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT -> {
                LingMiaoLogger.i(TAG, "Boot/Unlock detected, restarting service")
                // 延迟启动避免系统忙时
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    FloatingAssistService.start(context, "portrait")
                }, 3000)
            }
        }
    }
}
