package com.autolaunch.appscheduler

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务 - 两种用途：
 *
 * 1. 优先通道：AlarmReceiver 写入 pendingLaunch 后，本服务 onServiceConnected
 *    会检查并执行启动（不受 ReceiverRestrictedContext 限制）。
 *
 * 2. 备用通道：如果前台服务没跑起来，本服务的 instance 还在时，直接用它
 *    的 context 启动目标应用。
 */
class LauncherAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")

        // 检查 AlarmReceiver 是否写了 pendingLaunch
        val prefs = Prefs(this)
        val pending = prefs.consumePendingLaunch()
        if (pending != null) {
            val (pkg, _) = pending
            Log.i(TAG, "无障碍服务收到 pendingLaunch: $pkg")
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    startActivity(launchIntent)
                    Log.i(TAG, "✓ 无障碍服务已发起启动: $pkg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "无障碍服务启动失败: ${e.message}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理任何事件（纯启动通道）
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LauncherA11y"

        @Volatile
        private var instance: LauncherAccessibilityService? = null

        /** 服务是否已被用户在系统设置里启用 */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val enabled = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (_: Exception) { null } ?: return false
            val target = android.content.ComponentName(
                context,
                LauncherAccessibilityService::class.java
            )
            return enabled.split(':').any { it.equals(target.flattenToString(), true) }
        }

        /** 用无障碍服务的 context 启动应用（备用路径） */
        fun launch(intent: Intent): Boolean {
            val svc = instance ?: return false
            return try {
                svc.startActivity(intent)
                true
            } catch (e: Exception) { false }
        }
    }
}
