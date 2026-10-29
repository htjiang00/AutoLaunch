package com.autolaunch.appscheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启动 + 应用升级后重新调度
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "收到广播: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val prefs = Prefs(context)
            if (prefs.serviceRunning && prefs.targetPackage.isNotEmpty()) {
                // 重新调度闹钟
                AlarmScheduler.scheduleAll(context, prefs.getTimes())
                // 启动前台保活服务
                val svcIntent = Intent(context, SchedulerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
                Log.i(TAG, "开机自启完成：闹钟已重置，服务已启动")
            } else {
                Log.i(TAG, "服务未运行或未选目标应用，跳过自启")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
