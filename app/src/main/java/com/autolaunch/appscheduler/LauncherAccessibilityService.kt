package com.autolaunch.appscheduler

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务 - 仅用于「从后台启动指定应用」
 *
 * 用途：Android 10+ 禁止后台启动 Activity，无障碍服务是官方豁免通道之一。
 * 相比悬浮窗权限，无障碍服务不需要高危权限，也更符合厂商安全策略。
 *
 * 本服务不读取任何窗口内容（canRetrieveWindowContent=false），
 * 也不处理任何事件，只提供一个可以安全 startActivity 的上下文。
 */
class LauncherAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理任何事件
    }

    override fun onInterrupt() {
        // 不需要中断处理
    }

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
            } catch (_: Exception) {
                null
            } ?: return false

            val target = ComponentName(context, LauncherAccessibilityService::class.java)
            return enabled.split(':').any { it.equals(target.flattenToString(), true) }
        }

        /** 用无障碍服务的上下文启动应用（不受后台启动限制） */
        fun launch(intent: Intent): Boolean {
            val svc = instance ?: return false
            return try {
                svc.startActivity(intent)
                Log.i(TAG, "通过无障碍服务启动应用成功")
                true
            } catch (e: Exception) {
                Log.e(TAG, "无障碍启动失败: ${e.message}")
                false
            }
        }
    }
}
