package com.autolaunch.appscheduler

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置存储 - 保存用户选择的目标应用和时间
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var targetPackage: String
        get() = sp.getString(KEY_PKG, "") ?: ""
        set(value) = sp.edit().putString(KEY_PKG, value).apply()

    var targetAppName: String
        get() = sp.getString(KEY_APP_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_APP_NAME, value).apply()

    var targetAppIcon: String
        get() = sp.getString(KEY_APP_ICON, "") ?: ""
        set(value) = sp.edit().putString(KEY_APP_ICON, value).apply()

    var serviceRunning: Boolean
        get() = sp.getBoolean(KEY_RUNNING, false)
        set(value) = sp.edit().putBoolean(KEY_RUNNING, value).apply()

    // 四个时间点，格式 "HH:mm"
    fun getTimes(): List<String> {
        val list = mutableListOf<String>()
        for (i in 1..4) {
            list.add(sp.getString("time_$i", DEFAULT_TIMES[i - 1]) ?: DEFAULT_TIMES[i - 1])
        }
        return list
    }

    fun setTime(index: Int, time: String) {
        sp.edit().putString("time_${index + 1}", time).apply()
    }

    companion object {
        private const val NAME = "auto_launch_prefs"
        private const val KEY_PKG = "target_pkg"
        private const val KEY_APP_NAME = "target_app_name"
        private const val KEY_APP_ICON = "target_app_icon"
        private const val KEY_RUNNING = "service_running"
        val DEFAULT_TIMES = listOf("08:10", "11:45", "14:10", "17:45")
    }
}
